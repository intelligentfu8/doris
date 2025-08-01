// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.common.cache;

import org.apache.doris.analysis.UserIdentity;
import org.apache.doris.catalog.DatabaseIf;
import org.apache.doris.catalog.Env;
import org.apache.doris.catalog.OlapTable;
import org.apache.doris.catalog.Partition;
import org.apache.doris.catalog.TableIf;
import org.apache.doris.catalog.TableIf.TableType;
import org.apache.doris.catalog.View;
import org.apache.doris.common.Config;
import org.apache.doris.common.ConfigBase.DefaultConfHandler;
import org.apache.doris.common.Pair;
import org.apache.doris.common.Status;
import org.apache.doris.common.util.DebugUtil;
import org.apache.doris.datasource.CatalogIf;
import org.apache.doris.metric.MetricRepo;
import org.apache.doris.mysql.privilege.DataMaskPolicy;
import org.apache.doris.mysql.privilege.RowFilterPolicy;
import org.apache.doris.nereids.CascadesContext;
import org.apache.doris.nereids.SqlCacheContext;
import org.apache.doris.nereids.SqlCacheContext.CacheKeyType;
import org.apache.doris.nereids.SqlCacheContext.FullColumnName;
import org.apache.doris.nereids.SqlCacheContext.FullTableName;
import org.apache.doris.nereids.SqlCacheContext.ScanTable;
import org.apache.doris.nereids.SqlCacheContext.TableVersion;
import org.apache.doris.nereids.StatementContext;
import org.apache.doris.nereids.analyzer.UnboundVariable;
import org.apache.doris.nereids.parser.NereidsParser;
import org.apache.doris.nereids.properties.PhysicalProperties;
import org.apache.doris.nereids.rules.analysis.ExpressionAnalyzer;
import org.apache.doris.nereids.rules.analysis.UserAuthentication;
import org.apache.doris.nereids.rules.expression.ExpressionRewriteContext;
import org.apache.doris.nereids.rules.expression.rules.FoldConstantRuleOnFE;
import org.apache.doris.nereids.trees.expressions.Expression;
import org.apache.doris.nereids.trees.expressions.Variable;
import org.apache.doris.nereids.trees.expressions.functions.ExpressionTrait;
import org.apache.doris.nereids.trees.plans.Plan;
import org.apache.doris.nereids.trees.plans.RelationId;
import org.apache.doris.nereids.trees.plans.logical.LogicalEmptyRelation;
import org.apache.doris.nereids.trees.plans.logical.LogicalSqlCache;
import org.apache.doris.nereids.util.Utils;
import org.apache.doris.proto.InternalService;
import org.apache.doris.proto.Types.PUniqueId;
import org.apache.doris.qe.ConnectContext;
import org.apache.doris.qe.ResultSet;
import org.apache.doris.qe.cache.CacheAnalyzer;
import org.apache.doris.qe.cache.SqlCache;
import org.apache.doris.rpc.RpcException;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * NereidsSqlCacheManager
 */
public class NereidsSqlCacheManager {
    private static final Logger LOG = LogManager.getLogger(NereidsSqlCacheManager.class);
    // key: <ctl.db>:<user>:<sql>
    // value: SqlCacheContext
    private volatile Cache<String, SqlCacheContext> sqlCaches;

    public NereidsSqlCacheManager() {
        sqlCaches = buildSqlCaches(
                Config.sql_cache_manage_num,
                Config.expire_sql_cache_in_fe_second
        );
    }

    public static synchronized void updateConfig() {
        Env currentEnv = Env.getCurrentEnv();
        if (currentEnv == null) {
            return;
        }
        NereidsSqlCacheManager sqlCacheManager = currentEnv.getSqlCacheManager();
        if (sqlCacheManager == null) {
            return;
        }

        Cache<String, SqlCacheContext> sqlCaches = buildSqlCaches(
                Config.sql_cache_manage_num,
                Config.expire_sql_cache_in_fe_second
        );
        sqlCaches.putAll(sqlCacheManager.sqlCaches.asMap());
        sqlCacheManager.sqlCaches = sqlCaches;
    }

    private static Cache<String, SqlCacheContext> buildSqlCaches(int sqlCacheNum, long expireAfterAccessSeconds) {
        Caffeine<Object, Object> cacheBuilder = Caffeine.newBuilder()
                // auto evict cache when jvm memory too low
                .softValues();
        if (sqlCacheNum > 0) {
            cacheBuilder.maximumSize(sqlCacheNum);
        }
        if (expireAfterAccessSeconds > 0) {
            cacheBuilder = cacheBuilder.expireAfterAccess(Duration.ofSeconds(expireAfterAccessSeconds));
        }

        return cacheBuilder.build();
    }

    /**
     * tryAddFeCache
     */
    public void tryAddFeSqlCache(ConnectContext connectContext, String sql) {
        switch (connectContext.getCommand()) {
            case COM_STMT_EXECUTE:
            case COM_STMT_PREPARE:
                return;
            default: { }
        }

        Optional<SqlCacheContext> sqlCacheContextOpt = connectContext.getStatementContext().getSqlCacheContext();
        if (!sqlCacheContextOpt.isPresent()) {
            return;
        }

        SqlCacheContext sqlCacheContext = sqlCacheContextOpt.get();
        sqlCacheContext.setQueryId(connectContext.queryId());
        String key = sqlCacheContext.getCacheKeyType() == CacheKeyType.SQL
                ? generateCacheKey(connectContext, normalizeSql(sql))
                : generateCacheKey(connectContext, DebugUtil.printId(sqlCacheContext.getOrComputeCacheKeyMd5()));
        if (sqlCaches.getIfPresent(key) == null && sqlCacheContext.getOrComputeCacheKeyMd5() != null
                && sqlCacheContext.getResultSetInFe().isPresent()) {
            sqlCaches.put(key, sqlCacheContext);
        }
    }

    /**
     * tryAddBeCache
     */
    public void tryAddBeCache(ConnectContext connectContext, String sql, CacheAnalyzer analyzer) {
        switch (connectContext.getCommand()) {
            case COM_STMT_EXECUTE:
            case COM_STMT_PREPARE:
                return;
            default: { }
        }
        Optional<SqlCacheContext> sqlCacheContextOpt = connectContext.getStatementContext().getSqlCacheContext();
        if (!sqlCacheContextOpt.isPresent()) {
            return;
        }
        if (!(analyzer.getCache() instanceof SqlCache)) {
            return;
        }
        SqlCacheContext sqlCacheContext = sqlCacheContextOpt.get();
        sqlCacheContext.setQueryId(connectContext.queryId());
        String key = sqlCacheContext.getCacheKeyType() == CacheKeyType.SQL
                ? generateCacheKey(connectContext, normalizeSql(sql))
                : generateCacheKey(connectContext, DebugUtil.printId(sqlCacheContext.getOrComputeCacheKeyMd5()));
        if (sqlCaches.getIfPresent(key) == null && sqlCacheContext.getOrComputeCacheKeyMd5() != null) {
            SqlCache cache = (SqlCache) analyzer.getCache();
            sqlCacheContext.setSumOfPartitionNum(cache.getSumOfPartitionNum());
            sqlCacheContext.setLatestPartitionId(cache.getLatestId());
            sqlCacheContext.setLatestPartitionVersion(cache.getLatestVersion());
            sqlCacheContext.setLatestPartitionTime(cache.getLatestTime());
            sqlCacheContext.setCacheProxy(cache.getProxy());

            for (ScanTable scanTable : analyzer.getScanTables()) {
                sqlCacheContext.addScanTable(scanTable);
            }

            sqlCaches.put(key, sqlCacheContext);
        }
    }

    /**
     * tryParseSql
     */
    public Optional<LogicalSqlCache> tryParseSql(ConnectContext connectContext, String sql) {
        switch (connectContext.getCommand()) {
            case COM_STMT_EXECUTE:
            case COM_STMT_PREPARE:
                return Optional.empty();
            default: { }
        }
        String key = generateCacheKey(connectContext, normalizeSql(sql.trim()));
        SqlCacheContext sqlCacheContext = sqlCaches.getIfPresent(key);
        if (sqlCacheContext == null) {
            return Optional.empty();
        }

        // LOG.info("Total size: " + GraphLayout.parseInstance(sqlCacheContext).totalSize());
        UserIdentity currentUserIdentity = connectContext.getCurrentUserIdentity();
        List<Variable> currentVariables = resolveUserVariables(sqlCacheContext);
        if (usedVariablesChanged(currentVariables, sqlCacheContext)) {
            String md5 = DebugUtil.printId(
                    sqlCacheContext.doComputeCacheKeyMd5(Utils.fastToImmutableSet(currentVariables)));
            String md5CacheKey = generateCacheKey(connectContext, md5);
            SqlCacheContext sqlCacheContextWithVariable = sqlCaches.getIfPresent(md5CacheKey);

            // already exist cache in the fe, but the variable is different to this query,
            // we should create another cache context in fe, use another cache key
            connectContext.getStatementContext()
                    .getSqlCacheContext().ifPresent(ctx -> ctx.setCacheKeyType(CacheKeyType.MD5));

            if (sqlCacheContextWithVariable != null) {
                return tryParseSql(
                        connectContext, md5CacheKey, sqlCacheContextWithVariable, currentUserIdentity, true
                );
            } else {
                return Optional.empty();
            }
        } else {
            return tryParseSql(connectContext, key, sqlCacheContext, currentUserIdentity, false);
        }
    }

    private String generateCacheKey(ConnectContext connectContext, String sqlOrMd5) {
        CatalogIf<?> currentCatalog = connectContext.getCurrentCatalog();
        String currentCatalogName = currentCatalog != null ? currentCatalog.getName() : "";
        String currentDatabase = connectContext.getDatabase();
        String currentDatabaseName = currentDatabase != null ? currentDatabase : "";
        return currentCatalogName + "." + currentDatabaseName + ":" + connectContext.getCurrentUserIdentity().toString()
                + ":" + sqlOrMd5;
    }

    private String normalizeSql(String sql) {
        return NereidsParser.removeCommentAndTrimBlank(sql);
    }

    private Optional<LogicalSqlCache> tryParseSql(
            ConnectContext connectContext, String key, SqlCacheContext sqlCacheContext,
            UserIdentity currentUserIdentity, boolean checkUserVariable) {
        try {
            Env env = connectContext.getEnv();

            if (!tryLockTables(connectContext, env, sqlCacheContext)) {
                return invalidateCache(key);
            }

            // check table and view and their columns authority
            if (privilegeChanged(connectContext, env, sqlCacheContext)) {
                return invalidateCache(key);
            }
            if (tablesOrDataChanged(env, sqlCacheContext)) {
                return invalidateCache(key);
            }
            if (viewsChanged(env, sqlCacheContext)) {
                return invalidateCache(key);
            }

            LogicalEmptyRelation whateverPlan = new LogicalEmptyRelation(new RelationId(0), ImmutableList.of());
            if (nondeterministicFunctionChanged(whateverPlan, connectContext, sqlCacheContext)) {
                return invalidateCache(key);
            }

            // table structure and data not changed, now check policy
            if (rowPoliciesChanged(currentUserIdentity, env, sqlCacheContext)) {
                return invalidateCache(key);
            }
            if (dataMaskPoliciesChanged(currentUserIdentity, env, sqlCacheContext)) {
                return invalidateCache(key);
            }
            Optional<ResultSet> resultSetInFe = sqlCacheContext.getResultSetInFe();

            List<Variable> currentVariables = ImmutableList.of();
            if (checkUserVariable) {
                currentVariables = resolveUserVariables(sqlCacheContext);
            }
            boolean usedVariablesChanged
                    = checkUserVariable && usedVariablesChanged(currentVariables, sqlCacheContext);
            if (resultSetInFe.isPresent() && !usedVariablesChanged) {
                MetricRepo.COUNTER_CACHE_HIT_SQL.increase(1L);

                String cachedPlan = sqlCacheContext.getPhysicalPlan();
                LogicalSqlCache logicalSqlCache = new LogicalSqlCache(
                        sqlCacheContext.getQueryId(), sqlCacheContext.getColLabels(), sqlCacheContext.getFieldInfos(),
                        sqlCacheContext.getResultExprs(), resultSetInFe, ImmutableList.of(),
                        "none", cachedPlan
                );
                return Optional.of(logicalSqlCache);
            }

            Status status = new Status();

            PUniqueId cacheKeyMd5;
            if (usedVariablesChanged) {
                invalidateCache(key);
                cacheKeyMd5 = sqlCacheContext.doComputeCacheKeyMd5(Utils.fastToImmutableSet(currentVariables));
            } else {
                cacheKeyMd5 = sqlCacheContext.getOrComputeCacheKeyMd5();
            }

            InternalService.PFetchCacheResult cacheData =
                    SqlCache.getCacheData(sqlCacheContext.getCacheProxy(),
                            cacheKeyMd5, sqlCacheContext.getLatestPartitionId(),
                            sqlCacheContext.getLatestPartitionVersion(), sqlCacheContext.getLatestPartitionTime(),
                            sqlCacheContext.getSumOfPartitionNum(), status);

            if (status.ok() && cacheData != null && cacheData.getStatus() == InternalService.PCacheStatus.CACHE_OK) {
                List<InternalService.PCacheValue> cacheValues = cacheData.getValuesList();
                String cachedPlan = sqlCacheContext.getPhysicalPlan();
                String backendAddress = SqlCache.findCacheBe(cacheKeyMd5).getAddress();

                MetricRepo.COUNTER_CACHE_HIT_SQL.increase(1L);

                LogicalSqlCache logicalSqlCache = new LogicalSqlCache(
                        sqlCacheContext.getQueryId(), sqlCacheContext.getColLabels(), sqlCacheContext.getFieldInfos(),
                        sqlCacheContext.getResultExprs(), Optional.empty(),
                        cacheValues, backendAddress, cachedPlan
                );
                return Optional.of(logicalSqlCache);
            }
            return Optional.empty();
        } catch (Throwable t) {
            return invalidateCache(key);
        }
    }

    private boolean tablesOrDataChanged(Env env, SqlCacheContext sqlCacheContext) {
        if (sqlCacheContext.hasUnsupportedTables()) {
            return true;
        }

        // the query maybe scan empty partition of the table, we should check these table version too,
        // but the table not exists in sqlCacheContext.getScanTables(), so we need check here.
        // check table type and version
        for (Entry<FullTableName, TableVersion> scanTable : sqlCacheContext.getUsedTables().entrySet()) {
            TableVersion tableVersion = scanTable.getValue();
            if (tableVersion.type != TableType.OLAP && tableVersion.type != TableType.MATERIALIZED_VIEW) {
                return true;
            }
            TableIf tableIf = findTableIf(env, scanTable.getKey());
            if (!(tableIf instanceof OlapTable) || tableVersion.id != tableIf.getId()) {
                return true;
            }

            OlapTable olapTable = (OlapTable) tableIf;
            long currentTableVersion = 0L;
            try {
                currentTableVersion = olapTable.getVisibleVersion();
            } catch (RpcException e) {
                LOG.warn("table {}, in cloud getVisibleVersion exception", olapTable.getName(), e);
                return true;
            }
            long cacheTableVersion = tableVersion.version;
            // some partitions have been dropped, or delete or updated or replaced, or insert rows into new partition?
            if (currentTableVersion != cacheTableVersion) {
                return true;
            }
        }

        // check partition version
        for (ScanTable scanTable : sqlCacheContext.getScanTables()) {
            FullTableName fullTableName = scanTable.fullTableName;
            TableIf tableIf = findTableIf(env, fullTableName);
            if (!(tableIf instanceof OlapTable)) {
                return true;
            }
            OlapTable olapTable = (OlapTable) tableIf;
            Collection<Long> partitionIds = scanTable.getScanPartitions();
            try {
                olapTable.getVersionInBatchForCloudMode(partitionIds);
            } catch (RpcException e) {
                LOG.warn("failed to get version in batch for table {}", fullTableName, e);
                return true;
            }

            for (Long scanPartitionId : scanTable.getScanPartitions()) {
                Partition partition = olapTable.getPartition(scanPartitionId);
                // partition == null: is this partition truncated?
                if (partition == null) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean viewsChanged(Env env, SqlCacheContext sqlCacheContext) {
        for (Entry<FullTableName, String> cacheView : sqlCacheContext.getUsedViews().entrySet()) {
            TableIf currentView = findTableIf(env, cacheView.getKey());
            if (currentView == null) {
                return true;
            }

            String cacheValueDdlSql = cacheView.getValue();
            if (currentView instanceof View) {
                if (!((View) currentView).getInlineViewDef().equals(cacheValueDdlSql)) {
                    return true;
                }
            } else {
                return true;
            }
        }
        return false;
    }

    private boolean rowPoliciesChanged(UserIdentity currentUserIdentity, Env env, SqlCacheContext sqlCacheContext) {
        for (Entry<FullTableName, List<RowFilterPolicy>> kv : sqlCacheContext.getRowPolicies().entrySet()) {
            FullTableName qualifiedTable = kv.getKey();
            List<? extends RowFilterPolicy> cachedPolicies = kv.getValue();

            List<? extends RowFilterPolicy> rowPolicies = env.getAccessManager().evalRowFilterPolicies(
                    currentUserIdentity, qualifiedTable.catalog, qualifiedTable.db, qualifiedTable.table);
            if (!CollectionUtils.isEqualCollection(cachedPolicies, rowPolicies)) {
                return true;
            }
        }
        return false;
    }

    private boolean dataMaskPoliciesChanged(
            UserIdentity currentUserIdentity, Env env, SqlCacheContext sqlCacheContext) {
        for (Entry<FullColumnName, Optional<DataMaskPolicy>> kv : sqlCacheContext.getDataMaskPolicies().entrySet()) {
            FullColumnName qualifiedColumn = kv.getKey();
            Optional<DataMaskPolicy> cachedPolicy = kv.getValue();

            Optional<DataMaskPolicy> dataMaskPolicy = env.getAccessManager()
                    .evalDataMaskPolicy(currentUserIdentity, qualifiedColumn.catalog,
                            qualifiedColumn.db, qualifiedColumn.table, qualifiedColumn.column);
            if (!Objects.equals(cachedPolicy, dataMaskPolicy)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Execute table locking operations in ascending order of table IDs.
     *
     * @return true if obtain all tables lock.
     */
    private boolean tryLockTables(ConnectContext connectContext, Env env, SqlCacheContext sqlCacheContext) {
        StatementContext currentStatementContext = connectContext.getStatementContext();
        for (FullTableName fullTableName : sqlCacheContext.getUsedTables().keySet()) {
            TableIf tableIf = findTableIf(env, fullTableName);
            if (tableIf == null) {
                return false;
            }
            currentStatementContext.getTables().put(fullTableName.toList(), tableIf);
        }
        for (FullTableName fullTableName : sqlCacheContext.getUsedViews().keySet()) {
            TableIf tableIf = findTableIf(env, fullTableName);
            if (tableIf == null) {
                return false;
            }
            currentStatementContext.getTables().put(fullTableName.toList(), tableIf);
        }
        currentStatementContext.lock();
        return true;
    }

    private boolean privilegeChanged(ConnectContext connectContext, Env env, SqlCacheContext sqlCacheContext) {
        for (Entry<FullTableName, Set<String>> kv : sqlCacheContext.getCheckPrivilegeTablesOrViews().entrySet()) {
            Set<String> usedColumns = kv.getValue();
            TableIf tableIf = findTableIf(env, kv.getKey());
            if (tableIf == null) {
                return true;
            }
            try {
                UserAuthentication.checkPermission(tableIf, connectContext, usedColumns);
            } catch (Throwable t) {
                return true;
            }
        }
        return false;
    }

    private List<Variable> resolveUserVariables(SqlCacheContext sqlCacheContext) {
        List<Variable> cachedUsedVariables = sqlCacheContext.getUsedVariables();
        List<Variable> currentVariables = Lists.newArrayListWithCapacity(cachedUsedVariables.size());
        for (Variable cachedVariable : cachedUsedVariables) {
            Variable currentVariable = ExpressionAnalyzer.resolveUnboundVariable(
                    new UnboundVariable(cachedVariable.getName(), cachedVariable.getType()));
            currentVariables.add(currentVariable);
        }
        return currentVariables;
    }

    private boolean usedVariablesChanged(List<Variable> currentVariables, SqlCacheContext sqlCacheContext) {
        List<Variable> cachedUsedVariables = sqlCacheContext.getUsedVariables();
        for (int i = 0; i < cachedUsedVariables.size(); i++) {
            Variable currentVariable = currentVariables.get(i);
            Variable cachedVariable = cachedUsedVariables.get(i);
            if (!Objects.equals(currentVariable, cachedVariable)
                    || cachedVariable.getRealExpression().anyMatch(
                        expr -> !((ExpressionTrait) expr).isDeterministic())) {
                return true;
            }
        }
        return false;
    }

    private boolean nondeterministicFunctionChanged(
            Plan plan, ConnectContext connectContext, SqlCacheContext sqlCacheContext) {
        if (sqlCacheContext.containsCannotProcessExpression()) {
            return true;
        }

        List<Pair<Expression, Expression>> nondeterministicFunctions
                = sqlCacheContext.getFoldFullNondeterministicPairs();
        if (nondeterministicFunctions.isEmpty()) {
            return false;
        }

        CascadesContext tempCascadeContext = CascadesContext.initContext(
                connectContext.getStatementContext(), plan, PhysicalProperties.ANY);
        ExpressionRewriteContext rewriteContext = new ExpressionRewriteContext(tempCascadeContext);
        for (Pair<Expression, Expression> foldPair : nondeterministicFunctions) {
            Expression nondeterministic = foldPair.first;
            Expression deterministic = foldPair.second;
            Expression fold = nondeterministic.accept(FoldConstantRuleOnFE.VISITOR_INSTANCE, rewriteContext);
            if (!Objects.equals(deterministic, fold)) {
                return true;
            }
        }
        return false;
    }

    private Optional<LogicalSqlCache> invalidateCache(String key) {
        sqlCaches.invalidate(key);
        return Optional.empty();
    }

    private TableIf findTableIf(Env env, FullTableName fullTableName) {
        CatalogIf<DatabaseIf<TableIf>> catalog = env.getCatalogMgr().getCatalog(fullTableName.catalog);
        if (catalog == null) {
            return null;
        }
        Optional<DatabaseIf<TableIf>> db = catalog.getDb(fullTableName.db);
        if (!db.isPresent()) {
            return null;
        }
        return db.get().getTable(fullTableName.table).orElse(null);
    }

    // NOTE: used in Config.sql_cache_manage_num.callbackClassString and
    //       Config.cache_last_version_interval_second.callbackClassString,
    //       don't remove it!
    public static class UpdateConfig extends DefaultConfHandler {
        @Override
        public void handle(Field field, String confVal) throws Exception {
            super.handle(field, confVal);
            NereidsSqlCacheManager.updateConfig();
        }
    }
}
