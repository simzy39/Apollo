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

package com.apollocurrency.aplwallet.apl.core.http.get;

import com.apollocurrency.aplwallet.apl.core.app.Order;
import com.apollocurrency.aplwallet.apl.core.db.DbIterator;
import com.apollocurrency.aplwallet.apl.core.http.APITag;
import com.apollocurrency.aplwallet.apl.core.http.AbstractAPIRequestHandler;
import com.apollocurrency.aplwallet.apl.core.http.JSONData;
import com.apollocurrency.aplwallet.apl.core.http.ParameterException;
import com.apollocurrency.aplwallet.apl.core.http.HttpParameterParser;
import javax.enterprise.inject.Vetoed;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONStreamAware;

import javax.servlet.http.HttpServletRequest;

@Vetoed
public final class GetAccountCurrentBidOrders extends AbstractAPIRequestHandler {

    public GetAccountCurrentBidOrders() {
        super(new APITag[] {APITag.ACCOUNTS, APITag.AE}, "account", "asset", "firstIndex", "lastIndex");
    }

    @Override
    public JSONStreamAware processRequest(HttpServletRequest req) throws ParameterException {

        long accountId = HttpParameterParser.getAccountId(req, true);
        long assetId = HttpParameterParser.getUnsignedLong(req, "asset", false);
        int firstIndex = HttpParameterParser.getFirstIndex(req);
        int lastIndex = HttpParameterParser.getLastIndex(req);

        DbIterator<Order.Bid> bidOrders;
        if (assetId == 0) {
            bidOrders = Order.Bid.getBidOrdersByAccount(accountId, firstIndex, lastIndex);
        } else {
            bidOrders = Order.Bid.getBidOrdersByAccountAsset(accountId, assetId, firstIndex, lastIndex);
        }
        JSONArray orders = new JSONArray();
        try {
            while (bidOrders.hasNext()) {
                orders.add(JSONData.bidOrder(bidOrders.next()));
            }
        } finally {
            bidOrders.close();
        }
        JSONObject response = new JSONObject();
        response.put("bidOrders", orders);
        return response;
    }

}
