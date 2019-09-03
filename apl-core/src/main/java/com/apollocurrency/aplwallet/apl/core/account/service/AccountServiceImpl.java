/*
 *  Copyright © 2018-2019 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.account.service;

import com.apollocurrency.aplwallet.apl.core.account.AccountEventType;
import com.apollocurrency.aplwallet.apl.core.account.LedgerEvent;
import com.apollocurrency.aplwallet.apl.core.account.LedgerHolding;
import com.apollocurrency.aplwallet.apl.core.account.dao.AccountGuaranteedBalanceTable;
import com.apollocurrency.aplwallet.apl.core.account.dao.AccountTable;
import com.apollocurrency.aplwallet.apl.core.account.model.Account;
import com.apollocurrency.aplwallet.apl.core.account.model.LedgerEntry;
import com.apollocurrency.aplwallet.apl.core.account.model.PublicKey;
import com.apollocurrency.aplwallet.apl.core.app.Blockchain;
import com.apollocurrency.aplwallet.apl.core.app.BlockchainProcessor;
import com.apollocurrency.aplwallet.apl.core.app.BlockchainProcessorImpl;
import com.apollocurrency.aplwallet.apl.core.app.GlobalSync;
import com.apollocurrency.aplwallet.apl.core.chainid.BlockchainConfig;
import com.apollocurrency.aplwallet.apl.core.db.DatabaseManager;
import com.apollocurrency.aplwallet.apl.core.db.DbClause;
import com.apollocurrency.aplwallet.apl.core.db.DbIterator;
import com.apollocurrency.aplwallet.apl.core.db.DbKey;
import com.apollocurrency.aplwallet.apl.crypto.Convert;
import com.apollocurrency.aplwallet.apl.util.Constants;
import lombok.extern.slf4j.Slf4j;

import javax.enterprise.event.Event;
import javax.enterprise.inject.spi.CDI;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.apollocurrency.aplwallet.apl.core.account.observer.events.AccountEventBinding.literal;
import static com.apollocurrency.aplwallet.apl.core.app.CollectionUtil.toList;

/**
 * @author andrew.zinchenko@gmail.com
 */

@Slf4j
@Singleton
public class AccountServiceImpl implements AccountService {

    public static final int EFFECTIVE_BALANCE_CONFIRMATIONS = 1440;

    private AccountTable accountTable;
    private AccountGuaranteedBalanceTable accountGuaranteedBalanceTable;
    private Blockchain blockchain;
    private BlockchainConfig blockchainConfig;
    private GlobalSync sync;
    private DatabaseManager databaseManager;
    private AccountPublicKeyService accountPublicKeyService;
    private Event<Account> accountEvent;

    @Inject
    public AccountServiceImpl(AccountTable accountTable, Blockchain blockchain, BlockchainConfig blockchainConfig,
                              GlobalSync sync, DatabaseManager databaseManager,
                              AccountPublicKeyService accountPublicKeyService,
                              Event<Account> accountEvent,
                              AccountGuaranteedBalanceTable accountGuaranteedBalanceTable) {
        this.accountTable = accountTable;
        this.blockchain = blockchain;
        this.blockchainConfig = blockchainConfig;
        this.sync = sync;
        this.databaseManager = databaseManager;
        this.accountPublicKeyService = accountPublicKeyService;
        this.accountEvent = accountEvent;
        this.accountGuaranteedBalanceTable = accountGuaranteedBalanceTable;
    }

    @Override
    public int getActiveLeaseCount() {
        return accountTable.getCount(new DbClause.NotNullClause("active_lessee_id"));
    }

    private BlockchainProcessor blockchainProcessor;
    //TODO this lookup-method prevents the cyclic dependencies, need to be removed after refactoring the BlockchainProcessor class
    private void lookupAndInjectBlockchainProcessor() {
        if (this.blockchainProcessor == null) {
            this.blockchainProcessor = CDI.current().select(BlockchainProcessorImpl.class).get();
        }
    }
    //TODO this lookup-method prevents the cyclic dependencies, need to be removed after refactoring the BlockchainProcessor class
    private AccountLedgerService accountLedgerService;
    private AccountLedgerService lookupAccountLedgerService() {
        if (this.accountLedgerService == null) {
            this.accountLedgerService = CDI.current().select(AccountLedgerServiceImpl.class).get();
        }
        return accountLedgerService;
    }

    @Override
    public Account getAccount(long id) {
        DbKey dbKey = AccountTable.newKey(id);
        Account account = accountTable.get(dbKey);
        if (account == null) {
            PublicKey publicKey = accountPublicKeyService.getPublicKey(dbKey);
            if (publicKey != null) {
                account = accountTable.newEntity(dbKey);
                account.setPublicKey(publicKey);
            }
        }
        return account;
    }

    @Override
    public Account getAccount(long id, int height) {
        DbKey dbKey = AccountTable.newKey(id);
        Account account = accountTable.get(dbKey, height);
        if (account == null) {
            PublicKey publicKey = accountPublicKeyService.getPublicKey(dbKey, height);
            if (publicKey != null) {
                account = new Account(id, height);
                account.setPublicKey(publicKey);
            }
        }
        return account;
    }

    @Override
    public Account getAccount(byte[] publicKey) {
        long accountId = AccountService.getId(publicKey);
        Account account = getAccount(accountId);
        if (account == null) {
            return null;
        }
        if (account.getPublicKey() == null) {
            account.setPublicKey(accountPublicKeyService.getPublicKey(AccountTable.newKey(account)));
        }
        if (account.getPublicKey() == null || account.getPublicKey().getPublicKey() == null
                || Arrays.equals(account.getPublicKey().getPublicKey(), publicKey)) {
            return account;
        }
        throw new RuntimeException("DUPLICATE KEY for account " + Long.toUnsignedString(accountId)
                + " existing key " + Convert.toHexString(account.getPublicKey().getPublicKey()) + " new key " + Convert.toHexString(publicKey));
    }

    @Override
    public Account addOrGetAccount(long id) {
        return addOrGetAccount(id, false);
    }

    @Override
    public Account addOrGetAccount(long id, boolean isGenesis) {
        if (id == 0) {
            throw new IllegalArgumentException("Invalid accountId 0");
        }
        DbKey dbKey = AccountTable.newKey(id);
        Account account = accountTable.get(dbKey);
        if (account == null) {
            account = accountTable.newEntity(dbKey);
            PublicKey publicKey = accountPublicKeyService.getPublicKey(dbKey);
            if (publicKey == null) {
                publicKey = accountPublicKeyService.insertNewPublicKey(dbKey, isGenesis);
            }
            account.setPublicKey(publicKey);
        }
        return account;
    }

    @Override
    public void save(Account account) {
        account.setHeight(blockchain.getHeight());
        if (account.getBalanceATM() == 0
                && account.getUnconfirmedBalanceATM() == 0
                && account.getForgedBalanceATM() == 0
                && account.getActiveLesseeId() == 0
                && account.getControls().isEmpty()) {
            accountTable.delete(account, true, blockchain.getHeight());
        } else {
            accountTable.insert(account);
        }
    }

    @Override
    public long getEffectiveBalanceAPL(Account account, int height, boolean lock) {
        if (height <= EFFECTIVE_BALANCE_CONFIRMATIONS) {
            Account genesisAccount = getAccount(account.getId(), 0);
            return genesisAccount == null ? 0 : genesisAccount.getBalanceATM() / Constants.ONE_APL;
        }
        if (account.getPublicKey() == null) {
            account.setPublicKey(accountPublicKeyService.getPublicKey(AccountTable.newKey(account.getId())));
        }
        if (account.getPublicKey() == null || account.getPublicKey().getPublicKey() == null || height - account.getPublicKey().getHeight() <= EFFECTIVE_BALANCE_CONFIRMATIONS) {
            return 0; // cfb: Accounts with the public key revealed less than 1440 blocks ago are not allowed to generate blocks
        }
        if (lock) {
            sync.readLock();
        }
        try {
            long effectiveBalanceATM = getLessorsGuaranteedBalanceATM(account, height);
            if (account.getActiveLesseeId() == 0) {
                effectiveBalanceATM += getGuaranteedBalanceATM(account, blockchainConfig.getGuaranteedBalanceConfirmations(), height);
            }
            return effectiveBalanceATM < Constants.MIN_FORGING_BALANCE_ATM ? 0 : effectiveBalanceATM / Constants.ONE_APL;
        }
        finally {
            if (lock) {
                sync.readUnlock();
            }
        }
    }

    @Override
    public long getGuaranteedBalanceATM(Account account) {
        return getGuaranteedBalanceATM(account, blockchainConfig.getGuaranteedBalanceConfirmations(), blockchain.getHeight());
    }

    @Override
    public long getGuaranteedBalanceATM(Account account, final int numberOfConfirmations, final int currentHeight) {
        lookupAndInjectBlockchainProcessor();
        sync.readLock();
        try {
            int height = currentHeight - numberOfConfirmations;
            if (height + blockchainConfig.getGuaranteedBalanceConfirmations() < blockchainProcessor.getMinRollbackHeight()
                    || height > blockchain.getHeight()) {
                throw new IllegalArgumentException("Height " + height + " not available for guaranteed balance calculation");
            }
            Long sum = accountGuaranteedBalanceTable.getSumOfAdditions(account.getId(), height, currentHeight);
            if (sum == null) {
                return account.getBalanceATM();
            }
            return Math.max(Math.subtractExact(account.getBalanceATM(), sum), 0);
        }
        finally {
            sync.readUnlock();
        }
    }

    @Override
    public long getLessorsGuaranteedBalanceATM(Account account, int height) {
        List<Account> lessors = getLessors(account, height);
        Long[] lessorIds = new Long[lessors.size()];
        long[] balances = new long[lessors.size()];
        for (int i = 0; i < lessors.size(); i++) {
            lessorIds[i] = lessors.get(i).getId();
            balances[i] = lessors.get(i).getBalanceATM();
        }
        long total = 0L;
        int i = 0;
        Map<Long, Long> lessorsAdditions = accountGuaranteedBalanceTable.getLessorsAdditions(
                lessors.stream().map(Account::getId).collect(Collectors.toList()),
                height, blockchain.getHeight());
        if(!lessorsAdditions.isEmpty()) {
            List<Long> lessorsList = lessorsAdditions.keySet().stream().sorted().collect(Collectors.toList());
            for (Long accountId : lessorsList) {
                while (lessorIds[i] < accountId) {
                    total += balances[i++];
                }
                if (Objects.equals(lessorIds[i], accountId)) {
                    total += Math.max(balances[i++] - lessorsAdditions.get(accountId), 0);
                }
            }
        }
        while (i < balances.length) {
            total += balances[i++];
        }
        return total;
    }

    @Override
    public DbIterator<Account> getLessorsIterator(Account account) {
        DbIterator<Account> iterator = accountTable.getManyBy(new DbClause.LongClause("active_lessee_id", account.getId()), 0, -1, " ORDER BY id ASC ");
        return iterator;
    }

    @Override
    public DbIterator<Account> getLessorsIterator(Account account, int height) {
        DbIterator<Account> iterator = accountTable.getManyBy(new DbClause.LongClause("active_lessee_id", account.getId()), height, 0, -1, " ORDER BY id ASC ");
        return iterator;
    }

    @Override
    public List<Account> getLessors(Account account) {
        return toList(getLessorsIterator(account));
    }

    @Override
    public List<Account> getLessors(Account account, int height) {
        return toList(getLessorsIterator(account, height));
    }

    private void logEntryConfirmed(Account account, LedgerEvent event, long eventId, long amountATM, long feeATM) {
        if (lookupAccountLedgerService().mustLogEntry(account.getId(), false)) {
            if (feeATM != 0) {
                lookupAccountLedgerService().logEntry(new LedgerEntry(LedgerEvent.TRANSACTION_FEE, eventId, account.getId(),
                        LedgerHolding.APL_BALANCE, null, feeATM, account.getBalanceATM() - amountATM, blockchain.getLastBlock()));
            }
            if (amountATM != 0) {
                lookupAccountLedgerService().logEntry(new LedgerEntry(event, eventId, account.getId(),
                        LedgerHolding.APL_BALANCE, null, amountATM, account.getBalanceATM(), blockchain.getLastBlock()));
            }
        }
    }

    private void logEntryUnconfirmed(Account account, LedgerEvent event, long eventId, long amountATM, long feeATM) {
        if (lookupAccountLedgerService().mustLogEntry(account.getId(), true)) {
            if (feeATM != 0) {
                lookupAccountLedgerService().logEntry(new LedgerEntry(LedgerEvent.TRANSACTION_FEE, eventId, account.getId(),
                        LedgerHolding.UNCONFIRMED_APL_BALANCE, null, feeATM, account.getUnconfirmedBalanceATM() - amountATM, blockchain.getLastBlock()));
            }
            if (amountATM != 0) {
                lookupAccountLedgerService().logEntry(new LedgerEntry(event, eventId, account.getId(),
                        LedgerHolding.UNCONFIRMED_APL_BALANCE, null, amountATM, account.getUnconfirmedBalanceATM(), blockchain.getLastBlock()));
            }
        }
    }

    @Override
    public void addToBalanceATM(Account account, LedgerEvent event, long eventId, long amountATM, long feeATM) {
        if (amountATM == 0 && feeATM == 0) {
            return;
        }
        if (feeATM != 0 && log.isTraceEnabled()){
            log.trace("Add c balance for {} from {} , amount - {}, total conf- {}, height- {}",
                    account.getId(), last3Stacktrace(),
                    amountATM, amountATM + account.getBalanceATM(), blockchain.getHeight());
        }
        long totalAmountATM = Math.addExact(amountATM, feeATM);
        account.setBalanceATM(Math.addExact(account.getBalanceATM(), totalAmountATM));
        accountGuaranteedBalanceTable.addToGuaranteedBalanceATM(account.getId(), totalAmountATM, blockchain.getHeight());
        AccountService.checkBalance(account.getId(), account.getBalanceATM(), account.getUnconfirmedBalanceATM());
        save(account);

        log.trace("Fire event {} account={}", AccountEventType.BALANCE, account);
        accountEvent.select(literal(AccountEventType.BALANCE)).fire(account);
        logEntryConfirmed(account, event, eventId, amountATM, feeATM);
    }

    @Override
    public  void addToBalanceATM(Account account, LedgerEvent event, long eventId, long amountATM) {
        if(log.isTraceEnabled()) {
            log.trace("Add c balance for {} from {} , amount - {}, total conf- {}, height -{}", account.getId(), last3Stacktrace(), amountATM, amountATM + account.getBalanceATM(), blockchain.getHeight());
        }
        addToBalanceATM(account, event, eventId, amountATM, 0);
    }

    private String last3Stacktrace() {
        StackTraceElement[] stackTraceElements = Thread.currentThread().getStackTrace();
        return String.join("->", getStacktraceSpec(stackTraceElements[5]), getStacktraceSpec(stackTraceElements[4]), getStacktraceSpec(stackTraceElements[3]));
    }

    private String getStacktraceSpec(StackTraceElement element) {
        String className = element.getClassName();
        return className.substring(className.lastIndexOf(".") + 1) + "." + element.getMethodName();
    }

    @Override
    public void addToBalanceAndUnconfirmedBalanceATM(Account account, LedgerEvent event, long eventId, long amountATM, long feeATM) {
        if (amountATM == 0 && feeATM == 0) {
            return;
        }
        if (feeATM != 0 && log.isTraceEnabled()){
            log.trace("Add u balance for {} from {} , amount - {}, total unc {}, height - {}",
                    account.getId(), last3Stacktrace(),
                    amountATM, amountATM + account.getUnconfirmedBalanceATM(), blockchain.getHeight());
        }
        long totalAmountATM = Math.addExact(amountATM, feeATM);
        account.setBalanceATM(Math.addExact(account.getBalanceATM(), totalAmountATM));
        account.setUnconfirmedBalanceATM(Math.addExact(account.getUnconfirmedBalanceATM(), totalAmountATM));
        accountGuaranteedBalanceTable.addToGuaranteedBalanceATM(account.getId(), totalAmountATM, blockchain.getHeight());
        AccountService.checkBalance(account.getId(), account.getBalanceATM(), account.getUnconfirmedBalanceATM());
        save(account);

        log.trace("Fire event {} account={}", AccountEventType.BALANCE, account);
        accountEvent.select(literal(AccountEventType.BALANCE)).fire(account);
        log.trace("Fire event {} account={}", AccountEventType.UNCONFIRMED_BALANCE, account);
        accountEvent.select(literal(AccountEventType.UNCONFIRMED_BALANCE)).fire(account);

        if (event == null) {
            return;
        }
        logEntryUnconfirmed(account, event, eventId, amountATM, feeATM);
        logEntryConfirmed(account, event, eventId, amountATM, feeATM);
    }

    @Override
    public void addToBalanceAndUnconfirmedBalanceATM(Account account, LedgerEvent event, long eventId, long amountATM) {
        if (log.isTraceEnabled()){
            log.trace("Add c and  u balance for {} from {} , amount - {}, total conf- {}, total unc {}, height {}",
                    account.getId(), last3Stacktrace(),
                    amountATM, amountATM + account.getBalanceATM(), amountATM + account.getUnconfirmedBalanceATM(), blockchain.getHeight());
        }
        addToBalanceAndUnconfirmedBalanceATM(account, event, eventId, amountATM, 0);
    }

    @Override
    public void addToUnconfirmedBalanceATM(Account account, LedgerEvent event, long eventId, long amountATM, long feeATM) {
        if (amountATM == 0 && feeATM == 0) {
            return;
        }
        if (feeATM!=0 && log.isTraceEnabled()){
            log.trace("Add u balance for {} from {} , amount - {}, total unc {}, height - {}",
                    account.getId(), last3Stacktrace(),
                    amountATM, amountATM + account.getUnconfirmedBalanceATM(), blockchain.getHeight());
        }
        long totalAmountATM = Math.addExact(amountATM, feeATM);
        account.setUnconfirmedBalanceATM(Math.addExact(account.getUnconfirmedBalanceATM(), totalAmountATM));
        AccountService.checkBalance(account.getId(), account.getBalanceATM(), account.getUnconfirmedBalanceATM());
        save(account);

        log.trace("Fire event {} account={}", AccountEventType.UNCONFIRMED_BALANCE, account);
        accountEvent.select(literal(AccountEventType.UNCONFIRMED_BALANCE)).fire(account);

        if (event == null) {
            return;
        }
        logEntryUnconfirmed(account, event, eventId, amountATM, feeATM);
    }

    @Override
    public void addToUnconfirmedBalanceATM(Account account, LedgerEvent event, long eventId, long amountATM) {
        if (log.isTraceEnabled()){
            log.trace("Add u balance for {} from {} , amount - {}, total unc {}, height - {}",
                    account.getId(), last3Stacktrace(),
                    amountATM, amountATM + account.getUnconfirmedBalanceATM(), blockchain.getHeight());
        }
        addToUnconfirmedBalanceATM(account, event, eventId, amountATM, 0);
    }

    @Override
    public long getTotalAmountOnTopAccounts(int numberOfTopAccounts) {
        return accountTable.getTotalAmountOnTopAccounts(numberOfTopAccounts);
    }

    @Override
    public long getTotalAmountOnTopAccounts() {
        return getTotalAmountOnTopAccounts(100);
    }


    @Override
    public long getTotalNumberOfAccounts() {
        return accountTable.getTotalNumberOfAccounts();
    }

    @Override
    public List<Account> getTopHolders(int numberOfTopAccounts) {
        return accountTable.getTopHolders(numberOfTopAccounts);
    }

    @Override
    public long getTotalSupply() {
        return accountTable.getTotalSupply();
    }

    @Override
    public int getBlockchainHeight() {
        return blockchain.getHeight();
    }

    //Delegated from  AccountPublicKeyService
    @Override
    public boolean setOrVerify(long accountId, byte[] key) {
        return accountPublicKeyService.setOrVerify(accountId, key);
    }

    @Override
    public byte[] getPublicKey(long id) {
        return accountPublicKeyService.getPublicKey(id);
    }
}
