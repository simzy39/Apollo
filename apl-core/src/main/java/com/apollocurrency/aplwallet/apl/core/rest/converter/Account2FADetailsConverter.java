package com.apollocurrency.aplwallet.apl.core.rest.converter;

import com.apollocurrency.aplwallet.api.dto.Account2FADetailsDTO;
import com.apollocurrency.aplwallet.apl.core.app.Convert2;
import com.apollocurrency.aplwallet.apl.core.app.TwoFactorAuthDetails;

import java.util.Random;

public class Account2FADetailsConverter implements Converter<TwoFactorAuthDetails, Account2FADetailsDTO> {

    @Override
    public Account2FADetailsDTO apply(TwoFactorAuthDetails model) {
        Account2FADetailsDTO dto = new Account2FADetailsDTO();
        dto.setQrCodeUrl(model.getQrCodeUrl());
        dto.setSecret(model.getSecret());
        dto.setStatus(model.getStatus2Fa());
        return dto;
    }

    public void addAccount(Account2FADetailsDTO o, long accountId){
        if (o != null) {
            o.setAccount(Long.toUnsignedString(accountId));
            o.setAccountRS(Convert2.rsAccount(accountId));
        }
    }

    public void addPrivateAccount(Account2FADetailsDTO o, long accountId){
        if (o != null) {
            o.setAccount(Long.toUnsignedString(accountId));
            long accId = AccountConverter.anonymizeAccount();
            o.setAccountRS(Convert2.rsAccount(accId));
        }
    }

}
