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
 * Copyright © 2018-2019 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.db.derived;

import com.apollocurrency.aplwallet.apl.core.db.DatabaseManager;
import com.apollocurrency.aplwallet.apl.core.db.DbKey;
import com.apollocurrency.aplwallet.apl.core.db.DerivedTablesRegistry;
import com.apollocurrency.aplwallet.apl.core.db.TransactionalDataSource;
import com.apollocurrency.aplwallet.apl.core.db.fulltext.FullTextConfig;
import com.apollocurrency.aplwallet.apl.util.StringValidator;

import javax.annotation.PostConstruct;
import javax.enterprise.inject.spi.CDI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public abstract class DerivedDbTable<T> implements DerivedTableInterface<T> {

    private FullTextConfig fullTextConfig;
    private DerivedTablesRegistry derivedDbTablesRegistry;

    public String getTableName() {
        return table;
    }

    protected final String table;
    protected DatabaseManager databaseManager;

    //TODO: fix injects and remove
    private void lookupCdi(){
        if(fullTextConfig==null){
            fullTextConfig =  CDI.current().select(FullTextConfig.class).get();
        }
        if(derivedDbTablesRegistry==null){
            derivedDbTablesRegistry = CDI.current().select(DerivedTablesRegistry.class).get();
        }
    }

    // We should find better place for table init
    protected DerivedDbTable(String table, boolean init) { // for CDI beans setUp 'false'
        StringValidator.requireNonBlank(table, "Table name");
        this.table = table;
        databaseManager = CDI.current().select(DatabaseManager.class).get();
        if (init) {
            init();
        }
    }

    protected DerivedDbTable(String table) {
        this(table, true);
    }

    @Override
    public void trim(int height) {}

    @Override
    public void trim(int height, boolean isSharding) {
        // default implementation for most of derived successor
        // 'Vote' is only one exception in that case
        this.trim(height);
    }

    @PostConstruct
    public void init() {
        lookupCdi();
        derivedDbTablesRegistry.registerDerivedTable(this);
        fullTextConfig.registerTable(table);
    }

    @Override
    public void rollback(int height) {
        TransactionalDataSource dataSource = databaseManager.getDataSource();
        if (!dataSource.isInTransaction()) {
            throw new IllegalStateException("Not in transaction");
        }
        try (Connection con = dataSource.getConnection();
             PreparedStatement pstmtDelete = con.prepareStatement("DELETE FROM " + table + " WHERE height > ?")) {
            pstmtDelete.setInt(1, height);
            pstmtDelete.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e.toString(), e);
        }
    }

    @Override
    public boolean delete(T t) {
        throw new UnsupportedOperationException("Delete is not supported");
    }

    @Override
    public void insert(T t) {
        throw new UnsupportedOperationException("Insert is not supported");
    }

    @Override
    public void createSearchIndex(Connection con) throws SQLException {

    }

    @Override
    public void truncate() {
        TransactionalDataSource dataSource = databaseManager.getDataSource();
        if (!dataSource.isInTransaction()) {
            throw new IllegalStateException("Not in transaction");
        }
        try (Connection con = dataSource.getConnection();
             Statement stmt = con.createStatement()) {
            stmt.executeUpdate("TRUNCATE TABLE " + table);
        } catch (SQLException e) {
            throw new RuntimeException(e.toString(), e);
        }
    }


    public  DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DerivedTableData<T> getAllByDbId(long from, int limit, long dbIdLimit) throws SQLException {
        TransactionalDataSource dataSource = databaseManager.getDataSource();
        try (Connection con = dataSource.getConnection();
             PreparedStatement pstmt = con.prepareStatement("select * from " + table + " where db_id >= ? and db_id < ? limit ?")) {
            pstmt.setLong(1, from);
            pstmt.setLong(2, dbIdLimit);
            pstmt.setLong(3, limit);
            List<T> values = new ArrayList<>();
            long dbId = -1;
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()){
                    values.add(load(con, rs, null));
                    dbId = rs.getLong("db_id");
                }
            }

            return new DerivedTableData<>(values, dbId);
        }
    }

    protected abstract T load(Connection con, ResultSet rs, DbKey dbKey) throws SQLException;

    /**
     * {@inheritDoc}
     */
    @Override
    public ResultSet getRangeByDbId(Connection con, PreparedStatement pstmt,
                                    MinMaxValue minMaxValue, int limit) throws SQLException {
        Objects.requireNonNull(con, "connnection is NULL");
        Objects.requireNonNull(pstmt, "prepared statement is NULL");
        Objects.requireNonNull(minMaxValue, "minMaxValue is NULL");
        try {
            pstmt.setLong(1, minMaxValue.getMin());
            pstmt.setLong(2, minMaxValue.getMax());
            pstmt.setLong(3, limit);
            return pstmt.executeQuery();
        } catch (SQLException e) {
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public MinMaxValue getMinMaxValue(int height) {
        return getMinMaxValue(height, "db_id");
    }

    @Override
    public void prune(int time) {
    }

    protected MinMaxValue getMinMaxValue(int height, String column) {
        Objects.requireNonNull(column, "column is NULL");
        TransactionalDataSource dataSource = databaseManager.getDataSource();
        try (Connection con = dataSource.getConnection();
             PreparedStatement pstmt = con.prepareStatement(String.format("SELECT IFNULL(min(%s), 0) as min_id, IFNULL(max(%s), 0) as max_id, IFNULL(count(*), 0) as count, max(height) as max_height from %s where HEIGHT <= ?", column, column, table))) {
            pstmt.setInt(1, height);
            MinMaxValue minMaxValue = getMinMaxValue(pstmt);
            minMaxValue.setColumn(column);
            return minMaxValue;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    protected MinMaxValue getMinMaxValue(PreparedStatement pstmt) throws SQLException {
        MinMaxValue result = null;
        try (ResultSet rs = pstmt.executeQuery()) {
            if (rs.next()) {
                long min = rs.getLong("min_id");
                long max = rs.getLong("max_id");
                long rowCount = rs.getLong("count");
                int height = rs.getInt("max_height");
                result = new MinMaxValue(
                        min,
                        max,
                        null,
                        rowCount,
                        height
                );
            }
        }
        return result;
    }

    @Override
    public boolean isScanSafe() {
        return true; // by default derived table can be safely rolled back to any height without block popOff
    }
    @Override
    public final String toString() {
        return getName();
    }

    @Override
    public String getName() {
        return table;
    }
}
