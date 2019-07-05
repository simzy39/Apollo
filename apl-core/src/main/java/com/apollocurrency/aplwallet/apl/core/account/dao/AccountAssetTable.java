/*
 * Copyright © 2018-2019 Apollo Foundation.
 */

package com.apollocurrency.aplwallet.apl.core.account.dao;

import com.apollocurrency.aplwallet.apl.core.account.model.AccountAsset;
import com.apollocurrency.aplwallet.apl.core.account.service.AccountService;
import com.apollocurrency.aplwallet.apl.core.db.DbClause;
import com.apollocurrency.aplwallet.apl.core.db.DbIterator;
import com.apollocurrency.aplwallet.apl.core.db.DbKey;
import com.apollocurrency.aplwallet.apl.core.db.LinkKeyFactory;
import com.apollocurrency.aplwallet.apl.core.db.derived.VersionedDeletableEntityDbTable;
import com.apollocurrency.aplwallet.apl.util.Constants;

import javax.inject.Singleton;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 *
 * @author al
 */
@Singleton
public class AccountAssetTable extends VersionedDeletableEntityDbTable<AccountAsset> {
    
    private static class AccountAssetDbKeyFactory extends LinkKeyFactory<AccountAsset> {

        public AccountAssetDbKeyFactory(String idColumnA, String idColumnB) {
            super(idColumnA, idColumnB);
        }

        @Override
        public DbKey newKey(AccountAsset accountAsset) {
            return accountAsset.getDbKey() == null ? newKey(accountAsset.getAccountId(), accountAsset.getAssetId()) : accountAsset.getDbKey();
        }
    } 
    private static final LinkKeyFactory<AccountAsset> accountAssetDbKeyFactory = new AccountAssetDbKeyFactory("account_id", "asset_id");
    
    public static DbKey newKey(long idA, long idB){
        return accountAssetDbKeyFactory.newKey(idA,idB);
    }

    private AccountAssetTable() {
        super("account_asset",accountAssetDbKeyFactory);
    }

    @Override
    public AccountAsset load(Connection con, ResultSet rs, DbKey dbKey) throws SQLException {
        return new AccountAsset(rs, dbKey);
    }

    @Override
    public void save(Connection con, AccountAsset accountAsset) throws SQLException {
         try (final PreparedStatement pstmt = con.prepareStatement("MERGE INTO account_asset " + "(account_id, asset_id, quantity, unconfirmed_quantity, height, latest) " + "KEY (account_id, asset_id, height) VALUES (?, ?, ?, ?, ?, TRUE)")) {
            int i = 0;
            pstmt.setLong(++i, accountAsset.getAccountId());
            pstmt.setLong(++i, accountAsset.getAssetId());
            pstmt.setLong(++i, accountAsset.getQuantityATU());
            pstmt.setLong(++i, accountAsset.getUnconfirmedQuantityATU());
            pstmt.setInt(++i, accountAsset.getHeight());
            pstmt.executeUpdate();
        }       
    }
    
    public void save(AccountAsset accountAsset) {
        AccountService.checkBalance(accountAsset.getAccountId(), accountAsset.getQuantityATU(), accountAsset.getUnconfirmedQuantityATU());
        if (accountAsset.getQuantityATU() > 0 || accountAsset.getUnconfirmedQuantityATU() > 0) {
            insert(accountAsset);
        } else {
            delete(accountAsset);
        }
    }

    @Override
    public void checkAvailable(int height) {
        if (height + Constants.MAX_DIVIDEND_PAYMENT_ROLLBACK < lookupBlockchainProcessor().getMinRollbackHeight()) {
            throw new IllegalArgumentException("Historical data as of height " + height + " not available.");
        }
        if (height > lookupBlockchain().getHeight()) {
            throw new IllegalArgumentException("Height " + height + " exceeds blockchain height " + lookupBlockchain().getHeight());
        }
    }

    @Override
    protected String defaultSort() {
        return " ORDER BY quantity DESC, account_id, asset_id ";
    }

    public int getAssetCount(long assetId) {
        return getCount(new DbClause.LongClause("asset_id", assetId));
    }

    public int getAssetCount(long assetId, int height) {
        return getCount(new DbClause.LongClause("asset_id", assetId), height);
    }

    public int getAccountAssetCount(long accountId) {
        return getCount(new DbClause.LongClause("account_id", accountId));
    }

    public int getAccountAssetCount(long accountId, int height) {
        return getCount(new DbClause.LongClause("account_id", accountId), height);
    }

    public DbIterator<AccountAsset> getAccountAssets(long accountId, int from, int to) {
        return getManyBy(new DbClause.LongClause("account_id", accountId), from, to);
    }

    public DbIterator<AccountAsset> getAccountAssets(long accountId, int height, int from, int to) {
        return getManyBy(new DbClause.LongClause("account_id", accountId), height, from, to);
    }

    public AccountAsset getAccountAsset(long accountId, long assetId) {
        return get(AccountAssetTable.newKey(accountId, assetId));
    }

    public AccountAsset getAccountAsset(long accountId, long assetId, int height) {
        return get(AccountAssetTable.newKey(accountId, assetId), height);
    }

    public DbIterator<AccountAsset> getAssetAccounts(long assetId, int from, int to) {
        return getManyBy(new DbClause.LongClause("asset_id", assetId), from, to, " ORDER BY quantity DESC, account_id ");
    }

    public DbIterator<AccountAsset> getAssetAccounts(long assetId, int height, int from, int to) {
        return getManyBy(new DbClause.LongClause("asset_id", assetId), height, from, to, " ORDER BY quantity DESC, account_id ");
    }

}