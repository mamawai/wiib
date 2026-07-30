package com.mawai.wiibsim.campaign.mapper;

import com.mawai.wiibsim.campaign.model.ClosedPositionRow;
import com.mawai.wiibsim.campaign.model.CountRow;
import com.mawai.wiibsim.campaign.model.EligibleUserRow;
import com.mawai.wiibsim.campaign.model.HeldPositionRow;
import com.mawai.wiibsim.campaign.model.SpotSymbolRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 活动读业务表的全部 SQL。只 SELECT，一条 INSERT/UPDATE 都没有。
 * <p>
 * 【为什么全站口径不带 userId】~100 人的量级，全站扫一次 + Redis 缓存 60s，
 * 比每个人各查一遍便宜得多；更要紧的是「我的积分」「排行」「预估 LDC」出自同一份结果，
 * 不会出现"我的积分 37、榜上写 36"这种对不上账的场面。
 * <p>
 * 【时间区间一律左闭右开】start &lt;= t &lt; end。活动最后一天 23:59:59 的成交要算进来，
 * 而 end 那一刻的不算 —— 用 BETWEEN 会把 end 整秒也含进去，跨活动时两场会重叠一秒。
 */
@Mapper
public interface CampaignStatsMapper {

    /**
     * 活动期内平掉的仓位，附订单侧聚合出的累计投入保证金与已实现净盈亏。
     * <p>
     * 【口径来源】与 FuturesPositionMapper:148-183 的 selectPositionHistory 完全一致，
     * 也就是用户在「仓位历史」页看到的那个 ROI。积分与页面显示同口径，事后无争议。
     * <p>
     * 【状态四个全列】FILLED 手动成交、STOP_LOSS/TAKE_PROFIT 保护单触发、LIQUIDATED 强平，
     * 这四个是订单终态，漏一个就少算一段盈亏。注意实体注释(FuturesOrder:55)与
     * DDL 注释(init.sql:347)都漏写了 STOP_LOSS/TAKE_PROFIT，
     * FuturesOrderMapper:48 漏写了 LIQUIDATED —— 别照抄那几处。
     * <p>
     * 【用 JOIN 不用 LEFT JOIN】没有任何终态订单的仓位（只可能是破产清算刷出来的，
     * 见 BankruptcyServiceImpl:219）根本没有真实盈亏可言，直接不参与仓位任务。
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
     * 现货：按 (用户, 标的) 聚合，只留活动期内买入额已过 1000 门槛的那些。
     * <p>
     * 【成交时间取 updated_at 不取 created_at】限价单挂上和真正成交是两个时刻，
     * 按下单时间算会把活动前挂、活动中成交的单排除掉。同 FuturesPositionMapper:196 的口径。
     * <p>
     * 【HAVING 先剪枝】不达门槛的 (用户,标的) 对根本不用返回，结果集能小一个量级。
     */
    @Select("""
            SELECT user_id AS user_id,
                   symbol  AS symbol,
                   COALESCE(SUM(CASE WHEN order_side = 'BUY'
                                      AND updated_at >= #{start} AND updated_at < #{end}
                                     THEN filled_amount + COALESCE(commission, 0)
                                     ELSE 0 END), 0) AS buy_in_window,
                   COALESCE(SUM(CASE WHEN order_side = 'BUY'
                                     THEN filled_amount + COALESCE(commission, 0)
                                     ELSE 0 END), 0) AS buy_all,
                   COALESCE(SUM(CASE WHEN order_side = 'SELL'
                                     THEN filled_amount - COALESCE(commission, 0)
                                     ELSE 0 END), 0) AS sell_all
            FROM crypto_order
            WHERE status = 'FILLED'
            GROUP BY user_id, symbol
            HAVING SUM(CASE WHEN order_side = 'BUY'
                             AND updated_at >= #{start} AND updated_at < #{end}
                            THEN filled_amount + COALESCE(commission, 0)
                            ELSE 0 END) >= #{minBuy}
            ORDER BY user_id, buy_in_window DESC
            """)
    List<SpotSymbolRow> listSpotSymbols(@Param("start") LocalDateTime start,
                                        @Param("end") LocalDateTime end,
                                        @Param("minBuy") BigDecimal minBuy);

    /**
     * 预测市场：活动期内下注、持有到结算且猜中、额度达标的次数。
     * <p>
     * 【status='WON' 就是"持有到结算且猜中"】结算前卖掉是 SOLD、破产清算是 CANCELLED，
     * 都进不了 WON（见 PredictionBetMapper:52-54 的 settleWon，条件带 status='ACTIVE'）。
     * <p>
     * 【额度取 cost】casPartialSell 会同步减 contracts 与 cost，
     * 所以结算时刻的 cost 恰好是"真正持有到结算"的那部分额度。
     * <p>
     * 【窗口按 created_at】按下注时间算，用户直觉是"活动期间下的注"。
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
     * <p>
     * 【只能靠订单状态判】止损档位是 JSONB 里的 {id, price, quantity} 三字段，
     * 没有"已触发"标志位；部分平仓时该档被物理删掉，全平时原样留着。
     * 唯一可靠的持久化依据就是 futures_order.status = 'STOP_LOSS'
     * （FuturesRiskServiceImpl:384 写入）。
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
     * <p>
     * 【为什么查订单表不查仓位表】BankruptcyServiceImpl:164,219 的 closeOpenByUserId
     * 会把破产用户所有 OPEN 仓位（逐仓+全仓）一律刷成 LIQUIDATED，且不插订单。
     * 按仓位表计数，破产的人手里几个仓就凭空多扣几个 −5。真强平必插订单
     * （FuturesRiskServiceImpl:218），破产清理不插。
     * <p>
     * 【margin_mode 切开】全仓爆仓时每个仓位也各插一条 LIQUIDATED 订单
     * （CrossLiquidationServiceImpl:120），不切开就是一次爆仓被扣两遍
     * （每仓 −5 再加事件 −30）。
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
     * 全仓爆仓次数（−30/次）。
     * <p>
     * type=6 是 Notification.TYPE_CROSS_LIQUIDATION，实体注释写明
     * 「一次爆掉该用户所有全仓仓位，合并成一条」—— 一次事件正好一条记录，
     * 这是全站唯一能把"爆仓事件"与"被爆的仓位数"分开的地方。
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
     * <p>
     * 【为什么"纯数字"就是全部判据】官方文档 §3.4 写明分发接口的 user_id 必须是<b>数字</b>。
     * 也就是说 linux_do_id 不是纯数字的账号，物理上就不可能是一个能收款的 LinuxDo 用户 ——
     * 那么让它算分上榜就只是在污染榜单。一条正则同时挡住量化机器人（'internal:%'，
     * UserServiceImpl:92）、遗留的 'AI_TRADER'、引导用的 'local-admin'（UserMapper:65-68）
     * 与邀请码用户（NULL，AuthServiceImpl:220-231 不设该列）。
     * <p>
     * 【为什么不写成枚举那几个哨兵值】枚举法只挡得住你当时想得到的那几个。
     * 真库上它就漏了 'AI_TRADER'：它既不匹配 'internal:%' 也不是 'local-admin'，
     * 于是堂而皇之进了名单 —— 而这一个号独占 37 个已平仓位里的 32 个，
     * 上榜就是把真人碾平。下一个新哨兵值出现时，枚举法还会再漏一次，这条正则不会。
     * <p>
     * 【邀请码用户就此完全出局】不算分、不上榜、不参与分配。这是刻意的取舍：
     * 他们收不到 LDC，让他们占着榜位只会挤掉能收款的真人。
     * <p>
     * 【出资方不做特殊处理】id=1 按普通参与者对待。若分发时商户号恰好是同一个
     * LinuxDo 用户，服务端会以「不能转账给自己」拒掉那一笔，届时人工处理，代码里不设分支。
     */
    @Select("""
            SELECT id AS user_id, username AS username, linux_do_id AS linux_do_id
            FROM "user"
            WHERE linux_do_id ~ '^[0-9]+$'
            """)
    List<EligibleUserRow> listEligibleUsers();

    /**
     * 全站现货在持数量。现货标的整体收益 =（卖出总额 − 买入总额 + 剩余持仓市值）÷ 买入总额，
     * 这条提供"剩余持仓"那一项；市值在 Java 侧乘当前价（价在 Redis，SQL 拿不到）。
     * <p>
     * 数量取 quantity + frozen_quantity：冻结的是挂单锁住的量，仍算这个人的持仓，
     * 与 AssetSnapshotServiceImpl:305 的口径一致。
     */
    @Select("""
            SELECT user_id AS user_id,
                   symbol  AS symbol,
                   quantity + COALESCE(frozen_quantity, 0) AS qty
            FROM crypto_position
            WHERE quantity + COALESCE(frozen_quantity, 0) > 0
            """)
    List<HeldPositionRow> listHeldPositions();
}
