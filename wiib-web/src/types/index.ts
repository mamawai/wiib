export interface User {
  id: number;
  username: string;
  avatar?: string;
  balance: number;
  /** 游戏钱包：Mines/扑克/21点兑现/预测市场专用，和交易 balance 分离 */
  gameBalance: number;
  frozenBalance: number;
  positionMarketValue: number;
  marginLoanPrincipal: number;
  marginInterestAccrued: number;
  bankrupt: boolean;
  bankruptCount: number;
  bankruptResetDate?: string;
  totalAssets: number;
  profit: number;
  profitPct: number;
}

// MyBatis-Plus分页结果
export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

/**
 * 榜单排序维度。没有「收益率」这一档——初始资金全站是同一个常数，
 * 收益率跟总资产是同一个序，加进来就是同一张榜换个名字。
 */
export type RankingSort = 'ASSETS' | 'TRADING_PROFIT';

export interface RankingItem {
  rank: number;
  userId: number;
  username: string;
  avatar?: string;
  totalAssets: number;
  profitPct: number;
  /** 交易盈利 = 合约 + 现货 + 预测的净盈亏，不含优惠券省下的钱 */
  tradingProfit: number;
  /** 余额钱包（含冻结）与游戏钱包只是总资产的现金部分，相加 ≠ totalAssets */
  balanceWallet: number;
  gameWallet: number;
}

// ========== Buff相关类型 ==========
export interface UserBuff {
  id: number;
  buffType: string;
  buffName: string;
  rarity: 'COMMON' | 'RARE' | 'EPIC' | 'LEGENDARY';
  extraData?: string;
  expireAt: string;
  isUsed: boolean;
}

export interface BuffStatus {
  canDraw: boolean;
  todayBuff: UserBuff | null;
}

// ========== Blackjack相关类型 ==========
export interface BlackjackStatus {
  chips: number;
  todayConverted: number;
  convertable: number;
  todayConvertLimit: number;
  totalHands: number;
  totalWon: number;
  totalLost: number;
  biggestWin: number;
  dailyPool: number;
  activeGame: GameState | null;
}

export interface GameState {
  phase: 'PLAYER_TURN' | 'DEALER_TURN' | 'SETTLED';
  playerHands: HandInfo[];
  activeHandIndex: number;
  dealerCards: string[];
  dealerScore: number | null;
  chips: number;
  insurance: number | null;
  actions: string[];
  results: HandResult[] | null;
}

export interface HandInfo {
  cards: string[];
  bet: number;
  score: number;
  isBust: boolean;
  isBlackjack: boolean;
  isDoubled: boolean;
}

export interface HandResult {
  handIndex: number;
  result: 'WIN' | 'LOSE' | 'PUSH' | 'BLACKJACK';
  payout: number;
  net: number;
}

export interface ConvertResult {
  chips: number;
  balance: number;
  todayConverted: number;
  /** 转出后仍可转出的积分，后端算好返回，前端不再自己减保底值 */
  convertable: number;
}

// ========== 矿工游戏类型 ==========
export interface MinesGameState {
  gameId: number;
  betAmount: number;
  revealed: number[];
  minePositions: number[] | null;
  result: 'SAFE' | 'MINE' | 'CASHED_OUT' | null;
  currentMultiplier: number;
  nextMultiplier: number | null;
  potentialPayout: number;
  payout: number | null;
  phase: 'PLAYING' | 'SETTLED';
  balance: number;
}

export interface MinesStatus {
  balance: number;
  activeGame: MinesGameState | null;
}

// ========== 视频扑克类型 ==========
export interface VideoPokerGameState {
  gameId: number;
  betAmount: number;
  cards: string[];
  heldPositions: number[];
  handRank: string;
  multiplier: number;
  payout: number;
  phase: 'DEALING' | 'SETTLED';
  balance: number;
}

export interface VideoPokerStatus {
  balance: number;
  activeGame: VideoPokerGameState | null;
}

// ========== 加密货币行情类型 ==========
export interface CryptoPrice {
  price: string;
  ts: string;
}

// ========== 加密货币交易类型 ==========
export interface CryptoOrderRequest {
  symbol: string;
  quantity: number;
  orderType: 'MARKET' | 'LIMIT';
  limitPrice?: number;
  leverageMultiple?: number;
  useBuffId?: number;
}

export interface CryptoPosition {
  id: number;
  symbol: string;
  quantity: number;
  frozenQuantity: number;
  avgCost: number;
  totalDiscount: number;
}

// bStock 代币化美股：静态信息(bstock 表) + 实时行情
export interface BStock {
  id: number;
  symbol: string;        // NVDABUSDT
  ticker: string;        // NVDA
  name: string;          // 英伟达
  nameEn?: string;
  industry?: string;
  description?: string;
  ceo?: string;
  homepage?: string;
  marketCap?: number;
  peRatio?: number;
  dividendYield?: number;
  multiplier?: number;
  week52High?: number;
  week52Low?: number;
  // 实时
  price?: number;
  changePct?: number;
  high?: number;
  low?: number;
  volume?: number;
}

export interface CryptoOrder {
  orderId: number;
  symbol: string;
  orderSide: string;
  orderType: string;
  quantity: number;
  leverage: number;
  limitPrice?: number;
  filledPrice?: number;
  filledAmount?: number;
  commission?: number;
  triggerPrice?: number;
  triggeredAt?: string;
  status: string;
  createdAt: string;
}

// ========== 永续合约类型 ==========
/** 保证金模式：CROSS=全仓（整个账户净值兜底），ISOLATED=逐仓（只赔本仓保证金） */
export type FuturesMarginMode = 'CROSS' | 'ISOLATED';

export interface FuturesSLItem { price: number; quantity: number }
export interface FuturesTPItem { price: number; quantity: number }
export interface FuturesSLEntry { id: string; price: number; quantity: number }
export interface FuturesTPEntry { id: string; price: number; quantity: number }

export interface FuturesOpenRequest {
  symbol: string;
  side: 'LONG' | 'SHORT';
  quantity: number;
  leverage: number;
  marginMode: FuturesMarginMode;
  orderType: 'MARKET' | 'LIMIT';
  limitPrice?: number;
  stopLosses?: FuturesSLItem[];
  takeProfits?: FuturesTPItem[];
}

export interface FuturesCloseRequest {
  positionId: number;
  quantity: number;
  orderType: 'MARKET' | 'LIMIT';
  limitPrice?: number;
}

// 交易过滤器：数量步长/最小数量/最小名义额（对齐Binance exchangeInfo，后端注册表下发）
export interface TradeFilter {
  stepSize: number;
  minQty: number;
  minNotional: number;
}

export interface TradeFilterMap {
  futures: Record<string, TradeFilter>;
  spot: Record<string, TradeFilter>;
}

export interface FuturesAddMarginRequest {
  positionId: number;
  amount: number;
}

export interface FuturesReduceMarginRequest {
  positionId: number;
  amount: number;
}

export interface FuturesBracket {
  tier: number;
  notionalFloor: number;
  notionalCap: number;
  maxLeverage: number;
  mmr: number;
  maintAmount: number;
}

// 币种级调杠杆（对齐Binance）：多空共用杠杆，一次调整作用于该币全部仓位
export interface FuturesAdjustLeverageRequest {
  symbol: string;
  leverage: number;
}

/** 全仓账户概览（GET /futures/cross-account） */
export interface FuturesCrossAccount {
  balance: number;
  unrealizedPnl: number;
  equity: number;
  available: number;
  usedMargin: number;
  pendingReserved: number;
  maintenanceMargin: number;
  positionCount: number;
}

/** 划转预检（GET /wallet/transfer/preview）：restricted=可转额度被全仓占用压低（含未成交的全仓挂单） */
export interface WalletTransferPreviewPosition {
  positionId: number;
  symbol: string;
  side: string;
  estLiqPrice: number;
}
export interface WalletTransferPreview {
  restricted: boolean;
  allowed: boolean;
  maxTransferable?: number;
  equityAfter?: number;
  maintenanceMargin?: number;
  positions?: WalletTransferPreviewPosition[];
}

export interface FuturesStopLossRequest {
  positionId: number;
  stopLosses: FuturesSLItem[];
}

export interface FuturesTakeProfitRequest {
  positionId: number;
  takeProfits: FuturesTPItem[];
}

// ==================== 合约仓位历史 ====================

/** 仓位历史里的一笔成交。分批平仓靠它才看得见「0.4@110 / 0.6@120」的过程 */
export interface PositionFill {
  positionId: number;
  orderId: number;
  /** OPEN_LONG/OPEN_SHORT 开或加仓，CLOSE_LONG/CLOSE_SHORT 平仓 */
  orderSide: string;
  orderType: 'MARKET' | 'LIMIT';
  /** FILLED 手动成交，STOP_LOSS/TAKE_PROFIT 止损止盈打到，LIQUIDATED 被强平 */
  status: 'FILLED' | 'STOP_LOSS' | 'TAKE_PROFIT' | 'LIQUIDATED';
  quantity: number;
  price: number;
  amount: number;
  commission: number;
  /** 平仓单才有，开/加仓单为 null */
  realizedPnl: number | null;
  filledAt: string;
}

/**
 * 一笔合约仓位的完整生命周期（开→平）。
 * 跟 FuturesOrder 的区别是粒度：那个是一笔笔委托，这个把同仓位的开/加/分批平合成一条生意。
 */
export interface PositionHistoryItem {
  id: number;
  symbol: string;
  side: 'LONG' | 'SHORT';
  marginMode: FuturesMarginMode;
  leverage: number;
  /** CLOSED 正常平掉（含止盈止损打到） LIQUIDATED 被强平 */
  status: 'CLOSED' | 'LIQUIDATED';
  /** AI 策略标签，手动开的仓为 null */
  memo: string | null;
  /** 开仓均价（多次加仓已按量加权） */
  entryPrice: number;
  /** 已平仓量 = 全部平仓单数量之和 */
  closedQty: number;
  closeAmount: number;
  /** 平仓均价；一单没平过的仓位为 null（破产清零那批），显示"—" */
  closeAvgPrice: number | null;
  /** 累计投入保证金，回报率的分母 */
  investedMargin: number;
  commission: number;
  fundingFeeTotal: number;
  /** 已实现盈亏（净额）：已扣手续费与资金费，口径同排行榜「交易盈利」 */
  realizedPnl: number;
  /** 投资回报率(%)；分母为 0 时 null */
  roiPct: number | null;
  openedAt: string;
  closedAt: string;
  fills: PositionFill[];
}

export interface FuturesPosition {
  id: number;
  userId: number;
  symbol: string;
  side: 'LONG' | 'SHORT';
  leverage: number;
  marginMode: FuturesMarginMode;
  quantity: number;
  entryPrice: number;
  margin: number;
  fundingFeeTotal: number;
  stopLosses?: FuturesSLEntry[];
  takeProfits?: FuturesTPEntry[];
  status: string;
  closedPrice?: number;
  closedPnl?: number;
  createdAt: string;
  updatedAt: string;
  /** 持仓期已实现盈亏：开/加仓手续费+已平部分净盈亏（不含资金费） */
  realizedPnl?: number;
  currentPrice: number;
  markPrice: number;
  positionValue: number;
  unrealizedPnl: number;
  unrealizedPnlPct: number;
  effectiveMargin: number;
  maintenanceMargin: number;
  liquidationPrice: number;
  fundingFeePerCycle: number;
}

export interface FuturesOrder {
  orderId: number;
  userId: number;
  positionId?: number;
  symbol: string;
  orderSide: string;
  orderType: string;
  quantity: number;
  leverage: number;
  limitPrice?: number;
  frozenAmount?: number;
  filledPrice?: number;
  filledAmount?: number;
  marginAmount?: number;
  commission?: number;
  realizedPnl?: number;
  status: string;
  createdAt: string;
  isAiTrader?: boolean;
}

// ========== BTC 5min 涨跌预测 ==========

export interface PredictionRound {
  id?: number;
  windowStart: number;
  startPrice?: string;
  endPrice?: string;
  outcome?: string;
  upPrice?: string;
  downPrice?: string;
  status: string;
  remainingSeconds: number;
  serverTimeMs?: number;
  officialNowTimeMs?: number;
  officialStartTimeMs?: number;
  officialEndTimeMs?: number;
}

export interface PredictionBet {
  id: number;
  roundId: number;
  windowStart?: number;
  side: string;
  contracts: number;
  cost: number;
  avgPrice: number;
  payout?: number;
  currentValue?: number;
  status: string;
  createdAt: string;
}

export interface PredictionPnl {
  totalBets: number;
  activeBets: number;
  wonBets: number;
  lostBets: number;
  totalCost: number;
  realizedPnl: number;
  activeCost: number;
  activeValue: number;
  totalPnl: number;
  winRate: number;
}

export interface PredictionBuyRequest {
  side: 'UP' | 'DOWN';
  amount: number;
}

export interface PredictionBetLive {
  username: string;
  avatar?: string;
  side?: string;
  outcome?: string;
  price?: number;
  size?: number;
  amount?: number;
  source?: string;
  ts: number;
}

// ========== 资产快照类型（五分类：bStock/crypto/大宗商品/预测/游戏） ==========
export interface AssetSnapshot {
  date: string;
  totalAssets: number;
  profit: number;
  profitPct: number;
  bstockProfit: number;
  cryptoProfit: number;
  commodityProfit: number;
  predictionProfit: number;
  gameProfit: number;
  dailyProfit: number;
  dailyProfitPct: number;
  dailyBstockProfit: number;
  dailyCryptoProfit: number;
  dailyCommodityProfit: number;
  dailyPredictionProfit: number;
  dailyGameProfit: number;
}

export interface CategoryAverages {
  bstockProfit: number;
  cryptoProfit: number;
  commodityProfit: number;
  predictionProfit: number;
  gameProfit: number;
}

// ========== AI Agent 类型 ==========
export interface BehaviorAnalysisReport {
  overview: {
    totalAssets: number;
    totalProfitPct: number;
    distribution: { category: string; value: number }[];
    trend: { date: string; totalAssets: number }[];
  };
  tradeBehavior: {
    stock: { positionCount: number; orderCount: number; totalBuyAmount: number; preference: string };
    crypto: { positionCount: number; totalBuyAmount: number; totalSellAmount: number; leverageUsage: string };
    futures: { realizedPnl: number; orderCount: number; direction: string; avgLeverage: number; stopLossRate: number; liquidationCount: number };
    option: { totalBtoAmount: number; totalStcAmount: number };
    prediction: { frequency: number; netProfit: number; winRate: number; directionPreference: string };
  };
  gameBehavior: {
    blackjack: { totalHands: number; totalWon: number; totalLost: number; biggestWin: number; todayConverted: number };
    mines: { frequency: number; netProfit: number };
    videoPoker: { frequency: number; netProfit: number };
  };
  riskProfile: {
    riskLevel: string;
    bankruptCount: number;
    maxDrawdown: string;
    bankruptAt: string;
  };
  suggestions: string[];
}

export interface AiKeyConfig {
  id?: number;
  configName: string;
  apiKey: string;
  baseUrl: string;
  model?: string;
  /** 思考档位 none/low/medium/high；空=不传走模型默认 */
  reasoningEffort?: string;
  /** 上游协议 openai=/v1/chat/completions，responses=/v1/responses；空=openai */
  apiProtocol?: string;
  enabled?: boolean;
}

/** 邀请码（Admin 管理） */
export interface InviteCode {
  id: number;
  code: string;
  maxUses: number;
  usedCount: number;
  enabled: boolean;
  createdAt: string;
}

// 功能位→LLM配置的指针：更换LLM只改configId，模型名归属AiKeyConfig
export interface AiModelAssignment {
  id?: number;
  functionName: string;
  configId: number;
}


export interface ForceOrder {
  id: number;
  symbol: string;
  side: string;
  price: number;
  avgPrice: number;
  quantity: number;
  amount: number;
  status: string;
  tradeTime: string;
  createdAt: string;
}

// feed WS 流健康（Admin 面板：状态展示 + 手动重试）
export interface FeedStreamHealth {
  name: string;
  status: 'CONNECTED' | 'CONNECTING' | 'RECONNECTING' | 'DISCONNECTED';
  lastMessageAt: number;   // epoch millis，前端本地算"距上次数据 Xs"
  reconnectAttempt: number;
}

// ========== P7 研判工作台 ==========
/** 快照时间线曲线点（/ai/quant/snapshots/series） */
export interface QuantSnapshotSeriesPoint {
  closeTime: number;
  lastPrice: number;
  h6SigmaBps: number | null;
  h12SigmaBps: number | null;
  h24SigmaBps: number | null;
  volState: string | null;
  fragilityScore: number | null;
  fragilityLevel: string | null;
  /** 各腿已验证实际波幅 |return| bps；到期才有，故尾部按各自腿长天然缺（H24 缺最多） */
  realizedH6AbsBps: number | null;
  realizedH12AbsBps: number | null;
  realizedH24AbsBps: number | null;
}

/** 深研判（quant_deep_analysis 实体透传） */
export interface QuantDeepAnalysisView {
  id: number;
  symbol: string;
  closeTime: number;
  triggerSource: string;
  snapshotId: number | null;
  narrative: string;
  /** {bullPct, rangePct, bearPct} 和=100 */
  scenariosJson: string;
  noDirection: boolean;
  invalidation: string;
  bullArgument: string;
  bearArgument: string;
  judgeReasoning: string;
  newsContext: string | null;
  createdAt: string;
}

/** 记分卡（/ai/quant/scorecard） */
export interface ScorecardHorizon {
  horizon: string;
  samples: number;
  avgQlike: number;
  avgBaselineQlike: number;
  /** (baseline-forecast)/baseline，>0=跑赢基准 */
  qlikeImprovement: number;
  qlikeWinRate: number;
  volStateHitRate: number;
}
export interface Scorecard {
  symbol: string;
  windowDays: number;
  runningDays: number;
  totalSamples: number;
  horizons: ScorecardHorizon[];
  note: string | null;
}

/** 工作台 SSE 事件（与 ChatWorkbenchController 协议一一对应） */
export type WorkbenchEvent =
  | { type: 'session'; sessionId: string }
  | { type: 'agent_start'; node: string; agent: string }
  | { type: 'token'; text: string; agent: string; role: 'answer' | 'process' }
  | { type: 'progress'; text: string }
  | { type: 'hitl_request'; sessionId: string; symbol: string; reason: string; resumeMessage: string }
  | { type: 'done'; sessionId: string; answer: string }
  | { type: 'error'; message: string };

// ========== 策略账户监控 ==========
/** 已平仓历史（静态字段快照，无实时价字段） */
export type StrategyClosedPosition = Pick<FuturesPosition,
  'id' | 'userId' | 'symbol' | 'side' | 'leverage' | 'quantity' | 'entryPrice'
  | 'margin' | 'fundingFeeTotal' | 'status' | 'closedPrice' | 'closedPnl'
  | 'createdAt' | 'updatedAt'> & { memo?: string };

export interface StrategyAccountView {
  strategyId: string;
  accountUserId: number | null;
  /** false=sim 未启动或账户异常，整栏渲染空态 */
  available: boolean;
  balance: number | null;
  unrealizedPnl: number | null;
  equity: number | null;
  cumPnl: number | null;
  tradeCount: number;
  winCount: number;
  winRate: number;
  positions: FuturesPosition[];
  closedPositions: StrategyClosedPosition[];
}

/** 工作台历史会话摘要（/ai/workbench/sessions） */
export interface WorkbenchSessionSummary {
  sessionId: string;
  title: string;
  messageCount: number;
  lastAt: number;
}

/** 工作台历史消息（/ai/workbench/sessions/{id}/messages） */
export interface WorkbenchChatMessage {
  role: 'user' | 'assistant' | string;
  content: string;
  createdAt: number;
}

/** 策略×币种实时信号状态快照（/ai/strategies/signals）：一句话状态 + 有序指标表 */
export interface StrategySignalState {
  strategyId: string;
  symbol: string;
  state: string;
  metrics: Record<string, string>;
}

/** 最新数值快照（quant_snapshot 实体透传，/ai/quant/snapshots/latest） */
export interface QuantSnapshotView {
  id: number;
  symbol: string;
  closeTime: number;
  lastPrice: number;
  /** {H6:{sigmaBps,percentile,tier,volState,lowCut,highCut,regime,regimeConfidence},H12,H24} */
  volLegsJson: string;
  regime: string | null;
  regimeConfidence: number | null;
  fragilityScore: number;
  fragilityLevel: string;
  fragilityDirection: string;
  fragilityHeadline: string;
  signalPanelJson: string;
  qualityFlagsJson: string;
  createdAt: string;
}

/** 重要快讯（BlockBeats 缓存透传，plain 为脱 HTML 纯文本） */
export interface NewsFlashItem {
  id: number;
  title: string;
  plain: string;
  url: string;
  /** 形如 "2026-07-09 00:30:12" */
  createTime: string;
}

// ========== 留言板与通知 ==========

/** 留言板评论。只有两层：rootId 为空是根评论，非空是该根评论下的子评论。 */
export interface CommentItem {
  id: number;
  userId: number;
  username: string;
  avatar?: string;
  rootId?: number;
  replyToUserId?: number;
  /** 子评论展示"回复 @xxx"用 */
  replyToUsername?: string;
  content: string;
  likeCount: number;
  dislikeCount: number;
  /** 已对本条表过态（赞踩共用一次机会），true 时两个按钮都置灰。未登录恒 false */
  voted: boolean;
  /** 根评论专用：子评论总数。列表接口只带前 2 条预览，靠它判断要不要出"查看全部" */
  childCount: number;
  children?: CommentItem[];
  /** 非空即编辑过，显示"已编辑"。自删不写此字段，占位符不会被标成已编辑 */
  updatedAt?: string;
  /** 作者自删的占位符：正文已被覆盖，不能再编辑，但照常可赞可回复 */
  selfDeleted?: boolean;
  createdAt: string;
}

/** 通知条目。前端按 type+commentId 分组合并展示，后端每次事件只管插一行。 */
/** 评论类通知的 type */
export type CommentNotifType = 1 | 2;
/** 交易类通知的 type：3逐仓强平 4止损 5止盈 6全仓爆仓 */
export type TradeNotifType = 3 | 4 | 5 | 6;

export interface NotificationItem {
  id: number;
  /** 1赞 2回复 3逐仓强平 4止损 5止盈 6全仓爆仓 */
  type: CommentNotifType | TradeNotifType;
  isRead: boolean;
  createdAt: string;

  // ---- 评论类专属（交易类为 null）----
  /** 点击跳转目标：赞=自己被赞那条，回复=对方那条回复 */
  commentId: number | null;
  actorId: number | null;
  actorName: string | null;

  // ---- 交易类专属（评论类为 null）----
  /** 全仓爆仓跨多币种，为 null */
  symbol: string | null;
  side: 'LONG' | 'SHORT' | null;
  /** 平掉的数量；type=6 时是"爆掉的仓位数" */
  quantity: number | null;
  /** 触发价；全仓爆仓为 null */
  price: number | null;
  /** 已实现盈亏；type=6 时是净结算额 */
  pnl: number | null;
}

// ==================== 资金账单 ====================

/** 账本钱包维度。前五个对应 user 表的资金列；POSITION_MARGIN 记的是仓位保证金 */
export type LedgerWallet =
  | 'BALANCE' | 'FROZEN' | 'GAME' | 'LOAN_PRINCIPAL' | 'LOAN_INTEREST' | 'POSITION_MARGIN';

export interface LedgerEntry {
  id: number;
  userId: number;
  wallet: LedgerWallet;
  /** 枚举名，筛选参数传的就是它 */
  bizType: string;
  /** 中文说法，后端平铺下来的，前端不再维护一份映射 */
  bizTypeLabel: string;
  /** 变动额，有符号，正入负出 */
  delta: number;
  /** 该钱包变动后余额 */
  balanceAfter: number;
  /** delta 中含的手续费；仅费与本金同条 SQL 时才有 */
  fee: number | null;
  refType: string | null;
  refId: number | null;
  symbol: string | null;
  remark: string | null;
  createdAt: string;
}

/** 筛选下拉选项，取自后端枚举，避免前端硬编码一份中文映射 */
export interface LedgerBizTypeOption {
  name: string;
  label: string;
  group: string;
}

// ==================== 排行榜用户详情 ====================

/** 详情页的一条持仓。现货与合约共用一个形状，合约专属字段在现货行上为 null */
export interface ProfilePosition {
  symbol: string;
  quantity: number;
  /** 现货=持仓均价，合约=开仓均价 */
  entryPrice: number;
  /** 现货=现价，合约=标记价；取不到价时为 null */
  currentPrice: number | null;
  /** 现货=市值，合约=保证金+未实现盈亏；缺价时为 null */
  value: number | null;
  /** 现货=浮动盈亏，合约=未实现盈亏；缺价时为 null */
  profit: number | null;
  side: 'LONG' | 'SHORT' | null;
  leverage: number | null;
  marginMode: 'CROSS' | 'ISOLATED' | null;
}

export interface UserProfile {
  /** 榜单行原样复用，口径与排行榜完全一致 */
  summary: RankingItem;
  spotPositions: ProfilePosition[];
  futuresPositions: ProfilePosition[];
}

// ==================== 全站成交记录（匿名） ====================

export interface PublicTrade {
  /** SPOT=现货/bStock（共用现货引擎），FUTURES=永续合约 */
  kind: 'SPOT' | 'FUTURES';
  tradeId: number;
  /** 稳定假名，如 "a3f2c1"。同一用户恒定，但反推不回是谁 */
  alias: string;
  /** 策略账户（quant-*）下的单，是机器人不是人 */
  isAi: boolean;
  symbol: string;
  /** 现货 BUY/SELL；合约 OPEN_LONG/OPEN_SHORT/CLOSE_LONG/CLOSE_SHORT */
  orderSide: string;
  quantity: number;
  filledPrice: number;
  filledAmount: number;
  createdAt: string;
}

// ==================== 可视化回测页 ====================

export interface BacktestStrategyMeta {
  id: string;
  name: string;
  desc: string;
  symbols: string[];
  /** 附加提示（如 LIQFADE 依赖研究性回填数据）；无则 null */
  note: string | null;
}

export interface BacktestTaskStatus {
  taskId: string;
  state: 'RUNNING' | 'DONE' | 'FAILED';
  strategyId: string;
  symbol: string;
  barsDone: number;
  totalBars: number;
  warmupBars: number;
  error: string | null;
}

/** 工作记录事件：seq=任务内游标（=事件表下标），type 见后端 BacktestListener 常量 */
export interface BacktestEvent {
  seq: number;
  barTimeMs: number;
  type: string;
  data: Record<string, unknown>;
}

export interface BacktestEventsPage {
  events: BacktestEvent[];
  nextAfter: number;
  state: string;
}

/** K线分段：rows = [openTime, open, high, low, close, volume]（含预热段） */
export interface BacktestKlinesPage {
  total: number;
  offset: number;
  rows: number[][];
}

export interface BacktestSummary {
  totalTrades: number;
  wins: number;
  losses: number;
  winRate: number;
  profitFactor: number;
  netProfit: number;
  totalFees: number;
  sharpeRatio: number;
  maxDrawdownPct: number;
  avgHoldBars: number;
  avgR: number;
  returnPct: number;
  finalEquity: number;
}

export interface BacktestTrade {
  /** 对应 klines 下标（含预热段偏移），图表 marker 直接定位 */
  openBarIndex: number;
  closeBarIndex: number;
  openTime: number;
  closeTime: number;
  side: 'LONG' | 'SHORT';
  entryPrice: number;
  exitPrice: number;
  quantity: number;
  leverage: number;
  pnl: number;
  fee: number;
  rMultiple: number | null;
  exitReason: string;
  maxFavorableR: number | null;
  maxAdverseR: number | null;
}

export interface BacktestResultPayload {
  taskId: string;
  strategyId: string;
  symbol: string;
  warmupBars: number;
  summary: BacktestSummary;
  trades: BacktestTrade[];
  /** 降采样权益曲线：[closeTimeMs, equity] */
  equity: [number, number][];
}

// ==================== LDC 瓜分活动 ====================

export interface CampaignScoreItem {
  /** 稳定标识：ROI25 / ROI50 / ROI100 / GODLY / SPOT / TRIPLE / PREDICTION / STOP_LOSS_HERO
   *  / PNL_PROFIT / PNL_LOSS / LIQ_TRIGGER / RESET_EXTRA / CHECKIN / STREAK / FIRST_COMMENT / VOTE */
  code: string;
  label: string;
  /** 达成次数；投票那条恒为 0（它按分不按次） */
  count: number;
  score: number;
}

export interface CampaignScore {
  userId: number;
  username: string | null;
  /** 参与名单已按"纯数字 linux_do_id"筛过，故恒为 true。前端不要据此分支 */
  claimable: boolean;
  tradeScore: number;
  dailyScore: number;
  voteScore: number;
  /** 强平扣分，负数 */
  penalty: number;
  finalScore: number;
  items: CampaignScoreItem[];
}

export interface CampaignVoteBoard {
  symbol: string;
  label: string;
  upCount: number;
  downCount: number;
  /** null = 今天还没投这个标的 */
  myDirection: 'UP' | 'DOWN' | null;
}

export interface MyCampaignView {
  campaignId: number;
  campaignName: string;
  startAt: string;
  endAt: string;
  prizePool: number;
  me: CampaignScore;
  /** 有资格参与分配的人的总分，也就是分配公式的分母 */
  eligibleTotal: number;
  estimatedLdc: number;
  /** 我的名次，从 1 起；0 = 还没上榜（一分没有的人不进榜） */
  rank: number;
  participants: number;
  checkedToday: boolean;
  voteBoard: CampaignVoteBoard[];
}

/**
 * 当前活动（/campaign/current），没有进行中的活动时为 null。
 * <p>活动页只用它的 status 判"结算了没"——MyCampaignView 里没有这个字段，
 * 而"还没结算"和"结算了但你一分没分到"在 /reward 里都是 null，不看状态区分不开。
 */
export interface CampaignInfo {
  id: number;
  code: string;
  name: string;
  startAt: string;
  endAt: string;
  prizePool: number;
  /** RUNNING 进行中 / SETTLING 已结算可领取（DONE 收尾后接口直接返回 null） */
  status: string;
}

export interface CampaignReward {
  ldcAmount: number;
  /** PENDING 待领取 / CLAIMED 已授权待发 / SUCCESS 已到账 / FAILED 发放失败（可重领） */
  status: string;
  externalRef: string | null;
  errorMsg: string | null;
  finalScore: number;
}
