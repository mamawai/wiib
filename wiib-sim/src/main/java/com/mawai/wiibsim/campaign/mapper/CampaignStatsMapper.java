package com.mawai.wiibsim.campaign.mapper;

import com.mawai.wiibsim.campaign.model.ClosedPositionRow;
import com.mawai.wiibsim.campaign.model.CountRow;
import com.mawai.wiibsim.campaign.model.EligibleUserRow;
import com.mawai.wiibsim.campaign.model.SpotOrderRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 活动读业务表的全部 SQL。只 SELECT，一条 INSERT/UPDATE 都没有。
 * 全站口径不带 userId：三个视图同源不对不上账。
 * 时间区间一律左闭右开 start &lt;= t &lt; end：BETWEEN 会把 end 整秒含进去，跨活动重叠一秒。
 */
@Mapper
public interface CampaignStatsMapper {

    /**
     * 活动期内平掉的仓位，附订单侧聚合出的累计投入保证金与已实现净盈亏。
     * 口径与 FuturesPositionMapper.selectPositionHistory（仓位历史页 ROI）完全一致，积分与页面同口径。
     * 状态四个全列（FILLED/STOP_LOSS/TAKE_PROFIT/LIQUIDATED 是订单终态），漏一个少算一段盈亏。
     * 用 JOIN 不用 LEFT JOIN：没有终态订单的仓位（破产清算刷的）没有真实盈亏，不参与。
     */
    @Select("""
            SELECT p.user_id                                     AS user_id,
                   p.id                                          AS position_id,
                   p.symbol                                      AS symbol,
                   p.margin_mode                                 AS margin_mode,
                   p.updated_at                                  AS closed_at,
                   COALESCE(o.invested_margin, 0)                AS invested_margin,
                   COALESCE(o.net_pnl, 0) - p.funding_fee_total  AS net_pnl
            FROM futures_position p
            JOIN (
                SELECT position_id,
                       SUM(CASE WHEN order_side LIKE 'CLOSE%' THEN 0
                                ELSE COALESCE(margin_amount, 0) END)                AS invested_margin,
                       SUM(COALESCE(realized_pnl, 0) - COALESCE(commission, 0))     AS net_pnl
                FROM futures_order
                WHERE status IN ('FILLED', 'STOP_LOSS', 'TAKE_PROFIT', 'LIQUIDATED')
                GROUP BY position_id
            ) o ON o.position_id = p.id
            WHERE p.status IN ('CLOSED', 'LIQUIDATED')
              AND p.updated_at >= #{start}
              AND p.updated_at <  #{end}
            ORDER BY p.user_id, p.updated_at, p.id
            """)
    List<ClosedPositionRow> listClosedPositions(@Param("start") LocalDateTime start,
                                                @Param("end") LocalDateTime end);

    /**
     * 现货成交流水（全历史、按成交时间排好序），只取活动期内买入额（含手续费）过门槛的 (用户, 标的) 对，
     * Java 侧逐笔重放算已实现收益率的高水位（TradeScorer.countSpotUnits）。
     * <p>
     * 返流水不返聚合：达标单位只进不退，要逐笔重放取高水位，期末聚合会漏中途冲高。
     * 外层不卡时间窗：比率按全历史累计算（净现金流造不了假），窗口筛选在 Java 侧做。
     * 成交时间取 updated_at 不取 created_at：限价单挂上和成交是两个时刻。
     */
    @Select("""
            SELECT o.user_id                 AS user_id,
                   o.symbol                  AS symbol,
                   o.order_side              AS order_side,
                   o.filled_amount           AS filled_amount,
                   COALESCE(o.commission, 0) AS commission,
                   o.updated_at              AS filled_at
            FROM crypto_order o
            JOIN (
                SELECT user_id, symbol
                FROM crypto_order
                WHERE status = 'FILLED'
                  AND order_side = 'BUY'
                  AND updated_at >= #{start} AND updated_at < #{end}
                GROUP BY user_id, symbol
                HAVING SUM(filled_amount + COALESCE(commission, 0)) >= #{minBuy}
            ) q ON q.user_id = o.user_id AND q.symbol = o.symbol
            WHERE o.status = 'FILLED'
            ORDER BY o.user_id, o.symbol, o.updated_at, o.id
            """)
    List<SpotOrderRow> listSpotOrders(@Param("start") LocalDateTime start,
                                      @Param("end") LocalDateTime end,
                                      @Param("minBuy") BigDecimal minBuy);

    /**
     * 预测市场：活动期内下注、持有到结算且猜中、额度达标的次数。
     * status='WON' 即"持有到结算且猜中"（结算前卖是 SOLD、破产清算是 CANCELLED）；
     * 额度取 cost（部分卖出会同步减，结算时刻恰是持有到底的那部分）；窗口按 created_at（下注时间）。
     */
    @Select("""
            SELECT user_id AS user_id, COUNT(*) AS cnt
            FROM prediction_bet
            WHERE status = 'WON'
              AND cost >= #{minCost}
              AND created_at >= #{start}
              AND created_at <  #{end}
            GROUP BY user_id
            """)
    List<CountRow> countPredictionHits(@Param("start") LocalDateTime start,
                                       @Param("end") LocalDateTime end,
                                       @Param("minCost") BigDecimal minCost);

    /**
     * 止损被触发过的次数（用于「止损英雄」，只判有没有，不按次数给分）。
     * 止损档位无"已触发"标志位，唯一可靠的持久化依据是 futures_order.status = 'STOP_LOSS'。
     */
    @Select("""
            SELECT user_id AS user_id, COUNT(*) AS cnt
            FROM futures_order
            WHERE status = 'STOP_LOSS'
              AND created_at >= #{start}
              AND created_at <  #{end}
            GROUP BY user_id
            """)
    List<CountRow> countStopLossTriggered(@Param("start") LocalDateTime start,
                                          @Param("end") LocalDateTime end);

    /**
     * 逐仓强平次数（−5/次）。
     * 查订单表不查仓位表：真强平必插订单，破产清算只刷仓位状态不插单，按仓位表会多扣。
     * margin_mode 切开：全仓爆仓每仓各插一条 LIQUIDATED 但不罚分，不切会吃一串 −5。
     */
    @Select("""
            SELECT user_id AS user_id, COUNT(*) AS cnt
            FROM futures_order
            WHERE status = 'LIQUIDATED'
              AND margin_mode = 'ISOLATED'
              AND created_at >= #{start}
              AND created_at <  #{end}
            GROUP BY user_id
            """)
    List<CountRow> countIsolatedLiquidations(@Param("start") LocalDateTime start,
                                             @Param("end") LocalDateTime end);

    /**
     * 全仓爆仓事件数（并入「触发强平」，每次 −5）。
     * <p>
     * type=6 是 Notification.TYPE_CROSS_LIQUIDATION，实体注释写明
     * 「一次爆掉该用户所有全仓仓位，合并成一条」—— 一次事件正好一条记录，
     * 这是全站唯一能把"爆仓事件"与"被爆的仓位数"分开的地方。
     * 逐仓那半边按 LIQUIDATED 订单数（countIsolatedLiquidations），两边相加即触发强平总次数。
     */
    @Select("""
            SELECT user_id AS user_id, COUNT(*) AS cnt
            FROM notification
            WHERE type = 6
              AND created_at >= #{start}
              AND created_at <  #{end}
            GROUP BY user_id
            """)
    List<CountRow> countCrossLiquidations(@Param("start") LocalDateTime start,
                                          @Param("end") LocalDateTime end);

    /** 活动期内发过评论的用户（「首次评论 +1」只判有没有）。status=1 即未被删 */
    @Select("""
            SELECT DISTINCT user_id
            FROM comment
            WHERE status = 1
              AND created_at >= #{start}
              AND created_at <  #{end}
            """)
    List<Long> listCommenters(@Param("start") LocalDateTime start,
                              @Param("end") LocalDateTime end);

    /**
     * 参与积分与榜单的用户 = linux_do_id 是纯数字的账号，就这一条规则。
     * 分发接口的 user_id 必须是数字，非纯数字的账号物理上收不到款；
     * 一条正则同时挡住机器人（'internal:%'）、'AI_TRADER'、'local-admin' 与邀请码用户（NULL），
     * 且不像枚举哨兵值那样会漏掉新增的。邀请码用户刻意完全出局；出资方 id=1 不设分支按普通参与者对待。
     */
    @Select("""
            SELECT id AS user_id, username AS username, linux_do_id AS linux_do_id
            FROM "user"
            WHERE linux_do_id ~ '^[0-9]+$'
            """)
    List<EligibleUserRow> listEligibleUsers();

    /**
     * 平台侧记录的 LinuxDo ID。领取时用来校验"这次授权的是不是本人的号"。
     * 只核 id 不核 username：username 会过期（改名撞名时平台保留旧名），linux_do_id 从不变。
     */
    @Select("SELECT linux_do_id FROM \"user\" WHERE id = #{userId}")
    String selectLinuxDoId(@Param("userId") Long userId);
}
