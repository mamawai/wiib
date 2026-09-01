package com.mawai.wiibquant.agent.trader;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.mawai.wiibcommon.entity.AiTraderPlan;
import com.mawai.wiibquant.agent.i18n.PromptCatalog;
import com.mawai.wiibquant.mapper.AiTraderPlanMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 计划懒清理趟的补绑语义：限价单在两次唤醒之间成交，计划里的 positionId 还是 null，
 * 下一轮 cleanupStale 拿在场仓位 id 盖上——配对精确 join 的最后一块地基。
 */
class TraderPlanStoreTest {

    private static final long BOUNDARY = 1785171600000L;

    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiTraderPlan.class);
    }

    private final AiTraderPlanMapper mapper = mock(AiTraderPlanMapper.class);
    private final TraderPlanStore store = new TraderPlanStore(mapper, new PromptCatalog());

    private static AiTraderPlan livePlan(String symbol, String side, Long positionId) {
        AiTraderPlan p = new AiTraderPlan();
        p.setId(21L);
        p.setTraderId(7L);
        p.setRoundNo(1);
        p.setSymbol(symbol);
        p.setSide(side);
        p.setStatus(AiTraderPlan.STATUS_LIVE);
        p.setOpenedWakeTime(BOUNDARY - 3600_000L);
        p.setPositionId(positionId);
        return p;
    }

    /** 限价成交补绑：LIVE 计划无 id 且同键有在场仓位 → 盖 id 落库，计划保留 */
    @Test
    void rebindStampsPositionIdOnUnboundLivePlan() {
        when(mapper.selectList(any())).thenReturn(List.of(livePlan("BTCUSDT", "LONG", null)));

        List<AiTraderPlan> live = store.cleanupStale(7L, 1,
                Set.of(TraderPlanStore.key("BTCUSDT", "LONG")),
                Map.of(TraderPlanStore.key("BTCUSDT", "LONG"), 42L), BOUNDARY);

        assertThat(live).hasSize(1);
        assertThat(live.get(0).getPositionId()).isEqualTo(42L);
        ArgumentCaptor<AiTraderPlan> cap = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(mapper).updateById(cap.capture());
        assertThat(cap.getValue().getPositionId()).isEqualTo(42L);
        assertThat(cap.getValue().getStatus()).isEqualTo(AiTraderPlan.STATUS_LIVE);
    }

    /** 已绑定的计划不重写：补绑只救 null，不做刷新——省一次每轮白写 */
    @Test
    void boundPlanNotRewritten() {
        when(mapper.selectList(any())).thenReturn(List.of(livePlan("BTCUSDT", "LONG", 42L)));

        List<AiTraderPlan> live = store.cleanupStale(7L, 1,
                Set.of(TraderPlanStore.key("BTCUSDT", "LONG")),
                Map.of(TraderPlanStore.key("BTCUSDT", "LONG"), 42L), BOUNDARY);

        assertThat(live).hasSize(1);
        verify(mapper, never()).updateById(any(AiTraderPlan.class));
    }

    /** 挂单保活的计划没有仓位可绑：liveKeys 有键、映射无值 → 保留但不动 */
    @Test
    void pendingOrderPlanStaysAliveUnbound() {
        when(mapper.selectList(any())).thenReturn(List.of(livePlan("ETHUSDT", "SHORT", null)));

        List<AiTraderPlan> live = store.cleanupStale(7L, 1,
                Set.of(TraderPlanStore.key("ETHUSDT", "SHORT")), Map.of(), BOUNDARY);

        assertThat(live).hasSize(1);
        assertThat(live.get(0).getPositionId()).isNull();
        verify(mapper, never()).updateById(any(AiTraderPlan.class));
    }

    /** 既有归档语义回归：同键既无持仓也无挂单 → 归档带了结时刻，从存活列表剔除 */
    @Test
    void deadKeyPlanArchivedWithClosedTime() {
        when(mapper.selectList(any())).thenReturn(List.of(livePlan("BTCUSDT", "LONG", 42L)));

        List<AiTraderPlan> live = store.cleanupStale(7L, 1, Set.of(), Map.of(), BOUNDARY);

        assertThat(live).isEmpty();
        ArgumentCaptor<AiTraderPlan> cap = ArgumentCaptor.forClass(AiTraderPlan.class);
        verify(mapper).updateById(cap.capture());
        assertThat(cap.getValue().getStatus()).isEqualTo(AiTraderPlan.STATUS_CLOSED);
        assertThat(cap.getValue().getClosedWakeTime()).isEqualTo(BOUNDARY);
        // 归档不抹绑定：论点→结局的精确配对靠它
        assertThat(cap.getValue().getPositionId()).isEqualTo(42L);
    }
}
