/*
 * Copyright © 2013-2016 The Nxt Core Developers.
 * Copyright © 2016-2017 Jelurida IP B.V.
 *
 * See the LICENSE.txt file at the top-level directory of this distribution
 * for licensing information.
 *
 * Unless otherwise agreed in a custom licensing agreement with Jelurida B.V.,
 * no part of the Nxt software, including this file, may be copied, modified,
 * propagated, or distributed except according to the terms contained in the
 * LICENSE.txt file.
 *
 * Removal or modification of this copyright notice is prohibited.
 *
 */

/*
 * Copyright © 2018 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.app;

import static org.slf4j.LoggerFactory.getLogger;

import com.apollocurrency.aplwallet.apl.core.db.fulltext.FullTextSearchService;
import com.apollocurrency.aplwallet.apl.core.shard.ShardManagement;
import com.apollocurrency.aplwallet.apl.util.injectable.DbProperties;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;

import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import com.apollocurrency.aplwallet.apl.core.db.DataSourceWrapper;
import com.apollocurrency.aplwallet.apl.core.db.TransactionalDataSource;
import com.apollocurrency.aplwallet.apl.util.injectable.PropertiesHolder;

/**
 * Class is used for high level database and shard management.
 * It keeps track on main database's data source and internal connections as well as secondary shards.
 */
@Singleton
public class DatabaseManager implements ShardManagement {
    private static final Logger log = getLogger(DatabaseManager.class);

    private DbProperties baseDbProperties; // main database properties
    private PropertiesHolder propertiesHolder;
    private static TransactionalDataSource currentTransactionalDataSource; // main/shard database
    private Map<Long, TransactionalDataSource> connectedShardDataSourceMap = new ConcurrentHashMap<>(3); // secondary shards
    private Jdbi jdbi;

    /**
     * Create, initialize and return main database source.
     * @return main data source
     */
    public TransactionalDataSource getDataSource() {
        if (currentTransactionalDataSource == null || currentTransactionalDataSource.isShutdown()) {
            currentTransactionalDataSource = new TransactionalDataSource(baseDbProperties, propertiesHolder);
            jdbi = currentTransactionalDataSource.init(new AplDbVersion());
        }
        return currentTransactionalDataSource;
    }

    /**
     * Init current database and shards using dbProperties and optional fulltext search service
     * @param dbProperties config for database init
     * @param fullTextSearchService service which provide full
     */
    public DatabaseManager(DbProperties dbProperties, PropertiesHolder propertiesHolder,  FullTextSearchService fullTextSearchService) {
        this(dbProperties, propertiesHolder);
    }

    /**
     * Create main db instance with db properties, all other properties injected by CDI
     * @param dbProperties database only properties from CDI
     * @param propertiesHolderParam the rest global properties in holder from CDI
     */
    @Inject
    public DatabaseManager(DbProperties dbProperties, PropertiesHolder propertiesHolderParam) {
        baseDbProperties = Objects.requireNonNull(dbProperties, "Db Properties cannot be null");
        propertiesHolder = propertiesHolderParam;
        // init internal data source stuff only one time till next shutdown() will be called
        if (currentTransactionalDataSource == null || currentTransactionalDataSource.isShutdown()) {
            currentTransactionalDataSource = new TransactionalDataSource(baseDbProperties, propertiesHolder);
            jdbi = currentTransactionalDataSource.init(new AplDbVersion());
        }
//        openAllShards();
    }

    private void openAllShards() {
        List<Long> shardList = findAllShards(currentTransactionalDataSource);
        log.debug("Found [{}] shards...", shardList.size());
        for (Long shardId : shardList) {
            String shardName = ShardNameHelper.getShardNameByShardId(shardId); // shard's file name formatted from Id
            DbProperties shardDbProperties = null;
            try {
                // create copy instance, change file name, nullify dbUrl intentionally!
                shardDbProperties = baseDbProperties.deepCopy().dbFileName(shardName).dbUrl(null);
            } catch (CloneNotSupportedException e) {
                log.error("Db props clone error", e);
            }
            TransactionalDataSource shardDb = new TransactionalDataSource(shardDbProperties, propertiesHolder);
            shardDb.init(new AplDbVersion());
            connectedShardDataSourceMap.put(shardId, shardDb);
            log.debug("Prepared '{}' shard...", shardName);
        }
    }

    @Produces
    public Jdbi getJdbi() {
        return jdbi;
    }

    @Override
    public List<Long> findAllShards(TransactionalDataSource transactionalDataSource) {
        Objects.requireNonNull(transactionalDataSource, "DataSource cannot be null");
        String shardSelect = "SELECT shard_id from shard";
        List<Long> result = new ArrayList<>(3);
        try (Connection con = transactionalDataSource.getConnection();
             PreparedStatement pstmt = con.prepareStatement(shardSelect)) {
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    result.add(rs.getLong("shard_id"));
                }
            }
        } catch (SQLException e) {
            log.error("Error retrieve shards...", e);
        }
        return result;
    }

    /**
     * Method gives ability to create new 'shard database', open existing shard and add it into shard list.
     * @param shardId shard name to be added
     * @return shard database connection pool instance
     */
    @Override
    public TransactionalDataSource createAndAddShard(Long shardId) {
        Objects.requireNonNull(shardId, "shardId is NULL");
        String shardName = ShardNameHelper.getShardNameByShardId(shardId); // convert shard Id into shard name
        log.debug("Create new SHARD '{}'", shardName);
        DbProperties shardDbProperties = null;
        try {
            shardDbProperties = baseDbProperties.deepCopy().dbFileName(shardName).dbUrl(null); // nullify dbUrl intentionally!;
        } catch (CloneNotSupportedException e) {
            log.error("DbProperties cloning error", e);
        }
        TransactionalDataSource shardDb = new TransactionalDataSource(shardDbProperties, propertiesHolder);
        shardDb.init(new AplDbVersion());
        connectedShardDataSourceMap.put(shardId, shardDb);
        log.debug("new SHARD '{}' is CREATED", shardName);
        return shardDb;
    }

    @Override
    public TransactionalDataSource createAndAddTemporaryDb(String temporaryDatabaseName) {
        Objects.requireNonNull(temporaryDatabaseName, "temporary Database Name is NULL");
        log.debug("Create new SHARD '{}'", temporaryDatabaseName);
        DbProperties shardDbProperties = null;
        try {
            shardDbProperties = baseDbProperties.deepCopy().dbFileName(temporaryDatabaseName).dbUrl(null); // nullify dbUrl intentionally!;
        } catch (CloneNotSupportedException e) {
            log.error("DbProperties cloning error", e);
        }
        TransactionalDataSource temporaryDataSource = new TransactionalDataSource(shardDbProperties, propertiesHolder);
        temporaryDataSource.init(new AplDbVersion());
        log.debug("new temporaryDataSource '{}' is CREATED", temporaryDatabaseName);
        return temporaryDataSource;
    }

    @Override
    public TransactionalDataSource getShardDataSourceById(Long shardId) {
        Objects.requireNonNull(shardId, "shardId is NULL");
        if (connectedShardDataSourceMap.containsKey(shardId)) {
            return connectedShardDataSourceMap.get(shardId);
        } else {
            return createAndAddShard(shardId);
        }
    }

    /**
     * Shutdown main db and secondary shards.
     * After that the db can be reinitialized/opened again
     */
    public void shutdown() {
        if (connectedShardDataSourceMap.size() > 0) {
            connectedShardDataSourceMap.values().stream().forEach(DataSourceWrapper::shutdown);
        }
        if (currentTransactionalDataSource != null) {
            currentTransactionalDataSource.shutdown();
            currentTransactionalDataSource = null;
            jdbi = null;
        }
    }

    /**
     * Be CAREFUL using this method. It's better to use it for explicit DataSources (like temporary)
     * @param dataSource not null data source to be closed
     */
    public void shutdown(TransactionalDataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource is NULL");
        dataSource.shutdown();
    }

    public DatabaseManager() {} // never use it directly

    /**
     * Optional method, needs revising for shards
     * @throws IOException
     */
    public static void tryToDeleteDb() throws IOException {
            currentTransactionalDataSource.shutdown();
            log.info("Removing current Db...");
            Path dbPath = AplCoreRuntime.getInstance().getDbDir();
            removeDb(dbPath);
            log.info("Db: " + dbPath.toAbsolutePath().toString() + " was successfully removed!");
    }

    /**
     * Optional method, needs revising for shards
     * @param dbPath path to db folder
     * @throws IOException
     */
    public static void removeDb(Path dbPath) throws IOException {
        Files.walkFileTree(dbPath, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public String toString() {
        final StringBuffer sb = new StringBuffer("DatabaseManager{");
        sb.append("baseDbProperties=").append(baseDbProperties);
        sb.append(", propertiesHolder=[{}]").append(propertiesHolder != null ? propertiesHolder : -1);
        sb.append(", currentTransactionalDataSource={}").append(currentTransactionalDataSource != null ? "initialized" : "NULL");
        sb.append(", connectedShardDataSourceMap=[{}]").append(connectedShardDataSourceMap != null ? connectedShardDataSourceMap.size() : -1);
        sb.append('}');
        return sb.toString();
    }
}
