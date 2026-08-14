package com.mawai.wiibquant.agent.behavior;

import com.mawai.wiibquant.external.sim.SimInternalClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 行为分析的数据采集：并发拉 sim 的 10 个 {@code /internal/behavior} 端点（{@link SimInternalClient}），
 * quant 与 sim 编译解耦。
 * <p>这 10 段是固定的——每个端点的入参只有 userId、路径写死，模型在"查什么"上没有决策空间，
 * 所以不做成 @Tool 让它一轮轮来要，分析前一次性拉齐（见 {@link BehaviorAnalysisWorkflow}）。
 * <p>老 GBM 股市/期权已下线，bStock 代币化美股为现役维度。
 */
@Component
@RequiredArgsConstructor
public class BehaviorDataCollector {

    /**
     * 一段数据 = 一个 sim 端点。name/detail 会拼进 prompt 当段标题——模型只靠它认这段 JSON 是什么，
     * 文案沿用改造前 @Tool 的 description，给模型的语义信息不变。
     */
    private record Source(String endpoint, String name, String detail) {
    }

    private static final List<Source> SOURCES = List.of(
            new Source("user-profile", "用户基础信息", "余额、冻结余额、破产次数、注册时间"),
            new Source("portfolio-summary", "实时资产概览", "总资产、持仓市值、杠杆负债、盈亏，精确计算含实时价格"),
            new Source("asset-snapshots", "近30日资产快照", "每日总资产与各品类盈亏趋势"),
            new Source("crypto-stats", "加密货币交易统计", "买入总额、卖出总额、持仓数、杠杆使用情况"),
            new Source("bstock-stats", "bStock(代币化美股)交易统计", "持仓数、买入总额、卖出总额"),
            new Source("futures-stats", "合约交易统计",
                    "已实现盈亏、订单数、多空偏好、平均杠杆、止损率、爆仓次数，含分品类拆解(crypto加密/commodity大宗金油/tradfi美股ETF)"),
            new Source("prediction-stats", "Prediction统计", "参与次数、净盈亏、胜率、方向偏好"),
            new Source("blackjack-stats", "Blackjack统计", "总局数、净赢、净输、最大赢、当日已转出积分"),
            new Source("mines-stats", "Mines统计", "参与次数、净盈亏"),
            new Source("videopoker-stats", "Video Poker统计", "参与次数、净盈亏"));

    /** 采集到的一段：json 可能是错误 JSON，见 {@link #collect} */
    public record Section(String name, String detail, String json) {
    }

    private final SimInternalClient simClient;

    /**
     * 并发拉齐全部 10 段。单段失败不抛——{@link SimInternalClient#getJson} 失败返回错误 JSON，
     * 这里原样带走：一个端点挂了只该让模型知道这块没有数据，不该整份报告作废。
     * <p>10 个都是纯阻塞 HTTP（连接 1s / 读 5s），虚拟线程直接一段一根，总耗时按最慢的那段算。
     * <p>{@code onProgress} 会被多个线程回调，实现方自己保证线程安全。
     */
    public List<Section> collect(long userId, Consumer<String> onProgress) {
        AtomicInteger done = new AtomicInteger();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Section>> tasks = SOURCES.stream()
                    .map(src -> CompletableFuture.supplyAsync(() -> fetch(userId, src, done, onProgress), pool))
                    .toList();
            // 按提交顺序 join：prompt 里的段序恒等于 SOURCES 的顺序，不随网络快慢抖动
            return tasks.stream().map(CompletableFuture::join).toList();
        }
    }

    private Section fetch(long userId, Source src, AtomicInteger done, Consumer<String> onProgress) {
        String json = simClient.getJson("/internal/behavior/" + userId + "/" + src.endpoint());
        if (onProgress != null) {
            onProgress.accept("已采集 " + done.incrementAndGet() + "/" + SOURCES.size() + "：" + src.name());
        }
        return new Section(src.name(), src.detail(), json);
    }
}
