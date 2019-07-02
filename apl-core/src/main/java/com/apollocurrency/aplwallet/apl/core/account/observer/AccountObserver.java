package com.apollocurrency.aplwallet.apl.core.account.observer;

import com.apollocurrency.aplwallet.apl.core.account.AccountEventType;
import com.apollocurrency.aplwallet.apl.core.account.AccountLeaseTable;
import com.apollocurrency.aplwallet.apl.core.account.dao.AccountTable;
import com.apollocurrency.aplwallet.apl.core.account.model.Account;
import com.apollocurrency.aplwallet.apl.core.account.model.AccountLease;
import com.apollocurrency.aplwallet.apl.core.account.model.LedgerEntry;
import com.apollocurrency.aplwallet.apl.core.account.observer.events.AccountEventBinding;
import com.apollocurrency.aplwallet.apl.core.account.service.AccountLeaseService;
import com.apollocurrency.aplwallet.apl.core.account.service.AccountLedgerService;
import com.apollocurrency.aplwallet.apl.core.account.service.AccountPublicKeyService;
import com.apollocurrency.aplwallet.apl.core.account.service.AccountService;
import com.apollocurrency.aplwallet.apl.core.app.Block;
import com.apollocurrency.aplwallet.apl.core.app.ShufflingTransaction;
import com.apollocurrency.aplwallet.apl.core.app.observer.events.*;
import com.apollocurrency.aplwallet.apl.core.transaction.messages.PublicKeyAnnouncementAppendix;
import com.apollocurrency.aplwallet.apl.core.transaction.messages.ShufflingRecipientsAttachment;

import javax.enterprise.event.Event;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;

/**
 * @author al
 * @author andrew.zinchenko@gmail.com
 */
@Singleton
public class AccountObserver {

    private AccountService accountService;

    private AccountLeaseService accountLeaseService;

    private AccountPublicKeyService accountPublicKeyService;

    private Event<AccountLease> accountLeaseEvent;

    private AccountLedgerService accountLedgerService;

    @Inject
    public AccountObserver(AccountService accountService,
                           AccountLeaseService accountLeaseService,
                           AccountPublicKeyService accountPublicKeyService,
                           Event<AccountLease> accountLeaseEvent,
                           AccountLedgerService accountLedgerService) {
        this.accountService = accountService;
        this.accountLeaseService = accountLeaseService;
        this.accountPublicKeyService = accountPublicKeyService;
        this.accountLeaseEvent = accountLeaseEvent;
        this.accountLedgerService = accountLedgerService;
    }

    public void onRescanBegan(@Observes @BlockEvent(BlockEventType.RESCAN_BEGIN) Block block) {
        if (accountPublicKeyService.getPublicKeyCache() != null) {
            accountPublicKeyService.getPublicKeyCache().clear();
        }
    }

    public void onBlockPopped(@Observes @BlockEvent(BlockEventType.BLOCK_POPPED) Block block) {
        if (accountPublicKeyService.getPublicKeyCache() != null) {
            accountPublicKeyService.getPublicKeyCache().remove(AccountTable.newKey(block.getGeneratorId()));
            block.getTransactions().forEach(transaction -> {
                accountPublicKeyService.getPublicKeyCache().remove(AccountTable.newKey(transaction.getSenderId()));
                if (!transaction.getAppendages(appendix -> (appendix instanceof PublicKeyAnnouncementAppendix), false).isEmpty()) {
                    accountPublicKeyService.getPublicKeyCache().remove(AccountTable.newKey(transaction.getRecipientId()));
                }
                if (transaction.getType() == ShufflingTransaction.SHUFFLING_RECIPIENTS) {
                    ShufflingRecipientsAttachment shufflingRecipients = (ShufflingRecipientsAttachment) transaction.getAttachment();
                    for (byte[] publicKey : shufflingRecipients.getRecipientPublicKeys()) {
                        accountPublicKeyService.getPublicKeyCache().remove(AccountTable.newKey(AccountService.getId(publicKey)));
                    }
                }
            });
        }
    }

    public void onBlockApplied(@Observes @BlockEvent(BlockEventType.AFTER_BLOCK_APPLY) Block block) {
        int height = block.getHeight();
        List<AccountLease> changingLeases = accountLeaseService.getLeaseChangingAccounts(height);
        for (AccountLease lease : changingLeases) {
            Account lessor = accountService.getAccount(lease.getLessorId());
            if (height == lease.getCurrentLeasingHeightFrom()) {
                lessor.setActiveLesseeId(lease.getCurrentLesseeId());
                //leaseListeners.notify(lease, AccountEventType.LEASE_STARTED);
                accountLeaseEvent.select(AccountEventBinding.literal(AccountEventType.LEASE_STARTED)).fire(lease);
            } else if (height == lease.getCurrentLeasingHeightTo()) {
                //leaseListeners.notify(lease, AccountEventType.LEASE_ENDED);
                accountLeaseEvent.select(AccountEventBinding.literal(AccountEventType.LEASE_ENDED)).fire(lease);
                lessor.setActiveLesseeId(0);
                if (lease.getNextLeasingHeightFrom() == 0) {
                    lease.setCurrentLeasingHeightFrom(0);
                    lease.setCurrentLeasingHeightTo(0);
                    lease.setCurrentLesseeId(0);
                    AccountLeaseTable.getInstance().delete(lease);
                } else {
                    lease.setCurrentLeasingHeightFrom(lease.getNextLeasingHeightFrom());
                    lease.setCurrentLeasingHeightTo(lease.getNextLeasingHeightTo());
                    lease.setCurrentLesseeId(lease.getNextLesseeId());
                    lease.setNextLeasingHeightFrom(0);
                    lease.setNextLeasingHeightTo(0);
                    lease.setNextLesseeId(0);
                    AccountLeaseTable.getInstance().insert(lease);
                    if (height == lease.getCurrentLeasingHeightFrom()) {
                        lessor.setActiveLesseeId(lease.getCurrentLesseeId());
                        //leaseListeners.notify(lease, AccountEventType.LEASE_STARTED);
                        accountLeaseEvent.select(AccountEventBinding.literal(AccountEventType.LEASE_STARTED)).fire(lease);
                    }
                }
            }
            accountService.save(lessor);
        }
    }

    public void onLedgerCommitEntries(@Observes @AccountLedgerEvent(AccountLedgerEventType.COMMIT_ENTRIES) AccountLedgerEventType event ){
        accountLedgerService.commitEntries();
    }

    public void onLedgerClearEntries(@Observes @AccountLedgerEvent(AccountLedgerEventType.CLEAR_ENTRIES) AccountLedgerEventType event){
        accountLedgerService.clearEntries();
    }

}