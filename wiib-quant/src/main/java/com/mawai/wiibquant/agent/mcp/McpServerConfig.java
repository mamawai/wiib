package com.mawai.wiibquant.agent.mcp;

import com.mawai.wiibquant.agent.toolkit.MarketToolkit;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP server 工具注册（P6）："一份能力多处消费"的对外一处——把只读市场测量工具暴露给
 * 任意 MCP 客户端（Claude Desktop / Cursor 等直连本服务 SSE 端点）。
 * 只注册 MarketToolkit 的只读工具；NewsToolkit（外部抓取成本）与 run_deep_analysis（贵操作，HITL 管辖）不对外。
 * 预测轨工具（vol_forecast/market_regime/scorecard）已随预测管线下线（2026-08：生产验证无前瞻信息）。
 */
@Configuration
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider quantMcpTools(MarketToolkit marketToolkit) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(marketToolkit)
                .build();
    }
}
