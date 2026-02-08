# WhatIfIBought

虚拟股票模拟交易平台 —— "如果当初买了会怎样"

用户通过 [LinuxDo](https://linux.do) OAuth 登录，使用虚拟资金在 AI 生成的行情中进行模拟股票/期权交易，附带小游戏。

线上地址: https://linuxdo.stockgame.icu

## 功能概览

**交易系统**
- 市价单即时成交（±2%滑点保护）、限价单挂单等待触发
- T+1 资金结算，0.05% 手续费
- 杠杆交易（借款买入、计息、爆仓清算）
- CALL/PUT 期权交易，Black-Scholes 定价，自动到期结算

**行情系统**
- AI 每日生成 20 只股票的分时行情（1440 个价格点）
- WebSocket(STOMP) 每 10 秒实时推送行情、资产变动、订单状态

**游戏与社交**
- 每日 Buff 抽奖（4 种稀有度：交易折扣 / 现金红包 / 赠送股票）
- 小游戏（积分可转为交易资金）
- 总资产排行榜

## 技术栈

| 层 | 技术 |
|---|------|
| 后端 | Java 21（虚拟线程）+ Spring Boot 3.4 + MyBatis-Plus 3.5 |
| 前端 | React 19 + TypeScript + Vite + TailwindCSS + Ant Design + ECharts |
| 数据库 | PostgreSQL |
| 缓存 | Redis（行情数据、会话、排行榜、牌局状态、分布式锁、限流） |
| 实时通信 | STOMP over WebSocket + Redis Pub/Sub（多实例广播） |
| 认证 | LinuxDo OAuth2 + Sa-Token（Redis 持久化会话） |
| 状态管理 | Zustand（仅持久化 token，用户数据按需拉取） |
| 部署 | Docker（eclipse-temurin:21-jre-alpine） |

## 项目结构

```
whatifibought/
├── wiib-common/    # 公共模块（实体、DTO、枚举、工具类、限流切面）
├── wiib-service/   # 业务服务（Spring Boot 主应用，全部后端逻辑）
└── wiib-web/       # 前端（React SPA）
```

## 架构设计

### AI 行情生成：GBM + Jump-Diffusion

AI 不直接生成价格序列，而是输出宏观参数，由数学模型生成微观走势：

```
AI(LLM) → { openPrice, mu, sigma }  →  带跳跃的几何布朗运动  →  1440个价格点
```

1. **AI 生成三个参数**：开盘价（昨收±2%）、日收益率 mu（-0.05~0.05）、日波动率 sigma（按行业区分稳定/波动）
2. **输入上下文**：股票基本面、公司信息、全局市场情绪（随机25-74）、个股情绪（随机5-94）、近10日涨跌趋势
3. **GBM 公式**：`price[i] = price[i-1] * exp((mu - 0.5σ²)dt + σ√dt·z)`，z 截断在 [-4, 4]
4. **跳跃叠加**：稳定行业 0-2 次、波动行业 0-5 次，幅度 ±2%~4%（上限±5%）
5. **每日 21:00 预生成次日数据，9:10 加载到 Redis**

这样 AI 负责"大方向"，GBM 负责"细节"，避免了 LLM 输出不稳定导致的异常价格。

### Redis 数据结构设计

| Key 模式 | 结构 | 用途 |
|---|---|---|
| `tick:{date}:{stockId}` | Hash（field=index, value=price） | 分时价格，O(1) 按 index 查询 |
| `stock:daily:{date}:{stockId}` | Hash（open/high/low/last/prevClose） | 当日 OHLC 汇总 |
| `kline:{stockId}` | Hash（field=date, value="o,h,l,c"） | K线日线缓存 |
| `stock:static:{stockId}` | Hash | 股票静态数据，启动预热 |
| `stock:ids:all` | Set | 全量股票 ID，用于遍历推送 |
| `bj:session:{userId}` | String（序列化对象） | 21点牌局快照，TTL 4h |
| `bj:pool:{date}` | String | 每日积分池余额（200万），TTL 24h |
| `limiter:{type}:{userId}` | Hash | 令牌桶限流状态 |
| `order:execute:{orderId}` | String（分布式锁） | 订单操作互斥，TTL 30s |

分时数据选用 Hash 而非 List，因为实时行情需按 index O(1) 单点查价。

### WebSocket 实时推送

三层传输链路，支持多实例部署：

```
定时任务(10s) → QuotePushService → Redis Pub/Sub → 各实例 → SimpMessagingTemplate → STOMP → 前端
```

- **行情频道** `ws:broadcast:stock`：全量股票广播，STOMP 自动按客户端订阅过滤
- **用户事件频道** `event:{type}:{userId}`：资产变动/持仓变化/订单状态，精准推送
- 虚拟线程并发 + Semaphore(5) 限流，时间对齐到 10 秒整点

### 订单撮合引擎

**市价单**：即时成交，从 Redis 读取最新价。买入扣余额加持仓，卖出创建 T+1 结算记录。

**限价单**三阶段状态机：

```
PENDING  →  TRIGGERED  →  FILLED
  ↓(每10s检测价格)  ↓(每10s批量执行)
```

三重并发防护：
1. **Redis 分布式锁**（30s）：订单级互斥
2. **数据库 CAS 乐观锁**：`casUpdateStatus(orderId, expected, new)`
3. **Semaphore 限流**：虚拟线程并发上限

事务与锁顺序：获取锁 → 开启事务 → 执行操作 → 提交事务 → 释放锁。成交后通过虚拟线程异步发布 Spring 事件触发 WebSocket 推送。

### 期权定价：Black-Scholes

手写实现（无第三方库依赖）：

```
d1 = (ln(S/K) + (r + 0.5σ²)T) / (σ√T)
d2 = d1 - σ√T
CALL = S·N(d1) - K·e^(-rT)·N(d2)
PUT  = K·e^(-rT)·N(-d2) - S·N(-d1)
```

- 无风险利率 3%，CDF 使用 Abramowitz-Stegun 近似（精度 ~1.5e-7）
- 每日生成 5 档行权价的期权链，年化波动率 = 日 sigma × √252

### 限流：分布式令牌桶

基于 Redis Lua 脚本保证原子性：

```lua
-- 补充令牌：tokens = min(capacity, tokens + elapsed * rate)
-- 消费令牌：tokens >= requested 则扣减返回1，否则返回0
```

通过 `@RateLimiter` 注解声明式使用，按用户维度限流。

### 定时任务时间线

```
21:00  AI生成次日行情 + 新闻 + 期权链
09:00  恢复破产用户
09:10  加载当日数据到Redis
09:25  启动行情推送(10s) / 限价单执行(10s) / 排行榜刷新(10min)
09:30  开盘
15:00  收盘，期权到期结算
16:00  清理过期限价单
17:00  杠杆计息 + 爆仓检查
```

冷启动自愈：`@PostConstruct` 检查当前时间，如在交易时段则自动补启遗漏的周期任务。

### 小游戏

纸牌游戏：Hit / Stand / Double / Split / Insurance / Forfeit。

- DB 管资金统计，Redis 管牌局过程态（序列化整个 Session 对象，TTL 4h）
- 每日积分池 200 万：用户赢则池减少，用户输则池回血，池空则不能开新局
- 积分可 1:1 转出为交易资金（仅超出初始值的部分，每日上限 10 万）
- Redis 分布式锁串行化同一用户所有操作

## 前端页面

| 路由 | 功能 |
|------|------|
| `/` | 首页仪表盘（涨跌榜、行情概览） |
| `/stocks` | 股票列表 |
| `/stock/:id` | 股票详情（分时图 + 交易面板） |
| `/stock/:id/kline` | 日K线 |
| `/portfolio` | 持仓与资产概览 |
| `/options` | 期权交易 |
| `/ranking` | 排行榜 |
| `/blackjack` | 21点小游戏 |
| `/admin` | 管理后台 |

## 本地开发

### 环境要求

- JDK 21
- Node.js 18+
- PostgreSQL
- Redis

### 配置文件

项目未提交 `application.yml` 配置文件，需自行创建：

**wiib-common/src/main/resources/application.yml**（可留空）

**wiib-service/src/main/resources/application.yml** 需配置以下内容：

```yaml
server:
  port: 8080

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/wiib
    username: your_username
    password: your_password
  data:
    redis:
      host: localhost
      port: 6379

# LinuxDo OAuth2
linuxdo:
  client-id: your_client_id
  client-secret: your_client_secret
  redirect-uri: http://localhost:3000/login

# AI 行情生成
ai:
  model:
    api-key: your_api_key
    api-url: your_api_url
    model-name: your_model_name

# Sa-Token
sa-token:
  token-name: wiib-token
  timeout: 604800
  is-concurrent: true
```

### 数据库初始化

创建 `wiib` 数据库，执行建表脚本和初始数据脚本（20 家虚拟公司 + 20 只股票）。

### 启动

```bash
# 后端
mvn clean package -DskipTests
java -jar wiib-service/target/wiib-service-*.jar

# 前端
cd wiib-web
npm install
npm run dev
# 默认端口 3000，API 代理到 localhost:8080
```

### Docker 部署

```bash
mvn clean package -DskipTests
docker compose up -d --build
```

需确保 PostgreSQL 和 Redis 可达，具体连接信息在 `docker-compose.yml` 中配置。

## 数据库表

| 表 | 说明 |
|---|------|
| user | 用户（余额、冻结余额、杠杆借款、破产状态） |
| company | 虚拟公司（20 家，覆盖各行业） |
| stock | 股票（静态数据，实时价格从 Redis 获取） |
| position | 持仓（用户-股票唯一约束，含冻结数量和平均成本） |
| orders | 订单（市价/限价，BUY/SELL） |
| price_tick_daily | 分时行情（每日 1440 个价格点，NUMERIC 数组） |
| news | AI 生成的股票新闻 |
| settlement | T+1 资金结算 |
| option_contract | 期权合约（CALL/PUT，行权价/到期时间/波动率） |
| option_position | 期权持仓 |
| option_order | 期权订单 |
| option_settlement | 期权结算记录 |
| user_buff | 每日 Buff |
| blackjack_account | 21 点积分账户 |

## License

MIT
