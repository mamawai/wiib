package com.mawai.wiibsim.mapper;

import com.mawai.wiibsim.dto.PublicTradeRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 全站成交时间线：现货单与合约单 UNION 成一条流。
 * <p>
 * 【为什么合并而不是两个 tab 各查各的】首页"最新成交"本来就是两者混排的一条时间线，
 * 点"更多"进去理应还是同一条。两张表分别分页再在前端归并，会在页边界处漏记录
 * （各取第 2 页，合起来根本不是全局第 2 页）。
 * <p>
 * 【不带 userId 入参，是故意的】开了就等于"按用户筛全站记录"，枚举 userId 就能反查假名，
 * 匿名当场失效。按人查那条路在 RankingController 下，走隐私开关门控。
 */
@Mapper
public interface PublicTradeMapper {

    // 三条时间线查询的 SQL 在 resources/mapper/PublicTradeMapper.xml：
    // 动态筛选（symbol/kind）用 <if> 拼接，公共 UNION 片段用 <sql>+<include> 单源复用——

    /**
     * 分页取一页。
     * <p>
     * ORDER BY 带 (kind, trade_id) 兜底不是凑数：同一毫秒能落多条（一次开仓可能同时写现货和合约侧），
     * 只按 created_at 排，两页之间的相对顺序由 PG 随便定，翻页会重复或漏行。
     * trade_id 单独也不够——两张表的 id 各自从 1 开始，必然撞号，得加 kind 才唯一。
     */
    List<PublicTradeRow> selectPage(@Param("symbol") String symbol,
                                    @Param("kind") String kind,
                                    @Param("limit") int limit,
                                    @Param("offset") int offset);

    /** 总条数，给前端算总页数。筛选条件必须与 selectPage 保持一致 */
    long countAll(@Param("symbol") String symbol, @Param("kind") String kind);

    /**
     * 某个用户的成交时间线（排行榜用户详情页用）。
     * <p>
     * 这条<b>不是</b>给全站匿名页用的：它按人筛，调用方必须先过隐私开关门控。
     */
    List<PublicTradeRow> selectPageByUser(@Param("userId") Long userId,
                                          @Param("limit") int limit,
                                          @Param("offset") int offset);

    /** 某用户的成交总数，配 selectPageByUser 用；也用来便宜地判"这人到底交易过没有" */
    @Select("SELECT (SELECT COUNT(*) FROM crypto_order WHERE user_id = #{userId} AND status = 'FILLED')"
            + " + (SELECT COUNT(*) FROM futures_order WHERE user_id = #{userId} AND status = 'FILLED')")
    long countByUser(@Param("userId") Long userId);

    /**
     * 有过成交单的用户ID。排行榜拿它过滤——从没交易过的人不入榜。
     * <p>
     * UNION 而不是 UNION ALL：两边都成交过的人只该出现一次。
     * 只认 FILLED：挂着没成交的限价单不算"交易过"，撤了单就啥都没发生。
     */
    @Select("""
            SELECT user_id FROM crypto_order WHERE status = 'FILLED'
            UNION
            SELECT user_id FROM futures_order WHERE status = 'FILLED'
            """)
    List<Long> selectTradedUserIds();
}
