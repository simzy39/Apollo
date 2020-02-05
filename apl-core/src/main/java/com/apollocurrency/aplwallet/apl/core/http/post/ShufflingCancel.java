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

package com.apollocurrency.aplwallet.apl.core.http.post;

import com.apollocurrency.aplwallet.apl.core.account.model.Account;
import com.apollocurrency.aplwallet.apl.core.http.APITag;
import com.apollocurrency.aplwallet.apl.core.http.HttpParameterParser;
import com.apollocurrency.aplwallet.apl.util.AplException;
import com.apollocurrency.aplwallet.apl.core.app.Shuffling;
import com.apollocurrency.aplwallet.apl.core.transaction.messages.ShufflingCancellationAttachment;
import javax.enterprise.inject.Vetoed;
import org.json.simple.JSONStreamAware;

import javax.servlet.http.HttpServletRequest;

@Vetoed
public final class ShufflingCancel extends CreateTransaction {

    public ShufflingCancel() {
        super(new APITag[] {APITag.SHUFFLING, APITag.CREATE_TRANSACTION}, "shuffling", "cancellingAccount", "shufflingStateHash");
    }

    @Override
    public JSONStreamAware processRequest(HttpServletRequest req) throws AplException {
        Shuffling shuffling = HttpParameterParser.getShuffling(req);
        long cancellingAccountId = HttpParameterParser.getAccountId(req, "cancellingAccount", false);
        byte[] shufflingStateHash = HttpParameterParser.getBytes(req, "shufflingStateHash", true);
        long accountId = HttpParameterParser.getAccountId(req, this.vaultAccountName(), false);
        byte[] secretBytes = HttpParameterParser.getSecretBytes(req,accountId, true);
        ShufflingCancellationAttachment attachment = shuffling.revealKeySeeds(secretBytes, cancellingAccountId, shufflingStateHash);
        Account account = HttpParameterParser.getSenderAccount(req);
        return createTransaction(req, account, attachment);
    }
}
