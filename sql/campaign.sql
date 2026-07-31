-- ============================================================
-- LDC 瓜分活动（五维交易赛）：活动定义 / 签到 / 多空投票 / 结算发放
-- 与业务表零耦合：活动只读业务表，只写本文件这四张。活动结束整包移除：
--   DROP TABLE IF EXISTS campaign_reward, campaign_vote, campaign_checkin, campaign;
--   再删 wiib-sim 的 campaign 包与本文件，业务代码无感。
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
COMMENT ON COLUMN campaign_reward.status       IS 'PENDING 待领取 / CLAIMED 已授权待发 / SUCCESS 已到账 / FAILED 发放失败';
COMMENT ON COLUMN campaign_reward.out_trade_no IS 'WIIB_{campaignCode}_{userId}，固定可重算，整套幂等的基石。人工补发必须原样复用本列的值：换新单号会绕过服务端唯一索引，而 FAILED 里混着"其实已发成功、只是没读到响应"的，那就是双倍付款';
COMMENT ON COLUMN campaign_reward.external_ref IS 'LDC 返回的 trade_no';

CREATE INDEX IF NOT EXISTS idx_campaign_reward_status
    ON campaign_reward (campaign_id, status);

-- ============ 5. 活动种子行 ============
-- 时间按需改；code 一旦发放过就绝不能改（out_trade_no 靠它重算，改了幂等就断了）
INSERT INTO campaign (code, name, start_at, end_at, prize_pool, status)
VALUES ('FIVEDIM_202608', '五维交易赛',
        '2026-08-03 00:00:00', '2026-08-17 00:00:00', 500, 'RUNNING')
ON CONFLICT (code) DO NOTHING;
