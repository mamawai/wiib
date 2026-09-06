package com.mawai.wiibsim.service;

import java.math.BigDecimal;

public interface FuturesSettlementService {

    void onPriceUpdate(String symbol, BigDecimal price);

    void recoverLimitOrders(String symbol, BigDecimal periodLow, BigDecimal periodHigh);

    void executeTriggeredOrders();

    /** 限价单索引对账：DB里的PENDING挂单全部补回ZSet（纯追加、幂等） */
    void reconcileLimitOrderIndex();

    /** 资金费率结算：先拉一次官方费率写缓存，再按缓存给所有持仓扣费 */
    void chargeFundingFeeAll();
}
