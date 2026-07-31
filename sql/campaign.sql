-- ============================================================
-- LDC 瓜分活动（五维交易赛）：活动定义 / 签到 / 多空投票 / 结算发放
-- 与业务表零耦合：活动只读业务表，只写本文件这四张。活动结束整包移除，三步：
--   ① 删表：DROP TABLE IF EXISTS campaign_reward, campaign_vote, campaign_checkin, campaign;
--   ② 删代码（整目录/整文件）：
--        wiib-sim/src/main/java/com/mawai/wiibsim/campaign/
--        wiib-sim/src/test/java/com/mawai/wiibsim/campaign/   ← 别漏，留着编译不过
--        wiib-web/src/pages/Campaign.tsx
--        sql/campaign.sql（本文件）
--   ③ 拆挂载点（活动往外伸出去的就这几处，别处一行没有）：
--        wiib-sim/src/main/resources/application.yml 的 ldc: 段
--        .env.example 的 LDC_* 几行
--        wiib-web/src/ 六个文件：api/index.ts、types/index.ts、App.tsx、
--                                components/Layout.tsx、pages/Me.tsx、pages/Login.tsx
--   做完这三步业务代码无感。
-- 落库（psql 不在宿主 PATH 上，走容器）：
--   docker exec -i postgres-db psql -U mawai -d wiib -f - < sql/campaign.sql
-- IF NOT EXISTS + ON CONFLICT 幂等，整个文件可重跑
-- ============================================================

-- ============ 1. 活动定义 ============
-- 做成表而非 yml：改时间/奖池不用重启进程
CREATE TABLE IF NOT EXISTS campaign (
    id         BIGSERIAL PRIMARY KEY,
    code       VARCHAR(64)   NOT NULL UNIQUE,
    name       VARCHAR(128)  NOT NULL,
    start_at   TIMESTAMP     NOT NULL,
    end_at     TIMESTAMP     NOT NULL,
    prize_pool NUMERIC(18,4) NOT NULL,
    status     VARCHAR(16)   NOT NULL,
    created_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE  campaign            IS 'LDC 瓜分活动定义';
COMMENT ON COLUMN campaign.code       IS '活动码，出金单号 out_trade_no 的组成部分，定了不能改';
COMMENT ON COLUMN campaign.start_at   IS '活动开始时刻（含）';
COMMENT ON COLUMN campaign.end_at     IS '活动结束时刻（不含）';
COMMENT ON COLUMN campaign.prize_pool IS '奖池 LDC 总额';
COMMENT ON COLUMN campaign.status     IS '状态：RUNNING 进行中 / SETTLING 结算中 / DONE 已结算';

-- ============ 2. 每日签到 ============
-- 连续天数由本表计算，不落冗余列：冗余列会与真值漂移，而这张表一天一行，算起来不贵
CREATE TABLE IF NOT EXISTS campaign_checkin (
    id           BIGSERIAL PRIMARY KEY,
    campaign_id  BIGINT    NOT NULL,
    user_id      BIGINT    NOT NULL,
    checkin_date DATE      NOT NULL,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_campaign_checkin UNIQUE (campaign_id, user_id, checkin_date)
);

COMMENT ON TABLE  campaign_checkin              IS '活动每日签到（唯一索引保证一天一次）';
COMMENT ON COLUMN campaign_checkin.checkin_date IS '签到日（服务器本地日，与前端展示同时区）';

CREATE INDEX IF NOT EXISTS idx_campaign_checkin_user
    ON campaign_checkin (campaign_id, user_id, checkin_date);

-- ============ 3. 每日多空投票 ============
-- 唯一索引 (campaign,user,date,symbol) 即"多空二选一"：选了多就插不进空
CREATE TABLE IF NOT EXISTS campaign_vote (
    id          BIGSERIAL PRIMARY KEY,
    campaign_id BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    vote_date   DATE         NOT NULL,
    symbol      VARCHAR(20)  NOT NULL,
    direction   VARCHAR(8)   NOT NULL,
    result      VARCHAR(8),
    score       NUMERIC(8,2),
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_campaign_vote UNIQUE (campaign_id, user_id, vote_date, symbol)
);

COMMENT ON TABLE  campaign_vote           IS '活动每日多空投票（BTC + 黄金）';
COMMENT ON COLUMN campaign_vote.vote_date IS 'UTC 交易日，按该日日线收盘 vs 前日收盘结算';
COMMENT ON COLUMN campaign_vote.symbol    IS '标的：BTCUSDT / XAUUSDT';
COMMENT ON COLUMN campaign_vote.direction IS '方向：UP 看涨 / DOWN 看跌';
COMMENT ON COLUMN campaign_vote.result    IS '结果：WIN / LOSE / DEFERRED(平盘顺延，不计分不计票)；NULL=未结算';
COMMENT ON COLUMN campaign_vote.score     IS '结算后回填的该票得分；DEFERRED 与 LOSE 为 0';

CREATE INDEX IF NOT EXISTS idx_campaign_vote_date
    ON campaign_vote (campaign_id, vote_date, symbol);
CREATE INDEX IF NOT EXISTS idx_campaign_vote_user
    ON campaign_vote (campaign_id, user_id);

-- ============ 4. 结算结果与发放状态 ============
-- 三项分数分开存：出争议时能直接回答"这人为什么是这个分"，不必重算
CREATE TABLE IF NOT EXISTS campaign_reward (
    id           BIGSERIAL PRIMARY KEY,
    campaign_id  BIGINT         NOT NULL,
    user_id      BIGINT         NOT NULL,
    linux_do_id  VARCHAR(64)    NOT NULL,
    username     VARCHAR(64)    NOT NULL,
    trade_score  INT            NOT NULL,
    daily_score  INT            NOT NULL,
    vote_score   NUMERIC(8,2)   NOT NULL,
    penalty      INT            NOT NULL,
    final_score  NUMERIC(10,2)  NOT NULL,
    ldc_amount   NUMERIC(18,4)  NOT NULL,
    status       VARCHAR(16)    NOT NULL,
    out_trade_no VARCHAR(128)   NOT NULL UNIQUE,
    external_ref VARCHAR(128),
    error_msg    TEXT,
    created_at   TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_campaign_reward UNIQUE (campaign_id, user_id)
);

COMMENT ON TABLE  campaign_reward              IS '活动结算结果与 LDC 发放状态';
COMMENT ON COLUMN campaign_reward.linux_do_id  IS '领取时二次 OAuth 拿到的最新 LinuxDo 数字 ID，不取 user 表存量值';
COMMENT ON COLUMN campaign_reward.username     IS '同上，分发接口拿它做二次校验';
COMMENT ON COLUMN campaign_reward.penalty      IS '强平扣分，负数';
COMMENT ON COLUMN campaign_reward.final_score  IS 'max(0, trade+daily+vote+penalty)；带小数因投票是均分制';
-- status 的运维说明用 $$ 引号：正文里带单引号的 SQL 要能从本文件直接复制去执行，
-- 用 '...' 就得把里面每个单引号写成两个，粘出来是语法错的
COMMENT ON COLUMN campaign_reward.status       IS $$PENDING 待领取 / CLAIMED 已授权待发 / SUCCESS 已到账 / FAILED 发放失败。
【卡在 CLAIMED 怎么救】发放最坏耗时约 2 分钟，其间进程重启/发版，或收尾的 markSuccess/markFailed 失败，行会永远停在 CLAIMED，用户只看得到"上一次领取正在处理中"且无法自愈。手工重置：
    UPDATE campaign_reward SET status='FAILED', error_msg='人工重置：上次领取中断' WHERE id=? AND status='CLAIMED';
FAILED 可重领，且 out_trade_no 不变，那次中断若其实已发成功，重领会撞唯一索引被判 SUCCESS，绝不会重复付款。
【重置完还得过两道闸，否则用户点下去仍是死路】claim() 在看状态之前先判两件事：① 活动必须还是 SETTLING（翻成 DONE 就报"活动尚未结算，暂不可领取"）；② created_at + ldc.claim-days（默认 7 天）不能过（过了报"领取期限已过，请联系管理员"，第 8 天才重置必撞这句，得先把 ldc.claim-days 调大或改 created_at）。重置前先 SELECT 一眼这两个值：
    SELECT r.status, r.created_at, c.status FROM campaign_reward r JOIN campaign c ON c.id = r.campaign_id WHERE r.id = ?;
切记别另起新单号补发，那才是真会双倍付款的操作。$$;
COMMENT ON COLUMN campaign_reward.out_trade_no IS $$WIIB_{campaignCode}_{userId}，固定可重算，整套幂等的基石。
人工补发必须原样复用本列的值：换新单号会绕过服务端唯一索引，而 FAILED 里混着"其实已发成功、只是没读到响应"的，那就是双倍付款。
同理，只要出现过 SUCCESS 就绝不许 DELETE 掉 reward 行重新结算：单号只由活动码 + userId 决定，重结算出来的是同一批单号，每个已到账的人重领都会撞 duplicate key 被判"已发放"，页面告诉他已到账，而服务端付的是上一轮那个金额、与新的 ldc_amount 无关。真要重算得先换 campaign.code（新单号 = 新账），并且认清那等于重新发一整轮钱。$$;
COMMENT ON COLUMN campaign_reward.external_ref IS 'LDC 返回的 trade_no';

CREATE INDEX IF NOT EXISTS idx_campaign_reward_status
    ON campaign_reward (campaign_id, status);

-- ============ 5. 活动种子行 ============
-- 时间按需改；code 一旦发放过就绝不能改（out_trade_no 靠它重算，改了幂等就断了）
INSERT INTO campaign (code, name, start_at, end_at, prize_pool, status)
VALUES ('FIVEDIM_202608', '五维交易赛',
        '2026-08-03 00:00:00', '2026-08-17 00:00:00', 500, 'RUNNING')
ON CONFLICT (code) DO NOTHING;
