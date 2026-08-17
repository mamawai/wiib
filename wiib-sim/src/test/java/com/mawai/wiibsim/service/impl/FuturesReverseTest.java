package com.mawai.wiibsim.service.impl;

import com.mawai.wiibcommon.cache.CacheService;
import com.mawai.wiibcommon.dto.FuturesCloseRequest;
import com.mawai.wiibcommon.dto.FuturesOpenRequest;
import com.mawai.wiibcommon.dto.FuturesOrderResponse;
import com.mawai.wiibcommon.entity.FuturesPosition;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibcommon.market.BinanceRestClient;
import com.mawai.wiibsim.config.FuturesLeverageBracketRegistry;
import com.mawai.wiibsim.config.TradeFilterRegistry;
import com.mawai.wiibsim.config.TradingConfig;
import com.mawai.wiibsim.mapper.FuturesOrderMapper;
import com.mawai.wiibsim.mapper.FuturesPositionMapper;
import com.mawai.wiibsim.mapper.UserMapper;
import com.mawai.wiibsim.service.CrossMarginService;
import com.mawai.wiibsim.service.FuturesPositionIndexService;
import com.mawai.wiibsim.service.FuturesTradingService;
import com.mawai.wiibsim.service.UserService;
import com.mawai.wiibsim.util.RedisLockUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 反手 = 市价全平 + 立刻反向开等量新仓。
 * <p>
 * 用 spy 把 closePosition/openPosition 挡掉：这两条链路各自有专门的测试类，这里只钉反手自己的逻辑
 * ——参数怎么翻、量从哪儿取、开仓炸了怎么收场。
 */
class FuturesReverseTest {

    private static final Long UID = 9L;
    private static final Long POS_ID = 1L;
    private static final String SYMBOL = "BTCUSDT";

    private FuturesPositionMapper positionMapper;
    private FuturesTradingServiceImpl service;

    @BeforeEach
    void setUp() {
        positionMapper = mock(FuturesPositionMapper.class);
        service = spy(new FuturesTradingServiceImpl(
                mock(UserService.class), mock(UserMapper.class), positionMapper, mock(FuturesOrderMapper.class),
                new TradingConfig(), mock(RedisLockUtil.class), mock(CacheService.class),
                mock(FuturesPositionIndexService.class), mock(FuturesLeverageBracketRegistry.class),
                mock(CrossMarginService.class), new TradeFilterRegistry(mock(BinanceRestClient.class))));
    }

    private static FuturesPosition pos(String side, String mode, int leverage, String qty) {
        FuturesPosition p = new FuturesPosition();
        p.setId(POS_ID);
        p.setUserId(UID);
        p.setSymbol(SYMBOL);
        p.setSide(side);
        p.setMarginMode(mode);
        p.setLeverage(leverage);
        p.setEntryPrice(new BigDecimal("100"));
        p.setQuantity(new BigDecimal(qty));
        p.setMargin(new BigDecimal("10"));
        p.setStatus("OPEN");
        return p;
    }

    private static FuturesOrderResponse closedOrder(String orderSide, String qty, String pnl) {
        FuturesOrderResponse r = new FuturesOrderResponse();
        r.setOrderId(100L);
        r.setSymbol(SYMBOL);
        r.setOrderSide(orderSide);
        r.setQuantity(new BigDecimal(qty));
        r.setRealizedPnl(new BigDecimal(pnl));
        r.setStatus("FILLED");
        return r;
    }

    @Test
    void 平多开空_方向翻转_杠杆与保证金模式照抄原仓() {
        when(positionMapper.selectById(POS_ID)).thenReturn(pos("LONG", FuturesPosition.CROSS, 20, "2"));
        doReturn(closedOrder("CLOSE_LONG", "2", "50")).when(service).closePosition(eq(UID), any());
        FuturesOrderResponse opened = new FuturesOrderResponse();
        doReturn(opened).when(service).openPosition(eq(UID), any());

        FuturesTradingService.ReverseResult result = service.reversePosition(UID, POS_ID);

        ArgumentCaptor<FuturesCloseRequest> closeCaptor = ArgumentCaptor.forClass(FuturesCloseRequest.class);
        verify(service).closePosition(eq(UID), closeCaptor.capture());
        assertThat(closeCaptor.getValue().getPositionId()).isEqualTo(POS_ID);
        assertThat(closeCaptor.getValue().getOrderType()).isEqualTo("MARKET");
        // quantity 必须不传：全平量由平仓在自己的锁内取实时值，外面查到的是可能过期的快照
        assertThat(closeCaptor.getValue().getQuantity()).isNull();

        ArgumentCaptor<FuturesOpenRequest> openCaptor = ArgumentCaptor.forClass(FuturesOpenRequest.class);
        verify(service).openPosition(eq(UID), openCaptor.capture());
        FuturesOpenRequest openReq = openCaptor.getValue();
        assertThat(openReq.getSymbol()).isEqualTo(SYMBOL);
        assertThat(openReq.getSide()).isEqualTo("SHORT");
        assertThat(openReq.getMarginMode()).isEqualTo(FuturesPosition.CROSS);
        assertThat(openReq.getQuantity()).isEqualByComparingTo("2");
        assertThat(openReq.getLeverage()).isEqualTo(20);
        assertThat(openReq.getOrderType()).isEqualTo("MARKET");
        // 反手不带 SL/TP 过去：价位对称翻转容易搞错方向，新仓裸着让用户自己设
        assertThat(openReq.getStopLosses()).isNull();
        assertThat(openReq.getTakeProfits()).isNull();

        assertThat(result.closed().getRealizedPnl()).isEqualByComparingTo("50");
        assertThat(result.opened()).isSameAs(opened);
        assertThat(result.openError()).isNull();
    }

    @Test
    void 平空开多_逐仓同样照抄() {
        when(positionMapper.selectById(POS_ID)).thenReturn(pos("SHORT", FuturesPosition.ISOLATED, 5, "3"));
        doReturn(closedOrder("CLOSE_SHORT", "3", "-8")).when(service).closePosition(eq(UID), any());
        doReturn(new FuturesOrderResponse()).when(service).openPosition(eq(UID), any());

        service.reversePosition(UID, POS_ID);

        ArgumentCaptor<FuturesOpenRequest> openCaptor = ArgumentCaptor.forClass(FuturesOpenRequest.class);
        verify(service).openPosition(eq(UID), openCaptor.capture());
        assertThat(openCaptor.getValue().getSide()).isEqualTo("LONG");
        assertThat(openCaptor.getValue().getMarginMode()).isEqualTo(FuturesPosition.ISOLATED);
        assertThat(openCaptor.getValue().getLeverage()).isEqualTo(5);
    }

    /**
     * 开仓量取"真平掉多少"，不取查库那份快照量。
     * <p>
     * 查仓位到平仓拿锁之间 SL/TP 可能吃掉一部分，用快照量会反向开多——那就不是反手是加仓了。
     */
    @Test
    void 开仓量按实际平掉的量_不按查库快照量() {
        when(positionMapper.selectById(POS_ID)).thenReturn(pos("LONG", FuturesPosition.CROSS, 20, "2"));
        doReturn(closedOrder("CLOSE_LONG", "1.5", "30")).when(service).closePosition(eq(UID), any());
        doReturn(new FuturesOrderResponse()).when(service).openPosition(eq(UID), any());

        service.reversePosition(UID, POS_ID);

        ArgumentCaptor<FuturesOpenRequest> openCaptor = ArgumentCaptor.forClass(FuturesOpenRequest.class);
        verify(service).openPosition(eq(UID), openCaptor.capture());
        assertThat(openCaptor.getValue().getQuantity()).isEqualByComparingTo("1.5");
    }

    /**
     * 反向开仓失败不许抛：仓位已经真平了、盈亏真结算了，抛出去前端只显示"失败"，
     * 用户根本不知道自己已经空仓。必须带着已平仓信息返回，让前端把半成功说清楚。
     */
    @Test
    void 反向开仓失败_不抛异常_返回里带着已平仓信息() {
        when(positionMapper.selectById(POS_ID)).thenReturn(pos("LONG", FuturesPosition.CROSS, 20, "2"));
        doReturn(closedOrder("CLOSE_LONG", "2", "50")).when(service).closePosition(eq(UID), any());
        doThrow(new BizException(ErrorCode.FUTURES_INSUFFICIENT_BALANCE)).when(service).openPosition(eq(UID), any());

        FuturesTradingService.ReverseResult result = service.reversePosition(UID, POS_ID);

        assertThat(result.opened()).isNull();
        assertThat(result.openError()).isEqualTo(ErrorCode.FUTURES_INSUFFICIENT_BALANCE.getMsg());
        assertThat(result.closed().getQuantity()).isEqualByComparingTo("2");
        assertThat(result.closed().getRealizedPnl()).isEqualByComparingTo("50");
    }

    /** 平仓这步失败照常抛：什么都没发生，没有半成功要交代 */
    @Test
    void 平仓失败_直接抛_不去开反向仓() {
        when(positionMapper.selectById(POS_ID)).thenReturn(pos("LONG", FuturesPosition.CROSS, 20, "2"));
        doThrow(new BizException(ErrorCode.ORDER_PROCESSING)).when(service).closePosition(eq(UID), any());

        assertThatThrownBy(() -> service.reversePosition(UID, POS_ID))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.ORDER_PROCESSING.getCode());

        verify(service, never()).openPosition(any(), any());
    }

    @Test
    void 仓位不属于自己_直接拒() {
        FuturesPosition other = pos("LONG", FuturesPosition.CROSS, 20, "2");
        other.setUserId(999L);
        when(positionMapper.selectById(POS_ID)).thenReturn(other);

        assertThatThrownBy(() -> service.reversePosition(UID, POS_ID))
                .isInstanceOf(BizException.class)
                .extracting("code").isEqualTo(ErrorCode.FUTURES_POSITION_NOT_FOUND.getCode());

        verify(service, never()).closePosition(any(), any());
    }
}
