export interface Stock {
  id: number;
  code: string;
  name: string;
  industry?: string;
  price: number;
  openPrice?: number;
  highPrice?: number;
  lowPrice?: number;
  prevClose?: number;
  change: number;
  changePct: number;
  marketCap?: number;
  peRatio?: number;
  companyDesc?: string;
  trendList?: number[];
}

export interface Quote {
  code: string;
  name: string;
  price: number;
  open: number;
  high: number;
  low: number;
  prevClose: number;
  timestamp: number;
}

export interface User {
  id: number;
  username: string;
  avatar?: string;
  balance: number;
  frozenBalance: number;
  positionMarketValue: number;
  pendingSettlement: number;
  marginLoanPrincipal: number;
  marginInterestAccrued: number;
  bankrupt: boolean;
  bankruptCount: number;
  bankruptResetDate?: string;
  totalAssets: number;
  profit: number;
  profitPct: number;
}

export interface Position {
  id: number;
  stockId: number;
  stockCode: string;
  stockName: string;
  quantity: number;
  avgCost: number;
  currentPrice: number;
  marketValue: number;
  profit: number;
  profitPct: number;
}

export interface OrderRequest {
  stockId: number;
  quantity: number;
  orderType: 'MARKET' | 'LIMIT';
  limitPrice?: number;
  leverageMultiple?: number;
  useBuffId?: number;
}

export interface Order {
  orderId: number;
  stockCode: string;
  stockName: string;
  orderSide: string;
  orderType: string;
  status: string;
  quantity: number;
  limitPrice?: number;
  filledPrice?: number;
  filledAmount?: number;
  commission?: number;
  triggerPrice?: number;
  triggeredAt?: string;
  expireAt?: string;
  createdAt: string;
}

export interface DayTick {
  time: string;
  price: number;
}

export interface Settlement {
  id: number;
  orderId: number;
  amount: number;
  settleTime?: string;
  createdAt: string;
  status: string;
}

// MyBatis-Plus分页结果
export interface PageResult<T> {
  records: T[];
  total: number;
  size: number;
  current: number;
  pages: number;
}

export interface News {
  id: number;
  stockCode: string;
  title: string;
  content: string;
  newsType: string;
  publishTime: string;
}

// WebSocket事件类型（与后端对应）

export interface AssetChangeEvent {
  userId: number;
  balance: number;
  frozenBalance: number;
  positionMarketValue: number;
  pendingSettlement: number;
  marginLoanPrincipal: number;
  marginInterestAccrued: number;
  bankrupt: boolean;
  bankruptCount: number;
  bankruptResetDate?: string;
  totalAssets: number;
  profit: number;
  profitPct: number;
  reason: string;
  timestamp: number;
}

export interface PositionChangeEvent {
  userId: number;
  stockId: number;
  stockCode: string;
  stockName: string;
  quantity: number;
  frozenQuantity: number;
  avgCost: number;
  currentPrice: number;
  marketValue: number;
  profit: number;
  profitPct: number;
  changeType: 'BUY' | 'SELL' | 'FREEZE' | 'UNFREEZE';
  changeQuantity: number;
  timestamp: number;
}

export interface OrderStatusEvent {
  userId: number;
  orderId: number;
  stockCode: string;
  stockName: string;
  orderType: 'MARKET' | 'LIMIT';
  orderSide: 'BUY' | 'SELL';
  quantity: number;
  price: number;
  oldStatus: string;
  newStatus: 'PENDING' | 'TRIGGERED' | 'FILLED' | 'CANCELLED' | 'EXPIRED';
  executePrice?: number;
  timestamp: number;
}

export interface RankingItem {
  rank: number;
  userId: number;
  username: string;
  avatar?: string;
  totalAssets: number;
  profitPct: number;
}

// ========== 期权相关类型 ==========
export interface OptionChainItem {
  contractId: number;
  stockId: number;
  optionType: 'CALL' | 'PUT';
  strike: number;
  expireAt: string;
}

export interface OptionQuote {
  contractId: number;
  stockCode: string;
  stockName: string;
  optionType: 'CALL' | 'PUT';
  strike: number;
  expireAt: string;
  premium: number;
  intrinsicValue: number;
  timeValue: number;
  spotPrice: number;
  sigma: number;
}

export interface OptionPosition {
  positionId: number;
  contractId: number;
  stockId: number;
  stockCode: string;
  stockName: string;
  optionType: 'CALL' | 'PUT';
  strike: number;
  expireAt: string;
  quantity: number;
  avgCost: number;
  currentPremium: number;
  marketValue: number;
  pnl: number;
  spotPrice: number;
}

export interface OptionOrder {
  orderId: number;
  stockName: string;
  optionType: 'CALL' | 'PUT';
  strike: number;
  expireAt: string;
  orderSide: 'BTO' | 'STC';
  quantity: number;
  filledPrice: number;
  filledAmount: number;
  commission: number;
  status: string;
}

export interface OptionOrderRequest {
  contractId: number;
  quantity: number;
}

export interface OptionOrderResult {
  orderId: number;
  status: string;
  filledPrice: number;
  filledAmount: number;
  commission: number;
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
