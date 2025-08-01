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

package org.apache.doris.datasource.paimon;

import org.apache.doris.common.DdlException;
import org.apache.doris.common.security.authentication.AuthenticationConfig;
import org.apache.doris.common.security.authentication.HadoopAuthenticator;
import org.apache.doris.common.security.authentication.HadoopExecutionAuthenticator;
import org.apache.doris.datasource.CatalogProperty;
import org.apache.doris.datasource.ExternalCatalog;
import org.apache.doris.datasource.InitCatalogLog;
import org.apache.doris.datasource.NameMapping;
import org.apache.doris.datasource.SessionContext;
import org.apache.doris.datasource.property.PropertyConverter;
import org.apache.doris.datasource.property.constants.HMSProperties;
import org.apache.doris.datasource.property.constants.PaimonProperties;
import org.apache.doris.fs.remote.dfs.DFSFileSystem;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import org.apache.hadoop.conf.Configuration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.paimon.catalog.Catalog;
import org.apache.paimon.catalog.Catalog.TableNotExistException;
import org.apache.paimon.catalog.CatalogContext;
import org.apache.paimon.catalog.CatalogFactory;
import org.apache.paimon.catalog.Identifier;
import org.apache.paimon.options.Options;
import org.apache.paimon.partition.Partition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public abstract class PaimonExternalCatalog extends ExternalCatalog {
    private static final Logger LOG = LogManager.getLogger(PaimonExternalCatalog.class);
    public static final String PAIMON_CATALOG_TYPE = "paimon.catalog.type";
    public static final String PAIMON_FILESYSTEM = "filesystem";
    public static final String PAIMON_HMS = "hms";
    public static final String PAIMON_DLF = "dlf";
    protected String catalogType;
    protected Catalog catalog;
    protected AuthenticationConfig authConf;
    protected HadoopAuthenticator hadoopAuthenticator;

    private static final List<String> REQUIRED_PROPERTIES = ImmutableList.of(
            PaimonProperties.WAREHOUSE
    );

    public PaimonExternalCatalog(long catalogId, String name, String resource,
                                 Map<String, String> props, String comment) {
        super(catalogId, name, InitCatalogLog.Type.PAIMON, comment);
        props = PropertyConverter.convertToMetaProperties(props);
        catalogProperty = new CatalogProperty(resource, props);
    }

    @Override
    protected void initLocalObjectsImpl() {
        Configuration conf = DFSFileSystem.getHdfsConf(ifNotSetFallbackToSimpleAuth());
        for (Map.Entry<String, String> propEntry : this.catalogProperty.getHadoopProperties().entrySet()) {
            conf.set(propEntry.getKey(), propEntry.getValue());
        }
        authConf = AuthenticationConfig.getKerberosConfig(conf);
        hadoopAuthenticator = HadoopAuthenticator.getHadoopAuthenticator(authConf);
        initPreExecutionAuthenticator();
    }

    @Override
    protected synchronized void initPreExecutionAuthenticator() {
        if (executionAuthenticator == null) {
            executionAuthenticator = new HadoopExecutionAuthenticator(hadoopAuthenticator);
        }
    }

    public String getCatalogType() {
        makeSureInitialized();
        return catalogType;
    }

    protected List<String> listDatabaseNames() {
        try {
            return hadoopAuthenticator.doAs(() -> new ArrayList<>(catalog.listDatabases()));
        } catch (IOException e) {
            throw new RuntimeException("Failed to list databases names, catalog name: " + getName(), e);
        }
    }

    @Override
    public boolean tableExist(SessionContext ctx, String dbName, String tblName) {
        makeSureInitialized();
        try {
            return hadoopAuthenticator.doAs(() -> {
                try {
                    catalog.getTable(Identifier.create(dbName, tblName));
                    return true;
                } catch (TableNotExistException e) {
                    return false;
                }
            });

        } catch (IOException e) {
            throw new RuntimeException("Failed to check table existence, catalog name: " + getName(), e);
        }
    }

    @Override
    public List<String> listTableNames(SessionContext ctx, String dbName) {
        makeSureInitialized();
        try {
            return hadoopAuthenticator.doAs(() -> {
                List<String> tableNames = null;
                try {
                    tableNames = catalog.listTables(dbName);
                } catch (Catalog.DatabaseNotExistException e) {
                    LOG.warn("DatabaseNotExistException", e);
                }
                return tableNames;
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to list table names, catalog name: " + getName(), e);
        }
    }

    public org.apache.paimon.table.Table getPaimonTable(NameMapping nameMapping) {
        makeSureInitialized();
        try {
            return hadoopAuthenticator.doAs(() -> catalog.getTable(
                    Identifier.create(nameMapping.getRemoteDbName(), nameMapping.getRemoteTblName())));
        } catch (Exception e) {
            throw new RuntimeException("Failed to get Paimon table:" + getName() + "."
                    + nameMapping.getLocalDbName() + "." + nameMapping.getLocalTblName() + ", because "
                    + e.getMessage(), e);
        }
    }

    public List<Partition> getPaimonPartitions(NameMapping nameMapping) {
        makeSureInitialized();
        try {
            return hadoopAuthenticator.doAs(() -> {
                List<Partition> partitions = new ArrayList<>();
                try {
                    partitions = catalog.listPartitions(
                            Identifier.create(nameMapping.getRemoteDbName(), nameMapping.getRemoteTblName()));
                } catch (Catalog.TableNotExistException e) {
                    LOG.warn("TableNotExistException", e);
                }
                return partitions;
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to get Paimon table partitions:" + getName() + "."
                    + nameMapping.getRemoteDbName() + "." + nameMapping.getRemoteTblName()
                    + ", because " + e.getMessage(), e);
        }
    }

    public org.apache.paimon.table.Table getPaimonSystemTable(NameMapping nameMapping, String queryType) {
        return getPaimonSystemTable(nameMapping, null, queryType);
    }

    public org.apache.paimon.table.Table getPaimonSystemTable(NameMapping nameMapping, String branch,
            String queryType) {
        makeSureInitialized();
        try {
            return hadoopAuthenticator.doAs(() -> catalog.getTable(new Identifier(nameMapping.getRemoteDbName(),
                    nameMapping.getRemoteTblName(), branch, queryType)));
        } catch (Exception e) {
            throw new RuntimeException("Failed to get Paimon system table:" + getName() + "."
                    + nameMapping.getRemoteDbName() + "." + nameMapping.getRemoteTblName() + "$" + queryType
                    + ", because " + e.getMessage(), e);
        }
    }

    protected String getPaimonCatalogType(String catalogType) {
        if (PAIMON_HMS.equalsIgnoreCase(catalogType)) {
            return PaimonProperties.PAIMON_HMS_CATALOG;
        } else {
            return PaimonProperties.PAIMON_FILESYSTEM_CATALOG;
        }
    }

    protected Catalog createCatalog() {
        try {
            return hadoopAuthenticator.doAs(() -> {
                Options options = new Options();
                Map<String, String> paimonOptionsMap = getPaimonOptionsMap();
                for (Map.Entry<String, String> kv : paimonOptionsMap.entrySet()) {
                    options.set(kv.getKey(), kv.getValue());
                }
                CatalogContext context = CatalogContext.create(options, getConfiguration());
                return createCatalogImpl(context);
            });
        } catch (IOException e) {
            throw new RuntimeException("Failed to create catalog, catalog name: " + getName(), e);
        }
    }

    protected Catalog createCatalogImpl(CatalogContext context) {
        return CatalogFactory.createCatalog(context);
    }

    public Map<String, String> getPaimonOptionsMap() {
        Map<String, String> properties = catalogProperty.getHadoopProperties();
        Map<String, String> options = Maps.newHashMap();
        options.put(PaimonProperties.WAREHOUSE, properties.get(PaimonProperties.WAREHOUSE));
        setPaimonCatalogOptions(properties, options);
        setPaimonExtraOptions(properties, options);
        return options;
    }

    protected abstract void setPaimonCatalogOptions(Map<String, String> properties, Map<String, String> options);

    protected void setPaimonExtraOptions(Map<String, String> properties, Map<String, String> options) {
        for (Map.Entry<String, String> kv : properties.entrySet()) {
            if (kv.getKey().startsWith(PaimonProperties.PAIMON_PREFIX)) {
                options.put(kv.getKey().substring(PaimonProperties.PAIMON_PREFIX.length()), kv.getValue());
            }
        }

        // hive version.
        // This property is used for both FE and BE, so it has no "paimon." prefix.
        // We need to handle it separately.
        if (properties.containsKey(HMSProperties.HIVE_VERSION)) {
            options.put(HMSProperties.HIVE_VERSION, properties.get(HMSProperties.HIVE_VERSION));
        }
    }

    @Override
    public void checkProperties() throws DdlException {
        super.checkProperties();
        for (String requiredProperty : REQUIRED_PROPERTIES) {
            if (!catalogProperty.getProperties().containsKey(requiredProperty)) {
                throw new DdlException("Required property '" + requiredProperty + "' is missing");
            }
        }
    }
}
