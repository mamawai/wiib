package com.mawai.wiibquant.strategy.backtest.task;

import com.mawai.wiibquant.strategy.liq.LiqFadeParams;
import com.mawai.wiibquant.strategy.liq.LiqSideData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;

/**
 * LiqFade 回测 side data 装载（LiqFadeStrategyDbRun.loadSideData 的 main 源集移植，SQL 原样）：
 * taker = 完整 5m 桶买量和（缺 1m 的残桶丢弃 → NaN 保守）；premium = 每 5m 桶末采样。
 * 一个 LIQFADE 任务加载一次全历史（~60 万桶，秒级），任务结束随 Loaded 一起丢弃。
 */
@Component
@RequiredArgsConstructor
public class DbLiqSideData {

    private static final long BUCKET_MS = 300_000L;

    private final DataSource dataSource;

    /** 数组化只读快照，binarySearch 查询；语义与 DbRun 的 DbSide 完全一致。 */
    public static final class Loaded implements LiqSideData {
        final long[] takerT;
        final double[] takerV;
        final long[] premT;      // 1m 采样 openTime
        final double[] premV;
        final long staleMaxMs = LiqFadeParams.defaults().premStaleMaxMs();

        Loaded(long[] takerT, double[] takerV, long[] premT, double[] premV) {
            this.takerT = takerT;
            this.takerV = takerV;
            this.premT = premT;
            this.premV = premV;
        }

        @Override
        public double takerBuy(String symbol, long bucketOpenMs) {
            int i = Arrays.binarySearch(takerT, bucketOpenMs);
            return i >= 0 ? takerV[i] : Double.NaN;
        }

        @Override
        public double premiumAt(String symbol, long atMs) {
            // 可见性: 1m 样本在 openTime+59_999 收盘, ≤atMs 才可见; 超时效(staleMax)按缺数据
            int i = Arrays.binarySearch(premT, atMs - 59_999);
            if (i < 0) i = -i - 2;
            if (i < 0) return Double.NaN;
            long visibleAt = premT[i] + 59_999;
            return atMs - visibleAt > staleMaxMs ? Double.NaN : premV[i];
        }

        /** [fromMs, toMs) 内完整 taker 桶覆盖率；<60% 说明该窗口侧数据没回填，跑了也全是 NaN 不触发。 */
        public double takerCoverage(long fromMs, long toMs) {
            long expected = Math.max(1, (toMs - fromMs) / BUCKET_MS);
            int lo = lowerBound(takerT, fromMs);
            int hi = lowerBound(takerT, toMs);
            return (double) (hi - lo) / expected;
        }

        private static int lowerBound(long[] a, long key) {
            int i = Arrays.binarySearch(a, key);
            return i >= 0 ? i : -i - 1;
        }
    }

    public Loaded load(String symbol) {
        long[] tt = new long[600_000];
        double[] tv = new double[600_000];
        int tn = 0;
        long[] pt = new long[600_000];
        double[] pv = new double[600_000];
        int pn = 0;
        String takerSql = """
                SELECT (open_time/300000)*300000 AS b, sum(taker_buy_volume) AS s
                FROM taker_flow_1m WHERE symbol=?
                GROUP BY b HAVING count(*)=5 ORDER BY b
                """;
        String premSql = """
                SELECT DISTINCT ON (open_time/300000) open_time, close
                FROM premium_index_1m WHERE symbol=?
                ORDER BY open_time/300000, open_time DESC
                """;
        try (Connection con = dataSource.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement(takerSql)) {
                ps.setString(1, symbol);
                ps.setFetchSize(20_000);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next() && tn < tt.length) {
                        tt[tn] = rs.getLong(1);
                        tv[tn] = rs.getDouble(2);
                        tn++;
                    }
                }
            }
            try (PreparedStatement ps = con.prepareStatement(premSql)) {
                ps.setString(1, symbol);
                ps.setFetchSize(20_000);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next() && pn < pt.length) {
                        pt[pn] = rs.getLong(1);
                        pv[pn] = rs.getDouble(2);
                        pn++;
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("LIQ side data 加载失败: " + e.getMessage(), e);
        }
        return new Loaded(Arrays.copyOf(tt, tn), Arrays.copyOf(tv, tn),
                Arrays.copyOf(pt, pn), Arrays.copyOf(pv, pn));
    }
}
