/*
 * Copyright © 2018-2019 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.rest.converter;

import com.apollocurrency.aplwallet.api.dto.BlockDTO;
import com.apollocurrency.aplwallet.api.dto.TransactionDTO;
import com.apollocurrency.aplwallet.apl.core.app.Block;
import com.apollocurrency.aplwallet.apl.core.app.Blockchain;
import com.apollocurrency.aplwallet.apl.core.app.Convert2;
import com.apollocurrency.aplwallet.apl.core.app.Transaction;
import com.apollocurrency.aplwallet.apl.core.phasing.PhasingPollService;
import com.apollocurrency.aplwallet.apl.crypto.Convert;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AccountBlockConverter implements Converter<Block, BlockDTO> {

    private Blockchain blockchain;
    private TransactionConverter transactionConverter;
    private PhasingPollService phasingPollService;

    @Inject
    public AccountBlockConverter(Blockchain blockchain, TransactionConverter transactionConverter, PhasingPollService phasingPollService) {
        this.blockchain = blockchain;
        this.transactionConverter = transactionConverter;
        this.phasingPollService = phasingPollService;
    }

    @Override
    public BlockDTO apply(Block model) {

        BlockDTO dto = new BlockDTO();
        dto.setBlock(model.getStringId());
        dto.setHeight(model.getHeight());
        dto.setGenerator(Long.toUnsignedString(model.getGeneratorId()));
        dto.setGeneratorRS(Convert2.rsAccount(model.getGeneratorId()));
        dto.setGeneratorPublicKey(Convert.toHexString(model.getGeneratorPublicKey()));
        dto.setTimestamp(model.getTimestamp());
        dto.setTimeout(model.getTimeout());
        dto.setNumberOfTransactions(model.getTransactions().size());
        dto.setTotalFeeATM(String.valueOf(model.getTotalFeeATM()));
        dto.setPayloadLength(model.getPayloadLength());
        dto.setVersion(model.getVersion());
        dto.setBaseTarget(String.valueOf(model.getBaseTarget()));
        dto.setCumulativeDifficulty(model.getCumulativeDifficulty().toString());
        if (model.getPreviousBlockId() != 0) {
            dto.setPreviousBlock(Long.toUnsignedString(model.getPreviousBlockId()));
        }
        dto.setPreviousBlockHash(Convert.toHexString(model.getPreviousBlockHash()));
        if (model.getNextBlockId() != 0 ){
            dto.setNextBlock(Long.toUnsignedString(model.getNextBlockId()));
        }
        dto.setPayloadHash(Convert.toHexString(model.getPayloadHash()));
        dto.setGenerationSignature(Convert.toHexString(model.getGenerationSignature()));
        dto.setBlockSignature(Convert.toHexString(model.getBlockSignature()));
        dto.setTransactions(Collections.emptyList());
        dto.setTotalAmountATM(String.valueOf(
                model.getTransactions().stream().mapToLong(Transaction::getAmountATM).sum()));

        return dto;
    }

    public void addTransactions(BlockDTO o, Block model){
        if ( o != null){
            List<TransactionDTO> transactionDTOList = new ArrayList<>();
            model.getTransactions()
                    .forEach(t -> transactionDTOList.add(transactionConverter.convert(t)));
            o.setTransactions(transactionDTOList);
        }
    }

    public void addPhasedTransactions(BlockDTO o, Block model){
        if ( o != null){
            List<TransactionDTO> transactionDTOList = new ArrayList<>();
            List<Long> transactrionIdList = phasingPollService.getApprovedTransactionIds(model.getHeight());
            transactrionIdList
                    .forEach( trId -> transactionDTOList
                            .add(transactionConverter.convert(blockchain.getTransaction(trId))));

            o.setExecutedPhasedTransactions(transactionDTOList);
        }
    }

    public void addPhasedTransactionIds(BlockDTO o, Block model){
        if ( o != null){
            List<String> transactionList = new ArrayList<>();
            List<Long> approvedTransactionIds = phasingPollService.getApprovedTransactionIds(model.getHeight());
            approvedTransactionIds
                    .forEach( trId -> transactionList.add(Long.toUnsignedString(trId)));

            o.setExecutedPhasedTransactions(transactionList);
        }
    }

}