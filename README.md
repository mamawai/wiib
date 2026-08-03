<div align="center">

<img src="wiib-web/public/icon-512.png" width="116" height="116" alt="WhatIfIBought" />

# WhatIfIBought

**如果当初买了会怎样**

代币化美股 · 加密现货 / 永续 · 大宗商品 · BTC 预测 · AI 量化研判
—— 一个用虚拟资金跑真实行情的交易实验平台

<br/>

[![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.0-6DB33F?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![langgraph4j](https://img.shields.io/badge/langgraph4j-1.8.20-F97316)](https://github.com/bsorrentino/langgraph4j)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.0-6DB33F?logo=spring&logoColor=white)](https://docs.spring.io/spring-ai/reference/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-4169E1?logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![Redis](https://img.shields.io/badge/Redis-DC382D?logo=redis&logoColor=white)](https://redis.io/)

[![React](https://img.shields.io/badge/React-19.2-61DAFB?logo=react&logoColor=black)](https://react.dev/)
[![TypeScript](https://img.shields.io/badge/TypeScript-5.9-3178C6?logo=typescript&logoColor=white)](https://www.typescriptlang.org/)
[![Vite](https://img.shields.io/badge/Vite-7.2-646CFF?logo=vite&logoColor=white)](https://vite.dev/)
[![TailwindCSS](https://img.shields.io/badge/Tailwind-4.1-06B6D4?logo=tailwindcss&logoColor=white)](https://tailwindcss.com/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

<br/>

**[线上体验 → wtfibought.com](https://wtfibought.com)**

[定位](#当前定位) · [功能](#功能概览) · [技术栈](#技术栈) · [架构](#系统架构) · [AI 量化](#ai-量化链路双轨研判工作台) · [数据链路](#实时数据链路) · [部署](#部署)

</div>

---

## 当前定位

用户通过 LinuxDo OAuth 登录，用虚拟资金体验代币化美股、加密现货 / 永续合约、大宗商品、BTC 5 分钟涨跌预测和小游戏，
并观察一套**"只预测不下单"**的 AI 量化研判。

后端按业务域拆成 **3 个独立进程** + `wiib-common` 共享层，经 **Redis 行情总线**和**共享 PostgreSQL** 协作，进程级故障隔离（一个崩不连累其他）。

| 进程 | 端口 | 职责 | 对外 |
|---|:---:|---|:---:|
| **wiib-feed** | `8081` | 统一接入 Binance（现货 + 永续 WS/REST）与 Polymarket，写入 Redis（Stream / KV / Pub-Sub），crypto 永续 5m K 线落库 | ✗ 上游进程 |
| **wiib-sim** | `8080` | 真人模拟交易 + 游戏 + BTC 预测，账本 = 自研模拟盘 DB，提供 REST / WebSocket | ✓ 前端连它 |
| **wiib-quant** | `8082` | AI 量化**只预测、不碰模拟盘账本**（结构化预测落库 + 记分卡）；FIBO / LIQFADE / SQZMOM / TURTLE 四策略由 5m K 线收盘驱动 | ✗ 内部 |

> 策略执行目标二选一（`strategy.execution.target=sim｜testnet`）：本平台模拟盘的独立量化账户 `quant-<策略ID>`，或 Binance USDT-M Testnet。

### 当前标的

| 品类 | 标的 |
|---|---|
| 加密现货 / 永续 | `BTC` `ETH` `DOGE` `SOL` `XRP` `BNB` |
| bStock 代币化美股（10） | NVDA · TSLA · MU · SNDK · CRCL · MSTR · AMD · SPCX · QQQ · SOXL（Binance 现货，如 `NVDABUSDT`） |
| 大宗商品 | 黄金 `XAUUSDT` · 原油 `CLUSDT`（TradFi 永续，无现货） |
| TradFi 合约 | 闪迪 `SNDK` · `SOXL` · SK海力士 `SKHYNIX` · 美光 `MU` · `KORU` · SpaceX `SPCX`（美股/ETF 永续，无现货，盈亏归股票桶） |
| 策略实盘篮子 | FIBO: `BTC/ETH` · LIQFADE: `BTC/ETH/DOGE` · SQZMOM: `SOL/DOGE/XRP` · TURTLE: `SOL/ETH/DOGE/BNB` |

---

## 功能概览

### 交易系统

- **bStock 代币化美股**：真实 Binance 现货行情（价与实时 5m 蜡烛来自 feed→Redis 实时流，K 线历史代理 REST），含公司基本面；下单挂靠统一保证金账户。
- **加密货币现货**：BTC/ETH/DOGE/SOL/XRP，接入 Binance 实时行情，市价 / 限价单，卖出即时到账。
- **永续合约**：全仓 / 逐仓双模式（全仓为主，整个余额钱包为仓位兜底，强平线随净值与全部仓位浮盈亏实时变化），多 / 空双向，1-150 倍分档杠杆（对齐 Binance 档位表），maker 0.02% / taker 0.04%，真实资金费率（每 8h 按 Binance premiumIndex 双向收付，拉取失败回退 0.01%），自动强平。
- **大宗商品**：黄金 / 原油 TradFi 永续。
- **保证金 / 计息 / 爆仓 / 破产恢复**：借款买入统一保证金账户，交易日 17:00 计息 + 爆仓检查，09:00 破产用户幂等恢复。
- **BTC 5 分钟涨跌预测**：接入 Polymarket 盘口 + Chainlink BTC 价格线，5 分钟窗口自动结算（结算基准 = Polymarket 开 / 收盘价，Chainlink 仅前端价格线展示），动态手续费。

### 资金账本

- **全量记账**：所有资金变动经 `@Ledger` **AOP 切面**落 `user_ledger` 流水表，`balance_after` 取自同条 `UPDATE ... RETURNING`（不重查，天然与真实扣减同事务同一致），44 种业务类型带中文语义标注（另有 `UNKNOWN` 兜底，切面漏标也不丢账）。
- **账单页**：游标翻页（`id` 倒序，服务端封顶 100/页）+ 按业务类型筛选，中文 label 只在 `LedgerBizType` 枚举里维护一处，前端不再抄一份映射。
- **合约仓位历史**：一行一笔完整仓位，展开看分批平仓明细（已平仓量 / 平仓均价 / 已实现盈亏从订单表聚合，仓位表那两列只是残值）。

### 策略实盘

| 策略 | 周期 | 逻辑 |
|---|:---:|---|
| **FIBO** | 5m | 斐波回撤限价挂单 |
| **LIQFADE** | 5m | 强平瀑布 fade 仅做多；瀑布 / premium 折价 / taker 卖压三签名至少中二，1h 时间出场 |
| **SQZMOM** | 4H | 压缩释放仅做空 |
| **TURTLE** | 4H | 通道突破 90 入 / 15 出，盘中触价入场，多空对称，2×ATR 灾难止损、不设固定止盈 |

- 全部由 5m K 线收盘驱动；执行 sim（每策略独立账户，与真人同规则、资金隔离）或 Binance Testnet。仓库默认四策略全启跑 sim 轨、仅白名单 symbol。
- **策略监控页**：四策略账户全景（余额 / 权益 / 盈亏 / 持仓 / 已平仓历史）、各策略 × 币种实时信号快照（通道位置 / 压缩计数 / 签名命中），管理员可手动整仓市价平；testnet 轨另有独立看板（总览 / 成交 / 日历格 / 净值曲线 / 成交质量）。

### AI 量化研判（双轨研判工作台）

> **卖点不是"预测准"，是"工程可信"** —— vol 预测 / vol-state 经 walk-forward 样本外统计验证有 skill，工具化落地 + 线上记分卡公开战绩。

- **定时轨**（标的 BTC / ETH）：每 5m 零 LLM 数值快照（vol 三腿 + regime + 脆弱度 + 信号面板）落库，作记分卡预测点；1h 定频 + 波动哨兵插队触发深研判（新闻 → Bull∥Bear → Judge）。
- **对话轨**（管理员专属）：SSE 流式工作台，结构化 router 调度 market / quant / news 三专家并行取数，深模型 summarizer 汇总作答；PostgresSaver 断点续聊 + 跨会话记忆 + HITL 确认闸。
- **MCP Server**：同一工具层暴露 6 个只读量化工具（`market_snapshot` / `option_iv` / `fragility` / `vol_forecast` / `market_regime` / `scorecard`），SSE 端点，Claude Desktop / Cursor 等任意 MCP 客户端可直连；新闻抓取与深研判等贵操作刻意不对外。
- **研究工具**：策略 K 线 / 组合回测引擎与 walk-forward 样本外评估（vol / regime / direction）REST API。
- 详见 [AI 量化链路](#ai-量化链路双轨研判工作台)。

### 行情与实时数据

- **Binance WS**：现货 miniTicker、永续 markPrice、永续 miniTicker、forceOrder（订全市场流白名单过滤）、aggTrade、depth20、K 线流（crypto 永续 5m 驱动策略 / 预测并落库；15m / 1h、大宗商品与 bStock 现货 5m 仅广播）。
- **Binance REST**：K 线、ticker、funding、OI、多空比、大户持仓、taker 买卖比、盘口。
- **断线韧性**：WS 断开自动切 REST 轮询保价不中断，重连后按离线区间高低价补触发错过的限价单 / 强平；K 线缺口 REST 回补；流健康注册表 + 管理端手动重试。
- **Deribit**：DVOL 与期权 book summary（供 quant 做 IV / vol 上下文，非用户交易）。
- **宏观 / 资金面**：farside ETF 资金流、稳定币流通量、IV / OI 分位、Fear & Greed 指数（供 quant 快照上下文）。
- **Polymarket**：BTC 预测 live-data、UP/DOWN CLOB 盘口。
- **WebSocket**：SockJS + STOMP，前端订阅行情、预测、量化信号等 topic。

### 社区与社交

- **排行榜**：分页 + 双维排序（总资产 / 交易盈利），只含有过成交的用户；每日 00:00 全员资产快照（30 天资产曲线）。
- **用户主页**：榜单行 + 当前持仓 + 成交明细 / 仓位历史切换，由本人的**公开开关**门控（关了则除本人外一律 403）。
- **全站成交页**：匿名流水，交易者只给稳定假名；接口刻意不开 `userId` 入口——开了就能枚举 userId 反查假名，匿名当场失效（反射守卫测试钉着）。
- **留言板**：全站唯一，根评论 + 子评论两层，赞踩只存计数（去重靠 Redis Set 不落记录表），管理员可删除 / 禁言（`user.muted_until` 到期自动解禁）。赞与回复产生通知，顶栏信封角标 + WebSocket 点对点推送，前端按「类型 + 评论」合并展示。
- **游戏**：每日 Buff 抽奖、21 点、Mines、Video Poker；用户行为分析 Agent（quant 经 internal API 读 sim 行为数据）。
- **自助重置账户**：清空全部交易与游戏数据回到初始资金，每周一次、需逐字输入用户名确认。清理先注销 Redis 触发索引再事务删表，失败则把索引装回去。

### 前端体验

- **精密终端**仪器风自研设计系统（`.pt-card` / `.num` / `.microlabel` / `.page-shell`），亮 / 暗双主题，无 UI 框架依赖。
- **专业 K 线**（lightweight-charts 自绘）：全屏模式（内嵌周期切换）；趋势线 / 水平线 / 斐波那契 / 文字标注画线工具（磁吸落点，按币种持久化）；MA / EMA / BOLL 主图叠加与 MACD / RSI 副图各自可开关；最新价 + 收盘倒计时合体轴标签；仓位参考线（入场 / 止盈 / 止损 / 强平价，多空色系区分、单仓位显隐）；历史成交 B/S 角标（同根 K 线聚合成 B³S² 上标，点击看逐笔成交价）。
- **首页驾驶舱**：总资产曲线（30 天快照 + 实时值）、今日盈亏、月度盈亏网格（点单日下钻五分类盈亏拆解与当日已平仓位）。
- **冷启动开屏动画**：网格溶解，PC / 移动分头编排，内联在 `index.html`（首屏零 JS 依赖）。
- **PWA**：manifest + Service Worker，standalone 观感与导航适配，不锁竖屏（横屏看 K 线）。

---

## 技术栈

<table>
<tr><th>层级</th><th>技术</th><th>版本 / 说明</th></tr>
<tr><td rowspan="2"><b>运行时</b></td><td>Java</td><td>21，启用 Virtual Threads</td></tr>
<tr><td>Spring Boot</td><td>4.1.0（Web 层 JSON 随之换代到 Jackson 3，包名 <code>tools.jackson</code>）</td></tr>
<tr><td rowspan="3"><b>AI</b></td><td>langgraph4j</td><td>1.8.20（core + spring-ai + agentexecutor + postgres-saver）</td></tr>
<tr><td>Spring AI</td><td>2.0.0（OpenAI Compatible + Responses API，思考档位可配，配置在 DB）</td></tr>
<tr><td>MCP Server</td><td>Spring AI MCP Server WebMVC / SSE 2.0.0</td></tr>
<tr><td rowspan="3"><b>数据</b></td><td>PostgreSQL</td><td>共享主库，建表见 <code>sql/init.sql</code>（29 张）+ <code>sql/bstock.sql</code></td></tr>
<tr><td>Redis + Caffeine</td><td>行情总线、分布式锁、ZSet 索引 + 本地热缓存</td></tr>
<tr><td>MyBatis-Plus</td><td>3.5.17（<code>spring-boot4-starter</code>）</td></tr>
<tr><td><b>认证</b></td><td>Sa-Token</td><td>1.45.0（LinuxDo OAuth + 账号密码 / 邀请码注册，开关控制）</td></tr>
<tr><td><b>观测</b></td><td>Actuator + Micrometer</td><td>暴露 health / metrics / prometheus；LLM 调用观测经 Spring AI <code>ObservationRegistry</code></td></tr>
<tr><td rowspan="5"><b>前端</b></td><td>React + TypeScript</td><td>19.2 / 5.9</td></tr>
<tr><td>Vite</td><td>7.2 + vite-plugin-pwa</td></tr>
<tr><td>UI</td><td>TailwindCSS 4.1 + lucide-react，「精密终端」自研组件，无 UI 框架依赖</td></tr>
<tr><td>图表</td><td>ECharts 6 + lightweight-charts 5.2</td></tr>
<tr><td>状态 / 路由 / 实时</td><td>Zustand 5 + React Router 7 + STOMP over SockJS</td></tr>
</table>

---

## 系统架构

```mermaid
flowchart TD
    UI["React Web<br/>bStock / Coin / Commodity / Portfolio / Prediction<br/>AI / Scorecard / Strategies / Testnet / ForceOrders<br/>Ledger / Trades / Ranking / Comments / Games / Me / Admin"]
    EXC["Binance / Polymarket / Deribit<br/>外部行情源"]
    FEED["wiib-feed :8081<br/>交易所 WS/REST 接入"]
    SIM["wiib-sim :8080<br/>真人模拟交易 + 游戏 + 预测<br/>账本 = 自研模拟盘"]
    QUANT["wiib-quant :8082<br/>AI 量化研判 + 策略执行"]
    DB[("PostgreSQL<br/>共享 business + quant + ai runtime")]
    REDIS[("Redis 行情总线<br/>Stream + KV + Pub/Sub<br/>+ lock + zset")]
    TESTNET["Binance USDT-M Testnet<br/>策略实盘账本(target=testnet)"]

    EXC -->|WS / REST| FEED
    FEED -->|写行情| REDIS
    FEED -->|K线落库| DB
    UI <-->|REST / STOMP| SIM
    REDIS -->|消费行情| SIM
    REDIS -->|消费行情| QUANT
    SIM --> DB
    QUANT --> DB
    SIM <-->|"internal API<br/>行为数据 + 量化交易(target=sim)"| QUANT
    QUANT -->|"策略下单(target=testnet)"| TESTNET
```

---

## AI 量化链路（双轨研判工作台）

定位：**vol 预测 / vol-state 有真 skill**，工具化 + 线上记分卡实时对账。

图引擎用 **langgraph4j 1.8.20**（spring-ai-alibaba 的上游）：确定性用图（`StateGraph`），认知用 Agent（`ReactAgent`）。两轨 + MCP Server 共用同一个工具层。

### 定时轨（StateGraph）

```mermaid
---
title: 定时轨：数值快照 + 深研判
---
flowchart TD
    __START__((start))
    __END__((stop))
    build_snapshot("build_snapshot<br/>零LLM: vol三腿(PIT档界)+regime+脆弱度+信号面板")
    persist_snapshot("persist_snapshot<br/>每5m落库,记分卡预测点")
    news_context("news_context<br/>新闻LLM浓缩")
    bull("bull 辩手")
    bear("bear 辩手")
    judge("judge<br/>研判叙事+情景分布+失效条件+无方向态")
    persist_analysis("persist_analysis")
    condition1{"gate: 1h定频/哨兵插队/手动"}
    __START__ --> build_snapshot
    build_snapshot --> persist_snapshot
    persist_snapshot -.-> condition1
    condition1 -.->|deep| news_context
    condition1 -.->|end| __END__
    news_context --> bull
    news_context --> bear
    bull --> judge
    bear --> judge
    judge --> persist_analysis
    persist_analysis --> __END__
```

- 标的 BTC / ETH；快照段每 5m 零 LLM；Bull∥Bear 走同源多边 fan-out / fan-in（框架内部建 `ParallelNode`）。
- 深研判每轮 4 次 LLM 调用（新闻 + Bull + Bear + Judge），1h 定频基线 + 哨兵插队（冷却）——约 200 调用/天。
- state 只传标量与 JSON 字符串（框架 `deepCopy` 会破坏 record），重对象走 `MarketDataService` 缓存共享；任一 LLM 步失败只缺席本次研判，快照时序不受影响。
- 验证闭环：5m K 线收盘事件驱动即时对账到期预测点（每小时 cron 仅断流兜底；QLIKE vs naive 基准 + vol-state 命中，PIT 档界随快照入库）；叙事轨另按 Judge 三情景概率记 Brier（vs 均匀基线）与情景命中。`/quant/scorecard` 出战绩。

### 对话轨（结构化路由 + 三专家并行）

```mermaid
flowchart LR
    S((start))
    R{"router 浅模型<br/>route 工具给出去向"}
    D["dispatch"]
    M["market_agent<br/>行情/持仓/清算/脆弱度"]
    Q["quant_agent<br/>vol预测/regime/战绩"]
    N["news_agent<br/>BlockBeats 快讯(预取)"]
    J["join"]
    SUM["summarizer<br/>深模型 · 深研判工具 · 流式作答"]
    E((end))
    S --> R
    R -->|dispatch| D
    R -->|FINISH| SUM
    D --> M
    D --> Q
    D --> N
    M --> J
    Q --> J
    N --> J
    J -.->|回环再判| R
    SUM --> E
```

`ChatWorkbenchController` SSE 流式（调度过程可视化）。横切：PostgresSaver 断点续聊、跨会话长期记忆（规则化写入 `workbench_memory`，不烧 LLM）、`run_deep_analysis` 贵操作 HITL 确认闸、调用限额 + 历史摘要压缩控预算。

实现要点：

- **路由**：模型调 `route` 工具（function calling）给出去向，结果只写 state、不进 messages；条件边只读这个结构化值。
- **并行**：`dispatch` → 三专家 → `join` 是静态三条同源边（框架内部建 `ParallelNode`）；未被派发的专家节点直接返回空，不触发模型调用。
- **收敛**：同一专家一轮提问内只派一次，另设 3 轮派发上限。
- **韧性**：自研 `ResilientChatService` 装配进 `ReactAgent.ChatService`，对图与节点透明。阻塞路径只切兜底模型（重试归模型层），流式路径带退避重试 + 无缝切兜底，仅在尚未吐帧时重订阅。
- **专家取数**：market / quant 首轮强制调工具（`tool_choice=required`），保证数据来自本系统而非模型内置搜索；news 走无参工具预取，快讯随消息直接喂入。
- **新闻双源**：`news_agent` 出 BlockBeats 清单，summarizer 用联网搜索补充并合并，独有条目带源标签、同一事件合并标注。

同一工具层的第三个消费方：**MCP Server**（SSE 端点）——Claude Desktop 等任意 MCP 客户端可直连调用只读量化工具。

---

## 实时数据链路

### Binance

```text
wiib-feed（上游进程）  交易所 WS → Redis
  -> spot miniTicker        -> Redis 价格 KV + Pub/Sub(feed:price)
  -> futures markPrice@1s   -> Redis mark/指数价 KV + Pub/Sub(feed:price)
  -> forceOrder(全市场流)    -> 白名单过滤 -> force_order 表(DB, 天然跨进程)
  -> aggTrade               -> Redis Stream(market:orderflow:<sym>, quant 读侧聚合)
  -> depth20@100ms          -> Redis KV(DepthStreamCache)
  -> K 线收盘                -> Redis Stream(stream:kline:closed) + taker买量5m桶 KV + crypto 5m 落库
  -> WS 断线                 -> REST 轮询兜底保价, 重连后按区间高低价补触发限价/强平

wiib-sim / wiib-quant（消费进程）  从 Redis 消费 feed 写入的行情
  sim:   撮合/强平 + 预测回合消费
  quant: K线驱动预测/策略 + 波动哨兵 + LIQFADE 拉 premium/taker KV
```

sim 订阅 `feed:price` 后做撮合（feed 本身不撮合）：价格更新触发现货限价单、永续强平、止损、止盈检查。

### Polymarket BTC 预测

```text
Polymarket live-data -> Chainlink BTC price -> /topic/prediction/price（展示价格线）
Polymarket CLOB      -> UP/DOWN bid/ask     -> /topic/prediction/market
5min window rotation -> 回合事件(lock/create/settle)走 Redis Stream 保 FIFO
                     -> sim 锁上轮 -> 拉 Polymarket 开/收盘价 -> 结算下注
```

动态手续费：`effectiveRate = 0.25 * (p * (1 - p))^2`，clamp 0.1% ~ 2%。

---

## 项目结构

后端 **4 个 Maven module / 3 个独立进程**（+ 共享层），经 Redis 行情总线和共享 PostgreSQL 协作。

```text
whatifibought/                        # Maven 多 module 聚合 reactor
├── pom.xml
├── .env.example                      # 环境配置模板（唯一需手工填值的文件，复制为 .env.local / .env）
├── start-local.ps1 / .bat            # 本地一键启动三服务（bat 为双击入口，转调 ps1）
├── docker-compose.yml                # 三进程编排（无私有值，配置全在 .env）
├── redis-compose.yml                 # Redis 主从 + 哨兵栈（可选）
├── sql/                              # init.sql（29 表）+ bstock.sql（bStock 静态表 + 种子）
│
├── wiib-common/                      # 共享层：被 feed/quant/sim 共同依赖，三者互不直接依赖
│   └── market/ broadcast/ cache/ aspect/ mapper/ entity/ dto/ enums/ util/ ...
│                                     # 行情通道 / Depth·OrderFlow 缓存 / BinanceRestClient
│                                     # / KlineHistoryStore / ForceOrder mapper / InternalApiFilter
│
├── wiib-feed/                        # ① 数据流上游进程（:8081）
│   └── BinanceWsClient / PolymarketWsClient / KlineStreamCache / health(内部流健康+重试)
│
├── wiib-quant/                       # ② 量化 + 策略研究进程（:8082，只分析不碰模拟盘账本）
│   ├── agent/
│   │   ├── quant/                    # 定时轨 StateGraph（快照 + 深研判）+ 节点/因子/信号
│   │   ├── analysis/                 # 深研判持久化 / 记分卡 / vol + 叙事验证
│   │   ├── research/                 # 回测 / 因子 / 预测 / 样本外评估（vol/regime/direction）
│   │   ├── toolkit/                  # 统一工具层（定时轨 / 对话轨 / MCP 三处共用）
│   │   ├── chat/                     # 对话轨：router + 三专家并行 + summarizer + checkpoint
│   │   ├── strategy/                 # FIBO/LIQFADE/SQZMOM/TURTLE + 回测引擎
│   │   │                             # + 执行层(testnet|sim) + 账户监控
│   │   ├── llm/                      # ResilientChatService / Responses API / 摘要 / 调用限额
│   │   └── mcp/ behavior/ binance/ external/ ...
│   │                                 # MCP server / 行为 agent / Testnet 客户端
│   │                                 # / ETF 流爬取 / SimInternalClient
│   └── controller/ task/ mapper/ config/   # ResearchEval/Strategy/Testnet/Backtest... / 调度 / Deribit
│
├── wiib-sim/                         # ③ 真人模拟交易进程（:8080，账本=自研模拟盘 DB，对外）
│   ├── ledger/                       # 资金记账切面：@Ledger + LedgerAspect + 行映射
│   └── controller/ service/ mapper/ config/ task/
│                                     # 交易(bStock/crypto/futures) / 游戏 / 预测 / 结算 / WS 网关
│                                     # + 账单·排行·全站成交·仓位历史
│                                     # + BehaviorDataController（internal API 供 quant 调）
│
└── wiib-web/                         # React 前端（精密终端风）
    └── src/                          # App.tsx / pages/ components/ hooks/ stores/ api/
```

---

## 并发与一致性

| 机制 | 用途 |
|---|---|
| Virtual Threads | WS 消息处理、量化采集、调度任务、异步广播 |
| Redis 分布式锁 | 订单、仓位、用户、游戏操作互斥 |
| 数据库 CAS | 订单、预测回合、结算状态机 |
| `UPDATE ... RETURNING` | 资金变动与变动后余额同条 SQL 取回，账本不重查、不产生读写间隙 |
| Redis ZSet | 限价单、强平价、止损、止盈触发索引 |
| Redis Pub/Sub | 多实例 WebSocket 广播 |
| Caffeine + Redis | 本地热缓存 + L2 分布式缓存 |

---

## 部署

### 环境要求

| 依赖 | 最低版本 | 说明 |
|---|---:|---|
| JDK | 21 | 需要 Virtual Threads |
| Maven | 3.9+ | 后端构建 |
| Node.js | 20+ | 前端构建（Vite 7） |
| PostgreSQL | 14+ | 主数据库（三进程共享） |
| Redis | 6+ | 行情总线、锁、Pub/Sub、会话 |

### 1. 克隆项目

```bash
git clone https://github.com/mamawai/wtfibought.git
cd wtfibought
```

### 2. 初始化数据库

```bash
psql -U postgres -c "CREATE DATABASE wiib;"
psql -U postgres -d wiib -f sql/init.sql      # 业务 + 量化 + AI runtime（29 张表）
psql -U postgres -d wiib -f sql/bstock.sql    # bStock 代币化美股静态表 + 10 只种子
```

### 3. 后端配置

密钥与结构分离：`application.yml` 直接入库（只有 `${VAR}` 占位符），真实值只存在根目录 env 文件里。本地开发复制模板填值即可：

```bash
cp .env.example .env.local    # 填 PG_USER / PG_PASSWORD（必填），其余可选
```

启动时按 `本机环境变量 > .env.local > yml 默认值` 解析；线上 Docker 部署同一文件命名为 `.env`（见第 6 节）。

要点：

- **共享库 / 总线**：三进程指向同一 PostgreSQL `wiib` + 同一 Redis，读同一份 `.env.local`；`INTERNAL_API_TOKEN` 天然一致（进程间 `/internal/**` 鉴权），不填走统一默认值。
- **LLM 配置不在 yml**：唯一来源是 DB（`ai_runtime_config` + `ai_model_assignment`）。启动后用管理员账号进 Admin 页填 LLM（API Key + Base URL + 模型名，Base URL 不含 `/v1`）并给各功能位分配，即时生效、无需重启。
- **quant 必须关掉 Spring AI 的 OpenAI 自动装配**（6 类全关，否则缺 api-key 拒绝启动）：

  ```yaml
  spring:
    ai:
      model: { chat: none, embedding: none, image: none, moderation: none, audio: { speech: none, transcription: none } }
  ```

- **策略实盘执行**（`wiib-quant`，仓库默认即四策略全启、跑 sim 轨）：

  ```yaml
  strategy:
    runtime:   { enabled: true, enabled-ids: FIBO,LIQFADE,SQZMOM,TURTLE }
    execution: { enabled: true, target: sim,
                 symbols: BTCUSDT,ETHUSDT,DOGEUSDT,SOLUSDT,XRPUSDT,BNBUSDT }
  ```

  > 策略由 K 线收盘驱动：`wiib-feed` 的 `binance.symbols` 必须覆盖上面全部标的（缺谁谁永不触发）。
  > `symbols` 是四策略部署篮子的并集；TURTLE 的触价单是 quant 内存态，由 feed 的 futures tick 触发。

### 4. 构建后端

```bash
mvn clean package -DskipTests
# 产物：
#   wiib-feed/target/wiib-feed-0.0.1-SNAPSHOT.jar     :8081 数据上游
#   wiib-quant/target/wiib-quant-0.0.1-SNAPSHOT.jar   :8082 量化策略
#   wiib-sim/target/wiib-sim-0.0.1-SNAPSHOT.jar       :8080 模拟交易（对外）
```

### 5. 构建前端

```bash
cd wiib-web
npm install
npm run build      # 开发：npm run dev（Vite 默认 3000，/api、/ws 代理到 sim :8080，
                   #        /api/ai、/api/testnet、/api/admin/ai-agent 代理到 quant :8082）
```

### 6. 启动

本地一键（Windows，构建 + 依次拉起 feed → sim → quant）：

```powershell
.\start-local.ps1              # 加 -SkipBuild 跳过构建；或直接双击 start-local.bat
```

或手动逐个起（须在仓库根目录执行，`.env.local` 按相对路径解析；IDEA 直接点各模块 Run 也可）：

```bash
java -jar wiib-feed/target/wiib-feed-0.0.1-SNAPSHOT.jar    # :8081 交易所 WS → Redis
java -jar wiib-quant/target/wiib-quant-0.0.1-SNAPSHOT.jar  # :8082 量化研判 + 策略
java -jar wiib-sim/target/wiib-sim-0.0.1-SNAPSHOT.jar      # :8080 模拟交易（对外，前端连它）
```

Docker Compose（三进程全编排；配置放服务器上的 `.env`，与 `.env.example` 同款变量）：

```bash
docker network create wiib-network
docker compose up -d --build
```

> 三服务端口均只绑 127.0.0.1（feed 8081 / sim 8080 / quant 宿主 18082），经 Nginx / Caddy 反代后对外只放 sim。
> Redis 主从 + 哨兵栈可选 `docker compose -f redis-compose.yml up -d`。

---

## 致谢

<div align="center">

<a href="https://linux.do/"><img src="docs/assets/linuxdo.svg" width="48" alt="LinuxDo" /></a>

感谢 [**LinuxDo**](https://linux.do/) —— 本站登录体系基于 LinuxDo OAuth（Connect），项目的灵感与早期用户也都来自佬友们。

*真诚、友善、团结、专业，共建你我引以为荣之社区。*

</div>

---

<div align="center">

[MIT License](LICENSE) · 所有数据均为模拟，仅供娱乐，不构成投资建议

</div>
