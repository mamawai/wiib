/**
 * 手动复盘的撮合内核：纯函数、UI 之外零依赖，方便单独验算。
 *
 * 口径对齐回测引擎（BacktestTradingTools）：
 * - 手续费 taker 万5 双边——复盘里的开/平仓都是"看着收盘价点按钮"，全是市价语义；
 * - 单净仓全进全出：无仓可开多/开空，有仓只能全平，不加仓不部分平（简洁 > 拟真）；
 * - 权益 = 现金 + 保证金 + 未实现盈亏；权益 ≤ 0 即爆仓（不做维持保证金梯度）。
 */

export const TAKER_FEE = 0.0005;   // 对齐引擎 backtest.takerFeeRate 默认

export interface ReplayPosition {
  side: 'LONG' | 'SHORT';
  qty: number;
  entryPrice: number;
  /** 开仓时从现金划出的保证金 */
  margin: number;
  leverage: number;
  /** 回放数组下标（含上下文段偏移），图表 marker 直接定位 */
  openIndex: number;
  openTime: number;
}

export interface ReplayTrade {
  side: 'LONG' | 'SHORT';
  qty: number;
  leverage: number;
  entryPrice: number;
  exitPrice: number;
  /** 净盈亏（已扣开+平双边手续费，同引擎 trade.pnl 口径） */
  pnl: number;
  /** 开+平总手续费 */
  fee: number;
  openIndex: number;
  closeIndex: number;
  openTime: number;
  closeTime: number;
  reason: 'MANUAL' | 'LIQUIDATION' | 'END';
}

export interface ReplayState {
  /** 可用现金（不含已占用保证金） */
  cash: number;
  position: ReplayPosition | null;
  trades: ReplayTrade[];
  liquidated: boolean;
}

export function initialState(balance: number): ReplayState {
  return { cash: balance, position: null, trades: [], liquidated: false };
}

function unrealized(pos: ReplayPosition, price: number): number {
  const dir = pos.side === 'LONG' ? 1 : -1;
  return (price - pos.entryPrice) * pos.qty * dir;
}

/** 按最新价盯市的总权益 */
export function equity(s: ReplayState, price: number): number {
  return s.cash + (s.position ? s.position.margin + unrealized(s.position, price) : 0);
}

/**
 * 开仓：保证金 = 现金 × 比例 ÷ (1 + 杠杆×费率)。
 * 分母那截是给开仓费留位——100% 仓位时"保证金+手续费"恰好花光现金，不会负余额。
 */
export function open(s: ReplayState, side: 'LONG' | 'SHORT', pct: number, leverage: number,
                     price: number, index: number, time: number): ReplayState {
  if (s.position || s.liquidated || price <= 0) return s;
  const margin = s.cash * pct / (1 + leverage * TAKER_FEE);
  const notional = margin * leverage;
  const fee = notional * TAKER_FEE;
  const qty = notional / price;
  if (qty <= 0 || margin <= 0) return s;
  return {
    ...s,
    cash: s.cash - margin - fee,
    position: { side, qty, entryPrice: price, margin, leverage, openIndex: index, openTime: time },
  };
}

/** 平仓（手动/爆仓/收盘清算共用）；爆仓时现金落地为 0，不出现负权益 */
export function close(s: ReplayState, price: number, index: number, time: number,
                      reason: ReplayTrade['reason'] = 'MANUAL'): ReplayState {
  const pos = s.position;
  if (!pos) return s;
  const raw = unrealized(pos, price);
  const openFee = pos.qty * pos.entryPrice * TAKER_FEE;
  const closeFee = pos.qty * price * TAKER_FEE;
  const cash = Math.max(0, s.cash + pos.margin + raw - closeFee);
  const trade: ReplayTrade = {
    side: pos.side, qty: pos.qty, leverage: pos.leverage,
    entryPrice: pos.entryPrice, exitPrice: price,
    pnl: raw - openFee - closeFee, fee: openFee + closeFee,
    openIndex: pos.openIndex, closeIndex: index, openTime: pos.openTime, closeTime: time,
    reason,
  };
  return {
    cash,
    position: null,
    trades: [...s.trades, trade],
    liquidated: reason === 'LIQUIDATION' ? true : s.liquidated,
  };
}

/** 每根新 bar 收盘调用：盯市 + 爆仓判定。爆仓按当根收盘价强平、本局结束 */
export function step(s: ReplayState, price: number, index: number, time: number): ReplayState {
  if (s.position && equity(s, price) <= 0) {
    return close(s, price, index, time, 'LIQUIDATION');
  }
  return s;
}

/** 走完全部 bar 仍有持仓：按最后收盘价清算（同引擎 FORCE_CLOSE 语义） */
export function endSession(s: ReplayState, price: number, index: number, time: number): ReplayState {
  return s.position ? close(s, price, index, time, 'END') : s;
}

export interface ReplayStats {
  totalTrades: number;
  wins: number;
  losses: number;
  winRate: number;
  netProfit: number;
  totalFees: number;
  returnPct: number;
  maxDrawdownPct: number;
  finalEquity: number;
}

/** 结算统计；equitySeries = 每根已揭示 bar 收盘后的权益序列 */
export function stats(s: ReplayState, initialBalance: number, equitySeries: number[]): ReplayStats {
  const wins = s.trades.filter(t => t.pnl > 0).length;
  const losses = s.trades.filter(t => t.pnl < 0).length;
  const netProfit = s.trades.reduce((a, t) => a + t.pnl, 0);
  let peak = initialBalance, maxDd = 0;
  for (const eq of equitySeries) {
    if (eq > peak) peak = eq;
    if (peak > 0) maxDd = Math.max(maxDd, (peak - eq) / peak);
  }
  const finalEquity = equitySeries.length ? equitySeries[equitySeries.length - 1] : initialBalance;
  return {
    totalTrades: s.trades.length,
    wins, losses,
    winRate: s.trades.length ? wins / s.trades.length : 0,
    netProfit,
    totalFees: s.trades.reduce((a, t) => a + t.fee, 0),
    returnPct: initialBalance > 0 ? (finalEquity - initialBalance) / initialBalance : 0,
    maxDrawdownPct: maxDd,
    finalEquity,
  };
}
