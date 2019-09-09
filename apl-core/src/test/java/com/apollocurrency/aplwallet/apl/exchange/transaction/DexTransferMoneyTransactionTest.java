package com.apollocurrency.aplwallet.apl.exchange.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.apollocurrency.aplwallet.apl.core.account.Account;
import com.apollocurrency.aplwallet.apl.core.account.LedgerEvent;
import com.apollocurrency.aplwallet.apl.core.app.Blockchain;
import com.apollocurrency.aplwallet.apl.core.app.BlockchainImpl;
import com.apollocurrency.aplwallet.apl.core.app.TimeService;
import com.apollocurrency.aplwallet.apl.core.app.Transaction;
import com.apollocurrency.aplwallet.apl.core.chainid.BlockchainConfig;
import com.apollocurrency.aplwallet.apl.core.transaction.TransactionType;
import com.apollocurrency.aplwallet.apl.core.transaction.messages.AbstractAttachment;
import com.apollocurrency.aplwallet.apl.core.transaction.messages.DexControlOfFrozenMoneyAttachment;
import com.apollocurrency.aplwallet.apl.exchange.model.DexContractDBRequest;
import com.apollocurrency.aplwallet.apl.exchange.model.DexCurrencies;
import com.apollocurrency.aplwallet.apl.exchange.model.DexOffer;
import com.apollocurrency.aplwallet.apl.exchange.model.ExchangeContract;
import com.apollocurrency.aplwallet.apl.exchange.model.ExchangeContractStatus;
import com.apollocurrency.aplwallet.apl.exchange.model.OfferStatus;
import com.apollocurrency.aplwallet.apl.exchange.model.OfferType;
import com.apollocurrency.aplwallet.apl.exchange.service.DexService;
import com.apollocurrency.aplwallet.apl.util.AplException;
import org.jboss.weld.junit.MockBean;
import org.jboss.weld.junit5.EnableWeld;
import org.jboss.weld.junit5.WeldInitiator;
import org.jboss.weld.junit5.WeldSetup;
import org.json.simple.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

@EnableWeld
class DexTransferMoneyTransactionTest {
    DexControlOfFrozenMoneyAttachment attachment = new DexControlOfFrozenMoneyAttachment(64, 100);
    ExchangeContract contract = new ExchangeContract(1L, 64L, 200L, 300L, 1000L, 2000L, ExchangeContractStatus.STEP_3, new byte[32], null, null, new byte[32]);
    DexService dexService = mock(DexService.class);
    @WeldSetup
    WeldInitiator weld = WeldInitiator.from()
            .addBeans(
                    MockBean.of(mock(BlockchainConfig.class), BlockchainConfig.class),
                    MockBean.of(mock(BlockchainImpl.class), Blockchain.class, BlockchainImpl.class),
                    MockBean.of(dexService, DexService.class),
                    MockBean.of(mock(TimeService.class), TimeService.class)
            ).build();

    DexTransferMoneyTransaction transactionType;
    @BeforeEach
    void setUp() {
        transactionType = new DexTransferMoneyTransaction();
    }

    @Test
    void parseByteAttachment() throws AplException.NotValidException {
        ByteBuffer buffer = ByteBuffer.allocate(17);
        buffer.put((byte) 1);
        buffer.putLong(64);
        buffer.putLong(100);
        buffer.rewind();
        AbstractAttachment parsedAttachment = transactionType.parseAttachment(buffer);

        assertEquals(attachment, parsedAttachment);
    }

    @Test
    void testJsonParseAttachment() throws AplException.NotValidException {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("contractId", 64L);
        jsonObject.put("offerAmount", 100L);
        jsonObject.put("version.DexTransferMoney", 1);

        AbstractAttachment parsedAttachment = transactionType.parseAttachment(jsonObject);

        assertEquals(attachment, parsedAttachment);
    }

    @Test
    void testSerializeDeserializeAttachmentToByteBuffer() throws AplException.NotValidException {
        int myFullSize = attachment.getFullSize();
        ByteBuffer buffer = ByteBuffer.allocate(myFullSize);
        attachment.putBytes(buffer);
        buffer.rewind();

        AbstractAttachment parsedAttachment = transactionType.parseAttachment(buffer);

        assertEquals(attachment, parsedAttachment);
    }

    @Test
    void testSerializeDeserializeAttachmentToJson() throws AplException.NotValidException {
        JSONObject jsonObject = attachment.getJSONObject();

        AbstractAttachment parsedAttachment = transactionType.parseAttachment(jsonObject);

        assertEquals(attachment, parsedAttachment);
    }

    @Test
    void testValidateAttachment() throws AplException.ValidationException {
        Transaction tx = mock(Transaction.class);
        doReturn(attachment).when(tx).getAttachment();
        assertThrows(AplException.NotValidException.class, () -> transactionType.validateAttachment(tx)); // no contract

        doReturn(contract).when(dexService).getDexContract(DexContractDBRequest.builder().id(64L).build());
        assertThrows(AplException.NotValidException.class, () -> transactionType.validateAttachment(tx));

        doReturn(1000L).when(tx).getSenderId();
        assertThrows(AplException.NotValidException.class, () -> transactionType.validateAttachment(tx));

        doReturn(2000L).when(tx).getSenderId();
        doReturn(1000L).when(tx).getRecipientId();
        assertThrows(AplException.NotCurrentlyValidException.class, () -> transactionType.validateAttachment(tx));

        contract.setCounterTransferTxId("100");
        assertThrows(AplException.NotValidException.class, () -> transactionType.validateAttachment(tx));

        doReturn(100L).when(tx).getId();
        assertThrows(AplException.NotValidException.class, () -> transactionType.validateAttachment(tx));

        DexOffer offer = new DexOffer(1L, 300L, 0L, "", "", OfferType.BUY, OfferStatus.OPEN, DexCurrencies.APL, 100L, DexCurrencies.PAX, BigDecimal.ONE, 500);
        doReturn(offer).when(dexService).getOfferById(300L);
        assertThrows(AplException.NotValidException.class, () -> transactionType.validateAttachment(tx));

        offer.setAccountId(2000L);
        assertThrows(AplException.NotValidException.class, () -> transactionType.validateAttachment(tx));

        offer.setStatus(OfferStatus.WAITING_APPROVAL);
        transactionType.validateAttachment(tx);

        doReturn(1000L).when(tx).getSenderId();
        doReturn(2000L).when(tx).getRecipientId();
        doReturn(offer).when(dexService).getOfferById(200L);
        contract.setCounterTransferTxId("1");
        contract.setTransferTxId("100");
        offer.setAccountId(1000L);
        transactionType.validateAttachment(tx);
    }


    @Test
    void testApplyAttachment() {
        Transaction tx = mock(Transaction.class);
        doReturn(attachment).when(tx).getAttachment();
        doReturn(contract).when(dexService).getDexContract(DexContractDBRequest.builder().id(64L).build());

        Account sender = mock(Account.class);
        Account recipient = mock(Account.class);
        doReturn(1000L).when(sender).getId();
        doReturn(2000L).when(recipient).getId();

        transactionType.applyAttachment(tx, sender, recipient);

        verify(sender).addToBalanceATM(LedgerEvent.DEX_TRANSFER_MONEY, 0, -100);
        verify(recipient).addToBalanceAndUnconfirmedBalanceATM(LedgerEvent.DEX_TRANSFER_MONEY, 0, 100);
        verify(dexService).finishExchange(0, 300);
    }

    @Test
    void testApplyAttachmentForContractRecipient() {
        Transaction tx = mock(Transaction.class);
        doReturn(attachment).when(tx).getAttachment();
        doReturn(contract).when(dexService).getDexContract(DexContractDBRequest.builder().id(64L).build());
        Account sender = mock(Account.class);
        Account recipient = mock(Account.class);
        doReturn(2000L).when(sender).getId();
        doReturn(1000L).when(recipient).getId();

        transactionType.applyAttachment(tx, sender, recipient);

        verify(sender).addToBalanceATM(LedgerEvent.DEX_TRANSFER_MONEY, 0, -100);
        verify(recipient).addToBalanceAndUnconfirmedBalanceATM(LedgerEvent.DEX_TRANSFER_MONEY, 0, 100);
        verify(dexService).finishExchange(0, 200);
    }

    @Test
    void testIsDuplicate() {
        Transaction tx = mock(Transaction.class);
        doReturn(attachment).when(tx).getAttachment();

        Map<TransactionType, Map<String, Integer>> duplicates = new HashMap<>();

        assertFalse(transactionType.isDuplicate(tx, duplicates)); // populate map
        assertTrue(transactionType.isDuplicate(tx, duplicates)); // now contract with id = 64 added to map and another tx, which refer to this contract will be rejected as duplicate
    }
}