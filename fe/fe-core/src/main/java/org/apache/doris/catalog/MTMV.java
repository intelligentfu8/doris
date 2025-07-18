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

package org.apache.doris.catalog;

import org.apache.doris.analysis.PartitionKeyDesc;
import org.apache.doris.analysis.TableName;
import org.apache.doris.catalog.OlapTableFactory.MTMVParams;
import org.apache.doris.common.AnalysisException;
import org.apache.doris.common.Config;
import org.apache.doris.common.Pair;
import org.apache.doris.common.util.PropertyAnalyzer;
import org.apache.doris.datasource.CatalogMgr;
import org.apache.doris.job.common.TaskStatus;
import org.apache.doris.job.extensions.mtmv.MTMVTask;
import org.apache.doris.mtmv.EnvInfo;
import org.apache.doris.mtmv.MTMVCache;
import org.apache.doris.mtmv.MTMVJobInfo;
import org.apache.doris.mtmv.MTMVJobManager;
import org.apache.doris.mtmv.MTMVPartitionInfo;
import org.apache.doris.mtmv.MTMVPartitionInfo.MTMVPartitionType;
import org.apache.doris.mtmv.MTMVPartitionUtil;
import org.apache.doris.mtmv.MTMVPlanUtil;
import org.apache.doris.mtmv.MTMVRefreshEnum.MTMVRefreshState;
import org.apache.doris.mtmv.MTMVRefreshEnum.MTMVState;
import org.apache.doris.mtmv.MTMVRefreshInfo;
import org.apache.doris.mtmv.MTMVRefreshPartitionSnapshot;
import org.apache.doris.mtmv.MTMVRefreshSnapshot;
import org.apache.doris.mtmv.MTMVRelation;
import org.apache.doris.mtmv.MTMVStatus;
import org.apache.doris.qe.ConnectContext;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.gson.annotations.SerializedName;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.ReentrantReadWriteLock;


public class MTMV extends OlapTable {
    private static final Logger LOG = LogManager.getLogger(MTMV.class);
    private ReentrantReadWriteLock mvRwLock;

    @SerializedName("ri")
    private MTMVRefreshInfo refreshInfo;
    @SerializedName("qs")
    private String querySql;
    @SerializedName("s")
    private MTMVStatus status;
    @Deprecated
    @SerializedName("ei")
    private EnvInfo envInfo;
    @SerializedName("ji")
    private MTMVJobInfo jobInfo;
    @SerializedName("mp")
    private Map<String, String> mvProperties;
    @SerializedName("r")
    private MTMVRelation relation;
    @SerializedName("mpi")
    private MTMVPartitionInfo mvPartitionInfo;
    @SerializedName("rs")
    private MTMVRefreshSnapshot refreshSnapshot;
    // Should update after every fresh, not persist
    private MTMVCache cache;

    // For deserialization
    public MTMV() {
        type = TableType.MATERIALIZED_VIEW;
        mvRwLock = new ReentrantReadWriteLock(true);
    }

    MTMV(MTMVParams params) {
        super(
                params.tableId,
                params.tableName,
                params.schema,
                params.keysType,
                params.partitionInfo,
                params.distributionInfo
        );
        this.type = TableType.MATERIALIZED_VIEW;
        this.querySql = params.querySql;
        this.refreshInfo = params.refreshInfo;
        this.status = new MTMVStatus();
        this.jobInfo = new MTMVJobInfo(MTMVJobManager.MTMV_JOB_PREFIX + params.tableId);
        this.mvProperties = params.mvProperties;
        this.mvPartitionInfo = params.mvPartitionInfo;
        this.relation = params.relation;
        this.refreshSnapshot = new MTMVRefreshSnapshot();
        this.envInfo = new EnvInfo(-1L, -1L);
        mvRwLock = new ReentrantReadWriteLock(true);
    }

    @Override
    public boolean needReadLockWhenPlan() {
        return true;
    }

    public MTMVRefreshInfo getRefreshInfo() {
        readMvLock();
        try {
            return refreshInfo;
        } finally {
            readMvUnlock();
        }
    }

    public String getQuerySql() {
        return querySql;
    }

    public MTMVStatus getStatus() {
        readMvLock();
        try {
            return status;
        } finally {
            readMvUnlock();
        }
    }

    public EnvInfo getEnvInfo() {
        return envInfo;
    }

    public MTMVJobInfo getJobInfo() {
        readMvLock();
        try {
            return jobInfo;
        } finally {
            readMvUnlock();
        }
    }

    public MTMVRelation getRelation() {
        readMvLock();
        try {
            return relation;
        } finally {
            readMvUnlock();
        }
    }

    public void setCache(MTMVCache cache) {
        this.cache = cache;
    }

    public MTMVRefreshInfo alterRefreshInfo(MTMVRefreshInfo newRefreshInfo) {
        writeMvLock();
        try {
            return refreshInfo.updateNotNull(newRefreshInfo);
        } finally {
            writeMvUnlock();
        }
    }

    public MTMVStatus alterStatus(MTMVStatus newStatus) {
        writeMvLock();
        try {
            // only can update state, refresh state will be change by add task
            return this.status.updateStateAndDetail(newStatus);
        } finally {
            writeMvUnlock();
        }
    }

    public void addTaskResult(MTMVTask task, MTMVRelation relation,
            Map<String, MTMVRefreshPartitionSnapshot> partitionSnapshots, boolean isReplay) {
        MTMVCache mtmvCache = null;
        boolean needUpdateCache = false;
        if (task.getStatus() == TaskStatus.SUCCESS && !Env.isCheckpointThread()
                && !Config.enable_check_compatibility_mode) {
            needUpdateCache = true;
            try {
                // The replay thread may not have initialized the catalog yet to avoid getting stuck due
                // to connection issues such as S3, so it is directly set to null
                if (!isReplay) {
                    // shouldn't do this while holding mvWriteLock
                    mtmvCache = MTMVCache.from(this.getQuerySql(), MTMVPlanUtil.createMTMVContext(this), true,
                            true, null);
                }
            } catch (Throwable e) {
                mtmvCache = null;
                LOG.warn("generate cache failed", e);
            }
        }
        writeMvLock();
        try {
            if (task.getStatus() == TaskStatus.SUCCESS) {
                this.status.setState(MTMVState.NORMAL);
                this.status.setSchemaChangeDetail(null);
                this.status.setRefreshState(MTMVRefreshState.SUCCESS);
                this.relation = relation;
                if (needUpdateCache) {
                    this.cache = mtmvCache;
                }
            } else {
                this.status.setRefreshState(MTMVRefreshState.FAIL);
            }
            this.jobInfo.addHistoryTask(task);
            this.refreshSnapshot.updateSnapshots(partitionSnapshots, getPartitionNames());
            Env.getCurrentEnv().getMtmvService()
                    .refreshComplete(this, relation, task);
        } finally {
            writeMvUnlock();
        }
    }

    public Map<String, String> alterMvProperties(Map<String, String> mvProperties) {
        writeMvLock();
        try {
            this.mvProperties.putAll(mvProperties);
            return this.mvProperties;
        } finally {
            writeMvUnlock();
        }
    }

    public long getGracePeriod() {
        readMvLock();
        try {
            if (!StringUtils.isEmpty(mvProperties.get(PropertyAnalyzer.PROPERTIES_GRACE_PERIOD))) {
                return Long.parseLong(mvProperties.get(PropertyAnalyzer.PROPERTIES_GRACE_PERIOD)) * 1000;
            } else {
                return 0L;
            }
        } finally {
            readMvUnlock();
        }
    }

    public Optional<String> getWorkloadGroup() {
        readMvLock();
        try {
            if (mvProperties.containsKey(PropertyAnalyzer.PROPERTIES_WORKLOAD_GROUP) && !StringUtils
                    .isEmpty(mvProperties.get(PropertyAnalyzer.PROPERTIES_WORKLOAD_GROUP))) {
                return Optional.of(mvProperties.get(PropertyAnalyzer.PROPERTIES_WORKLOAD_GROUP));
            }
            return Optional.empty();
        } finally {
            readMvUnlock();
        }
    }

    public boolean isUseForRewrite() {
        readMvLock();
        try {
            if (!StringUtils.isEmpty(mvProperties.get(PropertyAnalyzer.PROPERTIES_USE_FOR_REWRITE))) {
                return Boolean.valueOf(mvProperties.get(PropertyAnalyzer.PROPERTIES_USE_FOR_REWRITE));
            }
            // default is true
            return true;
        } finally {
            readMvUnlock();
        }
    }

    public int getRefreshPartitionNum() {
        readMvLock();
        try {
            if (!StringUtils.isEmpty(mvProperties.get(PropertyAnalyzer.PROPERTIES_REFRESH_PARTITION_NUM))) {
                int value = Integer.parseInt(mvProperties.get(PropertyAnalyzer.PROPERTIES_REFRESH_PARTITION_NUM));
                return value < 1 ? MTMVTask.DEFAULT_REFRESH_PARTITION_NUM : value;
            } else {
                return MTMVTask.DEFAULT_REFRESH_PARTITION_NUM;
            }
        } finally {
            readMvUnlock();
        }
    }

    public Set<TableName> getExcludedTriggerTables() {
        Set<TableName> res = Sets.newHashSet();
        readMvLock();
        try {
            if (StringUtils.isEmpty(mvProperties.get(PropertyAnalyzer.PROPERTIES_EXCLUDED_TRIGGER_TABLES))) {
                return res;
            }
            String[] split = mvProperties.get(PropertyAnalyzer.PROPERTIES_EXCLUDED_TRIGGER_TABLES).split(",");
            for (String alias : split) {
                res.add(new TableName(alias));
            }
            return res;
        } finally {
            readMvUnlock();
        }
    }

    /**
     * Called when in query, Should use one connection context in query
     */
    public MTMVCache getOrGenerateCache(ConnectContext connectionContext) throws
            org.apache.doris.nereids.exceptions.AnalysisException {
        readMvLock();
        try {
            if (cache != null) {
                return cache;
            }
        } finally {
            readMvUnlock();
        }
        // Concurrent situations may result in duplicate cache generation,
        // but we tolerate this in order to prevent nested use of readLock and write MvLock for the table
        MTMVCache mtmvCache = MTMVCache.from(this.getQuerySql(), MTMVPlanUtil.createMTMVContext(this), true,
                false, connectionContext);
        writeMvLock();
        try {
            this.cache = mtmvCache;
            return cache;
        } finally {
            writeMvUnlock();
        }
    }

    public Map<String, String> getMvProperties() {
        readMvLock();
        try {
            return mvProperties;
        } finally {
            readMvUnlock();
        }
    }

    public MTMVPartitionInfo getMvPartitionInfo() {
        return mvPartitionInfo;
    }

    public MTMVRefreshSnapshot getRefreshSnapshot() {
        return refreshSnapshot;
    }

    /**
     * generateMvPartitionDescs
     *
     * @return mvPartitionName ==> mvPartitionKeyDesc
     */
    public Map<String, PartitionKeyDesc> generateMvPartitionDescs() {
        Map<String, PartitionItem> mtmvItems = getAndCopyPartitionItems();
        Map<String, PartitionKeyDesc> result = Maps.newHashMap();
        for (Entry<String, PartitionItem> entry : mtmvItems.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toPartitionKeyDesc());
        }
        return result;
    }

    /**
     * Calculate the partition and associated partition mapping relationship of the MTMV
     * It is the result of real-time comparison calculation, so there may be some costs,
     * so it should be called with caution.
     * The reason for not directly calling `calculatePartitionMappings` and
     * generating a reverse index is to directly generate two maps here,
     * without the need to traverse them again
     *
     * @return mvPartitionName ==> relationPartitionNames and relationPartitionName ==> mvPartitionName
     * @throws AnalysisException
     */
    public Pair<Map<String, Set<String>>, Map<String, String>> calculateDoublyPartitionMappings()
            throws AnalysisException {
        if (mvPartitionInfo.getPartitionType() == MTMVPartitionType.SELF_MANAGE) {
            return Pair.of(Maps.newHashMap(), Maps.newHashMap());
        }
        long start = System.currentTimeMillis();
        Map<String, Set<String>> mvToBase = Maps.newHashMap();
        Map<String, String> baseToMv = Maps.newHashMap();
        Map<PartitionKeyDesc, Set<String>> relatedPartitionDescs = MTMVPartitionUtil
                .generateRelatedPartitionDescs(mvPartitionInfo, mvProperties);
        Map<String, PartitionItem> mvPartitionItems = getAndCopyPartitionItems();
        for (Entry<String, PartitionItem> entry : mvPartitionItems.entrySet()) {
            Set<String> basePartitionNames = relatedPartitionDescs.getOrDefault(entry.getValue().toPartitionKeyDesc(),
                    Sets.newHashSet());
            String mvPartitionName = entry.getKey();
            mvToBase.put(mvPartitionName, basePartitionNames);
            for (String basePartitionName : basePartitionNames) {
                baseToMv.put(basePartitionName, mvPartitionName);
            }
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("calculateDoublyPartitionMappings use [{}] mills, mvName is [{}]",
                    System.currentTimeMillis() - start, name);
        }
        return Pair.of(mvToBase, baseToMv);
    }

    /**
     * Calculate the partition and associated partition mapping relationship of the MTMV
     * It is the result of real-time comparison calculation, so there may be some costs,
     * so it should be called with caution
     *
     * @return mvPartitionName ==> relationPartitionNames
     * @throws AnalysisException
     */
    public Map<String, Set<String>> calculatePartitionMappings() throws AnalysisException {
        if (mvPartitionInfo.getPartitionType() == MTMVPartitionType.SELF_MANAGE) {
            return Maps.newHashMap();
        }
        long start = System.currentTimeMillis();
        Map<String, Set<String>> res = Maps.newHashMap();
        Map<PartitionKeyDesc, Set<String>> relatedPartitionDescs = MTMVPartitionUtil
                .generateRelatedPartitionDescs(mvPartitionInfo, mvProperties);
        Map<String, PartitionItem> mvPartitionItems = getAndCopyPartitionItems();
        for (Entry<String, PartitionItem> entry : mvPartitionItems.entrySet()) {
            res.put(entry.getKey(),
                    relatedPartitionDescs.getOrDefault(entry.getValue().toPartitionKeyDesc(), Sets.newHashSet()));
        }
        if (LOG.isDebugEnabled()) {
            LOG.debug("calculatePartitionMappings use [{}] mills, mvName is [{}]",
                    System.currentTimeMillis() - start, name);
        }
        return res;
    }

    public ConcurrentLinkedQueue<MTMVTask> getHistoryTasks() {
        return jobInfo.getHistoryTasks();
    }

    // for test
    public void setRefreshInfo(MTMVRefreshInfo refreshInfo) {
        this.refreshInfo = refreshInfo;
    }

    // for test
    public void setQuerySql(String querySql) {
        this.querySql = querySql;
    }

    // for test
    public void setStatus(MTMVStatus status) {
        this.status = status;
    }

    // for test
    public void setJobInfo(MTMVJobInfo jobInfo) {
        this.jobInfo = jobInfo;
    }

    // for test
    public void setMvProperties(Map<String, String> mvProperties) {
        this.mvProperties = mvProperties;
    }

    // for test
    public void setRelation(MTMVRelation relation) {
        this.relation = relation;
    }

    // for test
    public void setMvPartitionInfo(MTMVPartitionInfo mvPartitionInfo) {
        this.mvPartitionInfo = mvPartitionInfo;
    }

    // for test
    public void setRefreshSnapshot(MTMVRefreshSnapshot refreshSnapshot) {
        this.refreshSnapshot = refreshSnapshot;
    }

    public void readMvLock() {
        this.mvRwLock.readLock().lock();
    }

    public void readMvUnlock() {
        this.mvRwLock.readLock().unlock();
    }

    public void writeMvLock() {
        this.mvRwLock.writeLock().lock();
    }

    public void writeMvUnlock() {
        this.mvRwLock.writeLock().unlock();
    }

    // toString() is not easy to find where to call the method
    public String toInfoString() {
        final StringBuilder sb = new StringBuilder("MTMV{");
        sb.append("refreshInfo=").append(refreshInfo);
        sb.append(", querySql='").append(querySql).append('\'');
        sb.append(", status=").append(status);
        if (jobInfo != null) {
            sb.append(", jobInfo=").append(jobInfo.toInfoString());
        }
        sb.append(", mvProperties=").append(mvProperties);
        if (relation != null) {
            sb.append(", relation=").append(relation.toInfoString());
        }
        if (mvPartitionInfo != null) {
            sb.append(", mvPartitionInfo=").append(mvPartitionInfo.toInfoString());
        }
        sb.append(", refreshSnapshot=").append(refreshSnapshot);
        sb.append(", id=").append(id);
        sb.append(", name='").append(name).append('\'');
        sb.append(", qualifiedDbName='").append(qualifiedDbName).append('\'');
        sb.append(", comment='").append(comment).append('\'');
        sb.append('}');
        return sb.toString();
    }

    /**
     * Previously, ID was used to store the related table of materialized views,
     * but when the catalog is deleted, the ID will change, so name is used instead.
     * The logic here is to be compatible with older versions by converting ID to name
     */
    public void compatible(CatalogMgr catalogMgr) {
        if (mvPartitionInfo != null) {
            mvPartitionInfo.compatible(catalogMgr);
        }
        if (relation != null) {
            relation.compatible(catalogMgr);
        }
        if (refreshSnapshot != null) {
            refreshSnapshot.compatible(this);
        }
    }
}
