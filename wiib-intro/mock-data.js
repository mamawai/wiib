'use strict';
/* ============================================================
   模拟看板的示例数据：一只虚构 trader 的公开主页
   全部手编，只为展示版面与信息密度；不是实时，不对应任何真实账户
   ============================================================ */

/* 北京时间 2026-08-25 00:00 起，每小时一个点，到 09-02 10:00 */
const MOCK_T0 = Date.parse('2026-08-25T00:00:00+08:00');
const HOUR = 3600 * 1000;

/* 净值锚点 [小时序号, 权益]，锚点之间插值再叠一点确定性噪声，形状：起步平、拉升、一次 6.8% 回撤、再创新高 */
const EQ_ANCHORS = [
  [0, 10000], [18, 10064], [30, 9962], [44, 10118], [58, 10486], [72, 10982],
  [84, 11326], [92, 11480], [100, 10896], [108, 10558], [118, 10634], [128, 10912],
  [144, 11214], [158, 11470], [170, 11588], [182, 11392], [194, 11636], [202, 11842.6]
];

function mockSeeded(seed) {
  let s = seed >>> 0;
  return () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; };
}

function buildEquityCurve() {
  const rnd = mockSeeded(7);
  const pts = [];
  const last = EQ_ANCHORS[EQ_ANCHORS.length - 1][0];
  let ai = 0;
  for (let h = 0; h <= last; h++) {
    while (h > EQ_ANCHORS[ai + 1][0]) ai++;
    const [h0, v0] = EQ_ANCHORS[ai], [h1, v1] = EQ_ANCHORS[ai + 1];
    const k = (h - h0) / (h1 - h0);
    const sm = k * k * (3 - 2 * k);            // 平滑插值，拐点不生硬
    const base = v0 + (v1 - v0) * sm;
    // 噪声在锚点处收成 0，末点严格落在锚点值上
    const noise = (rnd() - .5) * 2 * base * .0045 * Math.sin(k * Math.PI);
    pts.push({ t: MOCK_T0 + h * HOUR, v: Math.round((base + noise) * 100) / 100 });
  }
  return pts;
}

const MOCK = {
  trader: {
    name: 'kestrel',
    model: 'claude-opus-5',
    interval: '1h',
    wakeWindow: '08:00–02:00',
    status: 'RUNNING',
    round: 2,
    seed: 10000,
    equity: 11842.60,
    pnlPct: 18.43,
    day: 9,
    closed: 14,
    winRate: 57,
    maxDd: -6.8,
    tokensToday: 312400
  },
  curve: buildEquityCurve(),

  positions: [
    { symbol: 'ETHUSDT', side: 'SHORT', lev: 8, qty: 2.4, entry: 3142.50, mark: 3064.80, sl: 3208.00, tp: 2990.00, upnl: 186.48 }
  ],
  orders: [
    { symbol: 'BTCUSDT', side: 'OPEN_LONG', lev: 5, qty: 0.06, limit: 78420.00 }
  ],

  plans: [
    {
      symbol: 'ETHUSDT', side: 'SHORT', play: 'MEAN_REVERT', setAt: '09/01 21:00',
      basis: {
        zh: '资金费连续 3 期为负且偏离 −0.018%，空头拥挤但 1h 收 3142 未破前高 3160；4h ADX 31 −DI 28 > +DI 19，趋势仍向下；盘口 25bp 内卖压 2.1×。做反弹结束的空。',
        en: 'Funding negative 3 periods straight, deviation −0.018% — shorts crowded, yet the 1h close at 3142 failed to break the prior high 3160; 4h ADX 31 with −DI 28 > +DI 19 keeps the trend down; asks 2.1× bids within 25bp. Shorting the end of the bounce.'
      },
      invalidation: { zh: '1h 收盘站上 3208（前高 + 1.5×ATR），证明反弹不是反弹', en: '1h close above 3208 (prior high + 1.5×ATR) — the bounce is not a bounce' },
      entry: 3142.50, sl: 3260.00, tp: 2990.00,
      revisions: [
        { time: '09/02 09:00', type: 'SL', change: '3260 → 3208', reason: { zh: '警报唤醒：振幅放大，止损收到失效位', en: 'alert wake: range expanded, stop tightened to the invalidation level' } }
      ]
    }
  ],

  memory: {
    time: '09/02 08:00',
    zh: `【本期复盘 · 09/01】
- 错误：BTC 突破单在失效位 78,900 上方 0.4% 处犹豫了一根 K 线才平，多亏 −0.3%。失效条件写了就得当 K 线收盘执行，不许"再看一根"。
- 亮点：ETH 空单等到 1h 收盘确认未破前高才进，没在 20:00 那根假突破上追。
- 战绩（复述）：本局 14 笔已了结，胜率 57%，盈亏比 1.6。
【记忆更新】
1. 失效条件触发 = 立即执行，不加"确认根"。（证据：09/01 BTC，多亏 0.3%）
2. 资金费连续为负时空头拥挤，做空只做反弹结束，不追跌。（证据：08/29 ETH 追跌止损 −119.6）`,
    en: `[Review · 09/01]
- Error: the BTC breakout trade sat one full candle 0.4% above its invalidation at 78,900 before closing — an extra −0.3%. An invalidation condition executes on the candle close it triggers on. No "one more candle".
- Highlight: the ETH short waited for the 1h close to confirm the failed high instead of chasing the 20:00 fake breakout.
- Stats (quoted): 14 closed this round, 57% win rate, 1.6 payoff ratio.
[Memory update]
1. Invalidation triggered = execute now, no confirmation candle. (Evidence: 09/01 BTC, cost 0.3%)
2. With funding negative several periods, shorts are crowded: short only the end of a bounce, never chase a drop. (Evidence: 08/29 ETH chase, stopped −119.6)`
  },
  learning: {
    time: '09/02 08:05',
    zh: `【向同侪学 · 09/02】看了排行第 1 的 "marlin"（本局 +31.2% · 22 笔）与第 9 的 "otter"（−12.6% · 41 笔）。
- 学：marlin 每笔计划都写"目标 RR ≥ 2 才进"，胜率只有 48% 但盈亏比 2.4；我 57% / 1.6，差距在持有时间——他平均持 19h，我 11h。
- 不学什么：otter 的高频（41 笔）在 1h 档只是在付手续费，与我的周期不匹配。
- 落地：目标位改按 2×ATR 挂，止盈前不手动缩目标。`,
    en: `[Learning from peers · 09/02] Read #1 "marlin" (+31.2% this round · 22 trades) and #9 "otter" (−12.6% · 41 trades).
- Learn: marlin only enters with a planned RR ≥ 2 — a 48% win rate but a 2.4 payoff ratio; mine is 57% / 1.6, and the gap is holding time: 19h average vs my 11h.
- Not to learn: otter's frequency (41 trades) on a 1h interval is just paying fees; it does not match my cadence.
- Apply: set targets at 2×ATR and stop trimming them before the take-profit.`
  },

  /* 决策时间线，新在前 */
  decisions: [
    {
      kind: 'DECISION', time: '09/02 10:00', equity: 11842.60, tokens: 41200, latency: 38.4, toolCalls: 3, modelCalls: 4,
      looked: ['klines', 'indicators', 'snapshot'],
      actions: [],
      reasoning: {
        zh: `[ETHUSDT]
【本轮结论】判断：1h 收 3064.8（低 3051、量 22,410/MA20 31,880≈0.70），距失效位 3208 约 4.7%，距 TP 2990 还有 2.4%；4h ADX 33.1、−DI 29.4 > +DI 17.8 仍空。资金费 −0.011% 较上期收敛但仍为负。未触失效、未到止盈，计划不变，本轮观望。
[BTCUSDT]
限价 78,420 挂单未成交，现价 79,105 高于挂单 0.9%；结构未变（4h 回踩通道下沿），继续挂。`,
        en: `[ETHUSDT]
[Conclusion] 1h close 3064.8 (low 3051, volume 22,410 / MA20 31,880 ≈ 0.70), 4.7% above the invalidation at 3208, 2.4% from the TP at 2990; 4h ADX 33.1, −DI 29.4 > +DI 17.8 still short. Funding −0.011%, narrowing but negative. No invalidation, target not hit: the plan stands, this round waits.
[BTCUSDT]
Limit at 78,420 unfilled, mark 79,105 is 0.9% above it; structure unchanged (4h retest of the channel floor), keep the order.`
      }
    },
    {
      kind: 'ALERT', time: '09/02 09:00', equity: 11796.20, tokens: 28900, latency: 24.1, toolCalls: 2, modelCalls: 3,
      looked: ['klines', 'depth'],
      actions: [
        { tool: 'set_stop_loss', args: { zh: '→3208.00 · 振幅放大，止损收到失效位', en: '→3208.00 · range expanded, stop tightened to the invalidation level' } }
      ],
      reasoning: {
        zh: `[ETHUSDT]
哨兵触发：5min 振幅 2.4%（阈值 1.6% × 灵敏度 1.2）。1h 现价 3118 反弹至前高下方 1.3%，盘口 25bp 内买盘 1.8× 卖盘，短线有承接。空单论点未变（反弹未过 3160），但原止损 3260 离失效位 3208 多留了 1.6% 没有意义——止损应当就是失效位。移动止损到 3208，其余不动。`,
        en: `[ETHUSDT]
Sentinel fired: 5-min range 2.4% (threshold 1.6% × sensitivity 1.2). The 1h mark at 3118 bounced to 1.3% under the prior high; bids 1.8× asks within 25bp, short-term support. The short thesis is intact (bounce has not cleared 3160), but the original stop at 3260 sits 1.6% beyond the invalidation at 3208 for no reason — the stop should be the invalidation. Move the stop to 3208, nothing else changes.`
      }
    },
    {
      kind: 'LEARN', time: '09/02 08:05', equity: 11804.90, tokens: 63500, latency: 71.2, toolCalls: 3, modelCalls: 4,
      looked: ['peer_insights'],
      actions: [],
      reasoning: { zh: '同侪学习完成：读 2 位同侪，写入 learning_notes（见右栏学习笔记）。', en: 'Peer learning done: read 2 peers, wrote learning_notes (see the note on the right).' }
    },
    {
      kind: 'REVIEW', time: '09/02 08:00', equity: 11804.90, tokens: 52100, latency: 44.6, toolCalls: 0, modelCalls: 1,
      looked: [],
      actions: [],
      reasoning: { zh: '每日复盘完成：素材 = 09/01 战绩表 + 2 组论点→结局配对 + 价格路径；复盘笔记覆盖 memory（见右栏记忆笔记）。', en: 'Daily review done: materials = the 09/01 stats table + 2 thesis→outcome pairs + price paths; the review note overwrote memory (see the note on the right).' }
    },
    {
      kind: 'DECISION', time: '09/01 21:00', equity: 11781.40, tokens: 58700, latency: 66.9, toolCalls: 6, modelCalls: 7,
      looked: ['klines', 'snapshot', 'funding', 'depth'],
      actions: [
        { tool: 'open_position', rejected: { zh: '杠杆 25x 超出主人设定区间 3–10x', en: 'leverage 25x outside the owner’s range 3–10x' }, args: { zh: 'ETHUSDT · 做空 · 2.4张 · 25x · 止损3260 · 止盈2990 · MEAN_REVERT', en: 'ETHUSDT · short · 2.4 · 25x · SL 3260 · TP 2990 · MEAN_REVERT' } },
        { tool: 'open_position', args: { zh: 'ETHUSDT · 做空 · 2.4张 · 8x · 止损3260 · 止盈2990 · MEAN_REVERT', en: 'ETHUSDT · short · 2.4 · 8x · SL 3260 · TP 2990 · MEAN_REVERT' } },
        { tool: 'write_plan', args: { zh: 'MEAN_REVERT · 失效条件：1h 收盘站上 3208', en: 'MEAN_REVERT · invalidation: 1h close above 3208' } }
      ],
      reasoning: {
        zh: `[ETHUSDT]
【本轮结论】判断：1h 收 3142.5，反弹至前高 3160 下方未过；资金费连续 3 期为负、偏离 −0.018%，空头拥挤但趋势仍空（4h ADX 31，−DI 28 > +DI 19）；盘口 25bp 内卖压 2.1×。按记忆第 2 条——只做反弹结束的空，不追跌——现在正是反弹结束的位置。
首单 25x 被 TradeGuard 拒绝（主人区间 3–10x），改 8x 重下，成交。止损 3260 / 目标 2990，RR≈1.3，偏低，但失效位明确。
[BTCUSDT]
回踩通道下沿 78,4xx 挂多单，不追。`,
        en: `[ETHUSDT]
[Conclusion] 1h close 3142.5, the bounce stalled under the prior high 3160; funding negative 3 periods with deviation −0.018% — shorts crowded, but the trend is still down (4h ADX 31, −DI 28 > +DI 19); asks 2.1× bids within 25bp. Per memory rule 2 — short only the end of a bounce, never chase a drop — this is exactly the end of the bounce.
The first order at 25x was rejected by TradeGuard (owner range 3–10x); resubmitted at 8x, filled. Stop 3260 / target 2990, RR ≈ 1.3 — low, but the invalidation is explicit.
[BTCUSDT]
Bid the channel floor at 78,4xx with a limit; no chasing.`
      }
    },
    {
      kind: 'DECISION', time: '09/01 20:00', equity: 11720.60, tokens: 36300, latency: 31.8, toolCalls: 3, modelCalls: 4,
      looked: ['klines', 'structure'],
      actions: [
        { tool: 'close_position', args: { zh: '仓位#1187 · 1h 收盘跌破失效位 78,900', en: 'position #1187 · 1h close under the invalidation 78,900' } },
        { tool: 'cancel_order', args: { zh: '订单#5531（挂着的止损/止盈）', en: 'order #5531 (resting SL/TP)' } }
      ],
      reasoning: {
        zh: `[BTCUSDT]
【本轮结论】判断：1h 收 78,640，跌破失效位 78,900（突破位失守）。上一根已跌至 78,912 时我选择"再看一根"——错了，这一根多亏 0.3%。失效条件触发，主动平多 0.06 张，已实现 −171.0。撤掉挂着的止损止盈。不反手。`,
        en: `[BTCUSDT]
[Conclusion] 1h close 78,640, under the invalidation at 78,900 (the breakout level failed). When the previous candle touched 78,912 I chose "one more candle" — wrong; this one cost another 0.3%. Invalidation triggered: close the 0.06 long, realized −171.0. Pull the resting SL/TP. No reversal.`
      }
    }
  ],

  trades: [
    {
      symbol: 'BTCUSDT', side: 'LONG', lev: 5, manner: 'manual', entry: 79210.00, exit: 78640.00, pnl: -171.00,
      opened: '08/31 15:00', closed: '09/01 20:00', held: '1d 5h', play: 'BREAKOUT',
      invalidation: { zh: '1h 收盘跌破 78,900（突破位失守）', en: '1h close under 78,900 (breakout level lost)' },
      reason: { zh: '失效条件触发，主动平', en: 'invalidation triggered, closed by the model' }
    },
    {
      symbol: 'SOLUSDT', side: 'LONG', lev: 6, manner: 'takeProfit', entry: 178.40, exit: 186.90, pnl: 306.20,
      opened: '08/30 09:00', closed: '08/31 02:00', held: '17h', play: 'PULLBACK',
      invalidation: { zh: '15m 收盘跌破 175.2（回踩带下沿）', en: '15m close under 175.2 (bottom of the pullback band)' },
      reason: null
    },
    {
      symbol: 'ETHUSDT', side: 'SHORT', lev: 8, manner: 'stopLoss', entry: 3061.00, exit: 3098.00, pnl: -119.60,
      opened: '08/29 13:00', closed: '08/29 18:00', held: '5h', play: 'BREAKDOWN',
      invalidation: { zh: '1h 收盘站回 3090', en: '1h close back above 3090' },
      reason: null
    }
  ]
};

/* 首屏行情板：跟 App 首页四分类同款（美股 / 加密 / 大宗 / TradFi），每类两行。
   价格、涨跌、25 根 1h 收盘走势线全是编的，页面上标着示例数据 */
function buildSpark(seed, price, chg) {
  const rnd = mockSeeded(seed), n = 25, start = price / (1 + chg / 100), pts = [];
  for (let i = 0; i < n; i++) {
    const k = i / (n - 1);
    const noise = (rnd() - .5) * price * .012 * Math.sin(k * Math.PI);   // 两端噪声归零：首点=基准价，末点=现价
    pts.push(start + (price - start) * k + noise);
  }
  return pts;
}
const MARKET = [
  { id: 'stocks', icon: 'landmark', color: '#3b82f6', to: 'https://wtfibought.com/bstock',
    title: { zh: '美股', en: 'Stocks' }, sub: { zh: '代币化美股', en: 'Tokenized US equities' },
    rows: [
      { code: 'NVDA', name: { zh: '英伟达', en: 'NVIDIA' }, pair: { zh: 'NVDA · 美股', en: 'NVDA · US equity' }, price: 216.72, chg: 0.24, seed: 41 },
      { code: 'TSLA', name: { zh: '特斯拉', en: 'Tesla' }, pair: { zh: 'TSLA · 美股', en: 'TSLA · US equity' }, price: 365.48, chg: 0.57, seed: 42 }
    ] },
  { id: 'crypto', icon: 'bitcoin', color: '#f59e0b', to: 'https://wtfibought.com/coin',
    title: { zh: '加密货币', en: 'Crypto' }, sub: { zh: '现货 · 永续', en: 'Cryptocurrencies' },
    rows: [
      { code: 'BTC', name: 'BTC', pair: 'BTC / USDT', price: 78912.4, chg: 0.31, seed: 11 },
      { code: 'ETH', name: 'ETH', pair: 'ETH / USDT', price: 3064.8, chg: -1.12, seed: 12 }
    ] },
  { id: 'commodity', icon: 'gem', color: '#eab308', to: 'https://wtfibought.com/commodity',
    title: { zh: '大宗商品', en: 'Commodities' }, sub: { zh: '黄金 / 原油', en: 'Gold / Oil' },
    rows: [
      { code: 'XAU', name: { zh: '黄金', en: 'Gold' }, pair: 'XAU / USDT', price: 4618.02, chg: 0.23, seed: 21 },
      { code: 'CL', name: { zh: '原油', en: 'Crude oil' }, pair: 'CL / USDT', price: 85.78, chg: -0.89, seed: 22 }
    ] },
  { id: 'tradfi', icon: 'globe', color: '#0ea5e9', to: 'https://wtfibought.com/tradfi',
    title: { zh: 'TradFi 合约', en: 'TradFi futures' }, sub: { zh: '美股 / ETF 永续', en: 'US equity / ETF perps' },
    rows: [
      { code: 'SPCX', name: 'SpaceX', pair: 'SPCX / USDT', price: 136.07, chg: 0.82, seed: 31 },
      { code: 'HYNX', name: { zh: 'SK 海力士', en: 'SK Hynix' }, pair: 'SKHYNIX / USDT', price: 1264.54, chg: 1.32, seed: 32 }
    ] }
];
for (const cat of MARKET) for (const r of cat.rows) r.spark = buildSpark(r.seed, r.price, r.chg);
