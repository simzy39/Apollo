/*
 * Copyright © 2018-2019 Apollo Foundation
 */

package com.apollocurrency.aplwallet.apl.core.http.post;

import com.apollocurrency.aplwallet.apl.core.model.AplWalletKey;
import com.apollocurrency.aplwallet.apl.core.app.Helper2FA;
import com.apollocurrency.aplwallet.apl.core.http.APITag;
import com.apollocurrency.aplwallet.apl.core.http.AbstractAPIRequestHandler;
import com.apollocurrency.aplwallet.apl.core.http.JSONResponses;
import com.apollocurrency.aplwallet.apl.util.AplException;
import org.json.simple.JSONStreamAware;

import javax.servlet.http.HttpServletRequest;

public class GenerateAccount extends AbstractAPIRequestHandler {
    private static class GenerateAccountHolder {
        private static final GenerateAccount INSTANCE = new GenerateAccount();
    }

    public static GenerateAccount getInstance() {
        return GenerateAccountHolder.INSTANCE;
    }
    protected GenerateAccount() {
        super(new APITag[] {APITag.ACCOUNTS}, "passphrase");

    }

    @Override
    public JSONStreamAware processRequest(HttpServletRequest request) throws AplException {
        String passphrase = request.getParameter("passphrase");
        AplWalletKey aplWalletKey = Helper2FA.generateUserAccounts(passphrase);
        if (aplWalletKey != null) { return aplWalletKey.toJSON();}
        return JSONResponses.ACCOUNT_GENERATION_ERROR;
    }

    @Override
    protected boolean requirePost() {
        return true;
    }
}
