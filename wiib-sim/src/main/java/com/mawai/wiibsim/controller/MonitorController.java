package com.mawai.wiibsim.controller;

import com.mawai.wiibcommon.monitor.JvmMetrics;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 进程监控（sim）。JVM 采集下沉到共享 {@link JvmMetrics}，三进程共用。
 * <p>sim 是 WS 网关，自身 JVM 直推 /topic/monitor/sim；feed/agent 无网关，发 Redis 由 WsBroadcastRelay
 * 中继到 /topic/monitor/{code}。前端轮播统一订 /topic/monitor/{sim,feed,quant}——quant 是 agent 的旧频道名，沿用。
 */
@Component
public class MonitorController {

    private final SimpMessagingTemplate ws;

    public MonitorController(SimpMessagingTemplate ws) {
        this.ws = ws;
    }

    @Scheduled(fixedRate = 5000)
    public void pushMonitor() {
        // 载荷强转 Object 锁定 convertAndSend(目的地, 载荷)：Spring 7 新增了
        // convertAndSend(载荷, 消息头Map) 重载，Map 型载荷会同时命中两者而编译不过
        ws.convertAndSend("/topic/monitor/sim", (Object) JvmMetrics.collectLite());
    }
}
