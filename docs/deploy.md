# 部署

这份文档从"从零把它跑起来"的视角讲部署：环境要求，以及克隆 → 建库 → 配置 → 构建后端 / 前端 → 启动这六步，本地一键与 Docker Compose 两条路都在里面。

## 环境要求

| 依赖 | 最低版本 | 说明 |
|---|---:|---|
| JDK | 25 | pom `<release>` 即 25，低版本编译不过 |
| Maven | 3.9+ | 后端构建 |
| Node.js | 20+ | 前端构建（Vite 7） |
| PostgreSQL | 14+ | 主数据库（三进程共享） |
| Redis | 6+ | 行情总线、锁、Pub/Sub、会话 |

## 1. 克隆项目

```bash
git clone https://github.com/mamawai/wtfibought.git
cd wtfibought
```

## 2. 初始化数据库

```bash
psql -U postgres -c "CREATE DATABASE wiib;"
psql -U postgres -d wiib -f sql/init.sql      # 业务 + 量化 + AI runtime（35 张表）
psql -U postgres -d wiib -f sql/bstock.sql    # bStock 代币化美股静态表 + 10 只种子
```

## 3. 后端配置

密钥与结构分离：`application.yml` 直接入库（只有 `${VAR}` 占位符），真实值只存在根目录 env 文件里。本地开发复制模板填值即可：

```bash
cp .env.example .env.local    # 填 PG_USER / PG_PASSWORD / INTERNAL_API_TOKEN（必填），其余可选
```

启动时按 `本机环境变量 > .env.local > yml 默认值` 解析；线上 Docker 部署同一文件命名为 `.env`（见第 6 节）。

要点：

- 共享库 / 总线：三进程指向同一 PostgreSQL `wiib` + 同一 Redis，读同一份 `.env.local`；`INTERNAL_API_TOKEN`（进程间 `/internal/**` 鉴权）无默认值、必填，不填三进程都起不来，用 `openssl rand -base64 24` 生成一份填进去，三进程读同一份 env，天然同值。
- LLM 配置不在 yml，分两处，看是谁在烧钱：
  - 平台位（behavior 行为分析、news-tagging 快讯打标）：DB 的 `ai_runtime_config` + `ai_model_assignment`，管理员进 Admin 页填 API Key + Base URL + 模型名（不含 `/v1`）并分配功能位，即时生效、无需重启。
  - 用户 BYOK：`user_llm_endpoint` 端点库（一人多条：协议 + URL + key + 模型 + 档位）+ `user_llm_binding` 用途绑定（对话主 / 轻、交易员；没绑的用途落到默认端点），全在 AI 页「模型配置」维护，交易员 / 复盘教练页只从下拉里选。key 一律 AES-GCM 加密入库，密钥来自 `WIIB_TRADER_KEY_SECRET`（base64 的 32 字节）；没配这个变量，用户保存 key 时会直接报错，不会以明文入库。baseUrl 过 SSRF 校验（含 `100.64.0.0/10` 这类云厂商内网段）。
- quant 必须关掉 Spring AI 的 OpenAI 自动装配（6 类全关，否则缺 api-key 拒绝启动）：

  ```yaml
  spring:
    ai:
      model: { chat: none, embedding: none, image: none, moderation: none, audio: { speech: none, transcription: none } }
  ```

- 策略实盘执行（配在 `wiib-agent` 的 yml，代码在 `wiib-quant` 库；仓库默认即三策略全启、跑 sim 轨）：

  ```yaml
  strategy:
    runtime:   { enabled: true, enabled-ids: FIBO,SQZMOM,TURTLE }
    execution: { enabled: true, target: sim,
                 symbols: BTCUSDT,ETHUSDT,DOGEUSDT,SOLUSDT,XRPUSDT,BNBUSDT }
  ```

  > 策略由 K 线收盘驱动：`wiib-feed` 的 `binance.symbols` 必须覆盖上面全部标的（缺谁谁永不触发）。
  > `symbols` 是三策略部署篮子的并集；TURTLE 的触价单是 quant 内存态，由 feed 的 futures tick 触发。

## 4. 构建后端

```bash
mvn clean package -DskipTests
# 产物：
#   wiib-feed/target/wiib-feed-0.0.1-SNAPSHOT.jar     :8081 数据上游
#   wiib-agent/target/wiib-agent-0.0.1-SNAPSHOT.jar   :8082 AI 交易员 + 策略
#   wiib-sim/target/wiib-sim-0.0.1-SNAPSHOT.jar       :8080 模拟交易（对外）
```

## 5. 构建前端

```bash
cd wiib-web
npm install
npm run build      # 开发：npm run dev（Vite 默认 3000，/api、/ws 代理到 sim :8080，
                   #        /api/ai、/api/testnet、/api/admin/ai-agent 代理到 quant :8082）
```

## 6. 启动

本地一键（Windows，构建 + 依次拉起 feed → sim → quant）：

```powershell
.\start-local.ps1              # 加 -SkipBuild 跳过构建；或直接双击 start-local.bat
```

或手动逐个起（须在仓库根目录执行，`.env.local` 按相对路径解析；IDEA 直接点各模块 Run 也可）：

```bash
java -jar wiib-feed/target/wiib-feed-0.0.1-SNAPSHOT.jar    # :8081 交易所 WS → Redis
java -jar wiib-agent/target/wiib-agent-0.0.1-SNAPSHOT.jar  # :8082 AI 交易员 + 策略
java -jar wiib-sim/target/wiib-sim-0.0.1-SNAPSHOT.jar      # :8080 模拟交易（对外，前端连它）
```

Docker Compose（三进程全编排；配置放服务器上的 `.env`，与 `.env.example` 同款变量）：

```bash
docker network create wiib-network
docker compose up -d --build
```

> 三服务端口均只绑 127.0.0.1（feed 8081 / sim 8080 / quant 宿主 18082），经 Nginx / Caddy 反代后对外只放 sim。
> Redis 主从 + 哨兵栈可选 `docker compose -f redis-compose.yml up -d`。
