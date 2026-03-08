import { useEffect, useRef, useState, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { useUserStore } from '../stores/userStore';
import { userApi, orderApi, settlementApi, cryptoOrderApi, cryptoApi, futuresApi } from '../api';
import { Card, CardContent } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { Skeleton } from '../components/ui/skeleton';
import { Dialog, DialogContent, DialogFooter, DialogHeader } from '../components/ui/dialog';
import { useToast } from '../components/ui/use-toast';
import { PortfolioChart } from '../components/PortfolioChart';
import { useUserEvents } from '../hooks/useUserEvents';
import { cn } from '../lib/utils';
import {
  Wallet,
  TrendingUp,
  TrendingDown,
  Briefcase,
  ClipboardList,
  Clock,
  X,
  RefreshCcw,
  ChevronRight,
  PieChart,
  BarChart3,
  Bitcoin,
  Coins,
  CircleDollarSign,
  Scale,
} from 'lucide-react';
import type { Position, Order, Settlement, AssetChangeEvent, PositionChangeEvent, OrderStatusEvent, CryptoPosition, FuturesPosition } from '../types';
import { useDedupedEffect } from '../hooks/useDedupedEffect';

const CRYPTO_META: Record<string, { name: string; icon: typeof Bitcoin; color: string; bg: string }> = {
  BTCUSDT: { name: 'BTC', icon: Bitcoin, color: 'text-orange-500', bg: 'bg-orange-500/10' },
  PAXGUSDT: { name: 'PAXG', icon: Coins, color: 'text-yellow-500', bg: 'bg-yellow-500/10' },
};

interface CryptoRow extends CryptoPosition {
  currentPrice: number;
  marketValue: number;
  profit: number;
  profitPct: number;
}

function fmt(n: number) {
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

export function Portfolio() {
  const navigate = useNavigate();
  const { user } = useUserStore();
  const { toast } = useToast();
  const [positions, setPositions] = useState<Position[]>([]);
  const [cryptoRows, setCryptoRows] = useState<CryptoRow[]>([]);
  const [futuresPositions, setFuturesPositions] = useState<FuturesPosition[]>([]);
  const [orders, setOrders] = useState<Order[]>([]);
  const [orderTotal, setOrderTotal] = useState(0);
  const [orderPage, setOrderPage] = useState(1);
  const [orderPageSize, setOrderPageSize] = useState(10);
  const [settlements, setSettlements] = useState<Settlement[]>([]);
  const [tab, setTab] = useState<'positions' | 'orders' | 'settlements'>('positions');
  const [loading, setLoading] = useState(true);
  const activeRequestKey = useRef('');
  const [refreshNonce, setRefreshNonce] = useState(0);
  const [cancelOrder, setCancelOrder] = useState<Order | null>(null);
  const [cancelSubmitting, setCancelSubmitting] = useState(false);
  const [showChart, setShowChart] = useState(true);
  const [chartReady, setChartReady] = useState(false);
  const orderPageRef = useRef(orderPage);
  const orderPageSizeRef = useRef(orderPageSize);

  useEffect(() => {
    orderPageRef.current = orderPage;
  }, [orderPage]);

  useEffect(() => {
    orderPageSizeRef.current = orderPageSize;
  }, [orderPageSize]);

  // WS事件
  useUserEvents(user?.id, {
    onAssetChange: (event: AssetChangeEvent) => {
      useUserStore.setState((state) => {
        if (!state.user) return {};
        return {
          user: {
            ...state.user,
            balance: event.balance,
            frozenBalance: event.frozenBalance,
            positionMarketValue: event.positionMarketValue,
            pendingSettlement: event.pendingSettlement,
            marginLoanPrincipal: event.marginLoanPrincipal,
            marginInterestAccrued: event.marginInterestAccrued,
            bankrupt: event.bankrupt,
            bankruptCount: event.bankruptCount,
            bankruptResetDate: event.bankruptResetDate,
            totalAssets: event.totalAssets,
            profit: event.profit,
            profitPct: event.profitPct,
          },
        };
      });
    },
    onPositionChange: (event: PositionChangeEvent) => {
      setPositions((prev) => {
        const idx = prev.findIndex((p) => p.stockId === event.stockId);
        if (event.quantity === 0 && event.frozenQuantity === 0) {
          return prev.filter((p) => p.stockId !== event.stockId);
        }
        const updated: Position = {
          id: idx >= 0 ? prev[idx].id : 0,
          stockId: event.stockId,
          stockCode: event.stockCode,
          stockName: event.stockName,
          quantity: event.quantity,
          avgCost: event.avgCost,
          currentPrice: event.currentPrice,
          marketValue: event.marketValue,
          profit: event.profit,
          profitPct: event.profitPct,
        };
        if (idx >= 0) {
          const next = [...prev];
          next[idx] = updated;
          return next;
        }
        return [...prev, updated];
      });
    },
    onOrderStatus: (event: OrderStatusEvent) => {
      setOrders((prev) => {
        const idx = prev.findIndex((o) => o.orderId === event.orderId);
        if (idx >= 0) {
          const next = [...prev];
          next[idx] = {
            ...next[idx],
            status: event.newStatus,
            filledPrice: event.executePrice ?? next[idx].filledPrice,
          };
          return next;
        }
        if (orderPageRef.current === 1) {
             const newOrder: Order = {
              orderId: event.orderId,
              stockCode: event.stockCode,
              stockName: event.stockName,
              orderSide: event.orderSide,
              orderType: event.orderType,
              status: event.newStatus,
              quantity: event.quantity,
              limitPrice: event.orderType === 'LIMIT' ? event.price : undefined,
              filledPrice: event.executePrice,
              createdAt: new Date(event.timestamp).toISOString(),
            };
            const next = [newOrder, ...prev];
            if (next.length > orderPageSizeRef.current) next.pop();
            setOrderTotal(t => t + 1);
            return next;
        }
        return prev;
      });
    },
  });

  useEffect(() => {
    if (!user) {
      navigate('/login');
    }
  }, [user, navigate]);

  const loadCryptoPositions = useCallback(async () => {
    try {
      const cps = await cryptoOrderApi.positions();
      if (!cps || cps.length === 0) { setCryptoRows([]); setChartReady(true); return; }
      const rows = await Promise.all(cps.map(async (cp) => {
        let currentPrice = 0;
        try {
          const res = await cryptoApi.price(cp.symbol);
          if (res && res.price) currentPrice = parseFloat(res.price);
        } catch { /* skip */ }
        const marketValue = currentPrice * cp.quantity;
        const costValue = cp.avgCost * cp.quantity;
        const profit = marketValue - costValue;
        const profitPct = costValue > 0 ? (profit / costValue) * 100 : 0;
        return { ...cp, currentPrice, marketValue, profit, profitPct };
      }));
      setCryptoRows(rows);
    } catch {
      setCryptoRows([]);
    } finally {
      setChartReady(true);
    }
  }, []);

  const requestKey = user ? `portfolio:user=${user.id}:refresh=${refreshNonce}:page=${orderPage}:size=${orderPageSize}` : null;
  useDedupedEffect(
    requestKey,
    () => {
      if (!user) return;
      let cancelled = false;
      activeRequestKey.current = requestKey ?? '';

      setLoading(true);
      setChartReady(false);
      Promise.all([
          userApi.portfolio(),
          userApi.positions(),
          orderApi.list(undefined, orderPage, orderPageSize),
          settlementApi.pending(),
      ])
        .then(([u, p, o, s]) => {
          if (cancelled) return;
          if (activeRequestKey.current !== (requestKey ?? '')) return;
          useUserStore.setState({ user: u });
          setPositions(p);
          setOrders(o.records);
          setOrderTotal(o.total);
          setSettlements(s);
          loadCryptoPositions();
          futuresApi.positions().then(setFuturesPositions).catch(() => setFuturesPositions([]));
        })
        .catch(() => {
          if (cancelled) return;
          if (activeRequestKey.current !== (requestKey ?? '')) return;
          setPositions([]);
          setOrders([]);
          setOrderTotal(0);
          setSettlements([]);
          setCryptoRows([]);
          setFuturesPositions([]);
          toast('获取账户数据失败', 'error', { description: '请稍后重试' });
        })
        .finally(() => {
          if (cancelled) return;
          if (activeRequestKey.current !== (requestKey ?? '')) return;
          setLoading(false);
        });

      return () => {
        cancelled = true;
      };
    },
    [requestKey],
  );

  const handleCancelOrder = async (orderId: number) => {
    try {
      await orderApi.cancel(orderId);
      setOrders((prev) => prev.filter((o) => o.orderId !== orderId));
      toast('订单已取消', 'success');
      return true;
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : '取消失败';
      toast(msg, 'error');
      return false;
    }
  };

  if (!user) return null;

  const isProfit = user.profit >= 0;
  const stockTotal = positions.reduce((s, p) => s + (p.marketValue || 0), 0);
  const cryptoTotal = cryptoRows.reduce((s, c) => s + c.marketValue, 0);
  const stockProfit = positions.reduce((s, p) => s + (p.profit || 0), 0);
  const cryptoProfit = cryptoRows.reduce((s, c) => s + c.profit, 0);
  const futuresMargin = futuresPositions.reduce((s, f) => s + f.margin, 0);
  const futuresProfit = futuresPositions.reduce((s, f) => s + f.unrealizedPnl, 0);
  const futuresTotal = futuresMargin + futuresProfit;
  const hasStock = positions.length > 0;
  const hasCrypto = cryptoRows.length > 0;
  const hasFutures = futuresPositions.length > 0;
  const hasPositions = hasStock || hasCrypto || hasFutures;

  return (
    <div className="max-w-4xl mx-auto p-4 space-y-4">
      {user.bankrupt && (
        <Card className="border-destructive/30 bg-destructive/5">
          <CardContent className="p-4 text-sm">
            <div className="font-medium text-destructive">已爆仓，交易已禁用</div>
            <div className="mt-1 text-muted-foreground">
              破产次数 {user.bankruptCount} · 将在 {user.bankruptResetDate ?? '下个交易日'} 09:00 恢复初始资金
            </div>
          </CardContent>
        </Card>
      )}

      {/* 总资产概览 */}
      <div className="relative rounded-2xl border border-primary/15 overflow-hidden"
        style={{
          backgroundImage: `linear-gradient(135deg, color-mix(in oklab, var(--color-primary) 12%, var(--color-card)) 0%, var(--color-card) 60%), repeating-linear-gradient(0deg, transparent, transparent 24px, color-mix(in oklab, var(--color-border) 30%, transparent) 24px, color-mix(in oklab, var(--color-border) 30%, transparent) 25px), repeating-linear-gradient(90deg, transparent, transparent 24px, color-mix(in oklab, var(--color-border) 30%, transparent) 24px, color-mix(in oklab, var(--color-border) 30%, transparent) 25px)`,
        }}
      >
        {/* 顶部光晕 */}
        <div className="absolute top-0 left-0 right-0 h-px bg-gradient-to-r from-transparent via-primary/40 to-transparent" />

        <div className="relative p-5">
          {/* 头部行：头像+用户名 / 切换按钮 */}
          <div className="flex justify-between items-center mb-5">
            <div className="flex items-center gap-3">
              {user.avatar ? (
                <img src={user.avatar} alt="" className="w-10 h-10 rounded-full ring-1 ring-primary/30 ring-offset-1 ring-offset-card" />
              ) : (
                <div className="w-10 h-10 rounded-full bg-primary/15 flex items-center justify-center ring-1 ring-primary/20">
                  <Wallet className="w-5 h-5 text-primary" />
                </div>
              )}
              <div>
                <h2 className="text-sm font-semibold leading-tight">{user.username}</h2>
                <p className="text-[11px] text-muted-foreground leading-tight mt-0.5 tracking-wide uppercase">模拟账户</p>
              </div>
            </div>
            <button
              onClick={() => setShowChart(!showChart)}
              className={cn(
                "flex items-center gap-1.5 px-2.5 py-1.5 rounded-lg text-xs font-medium transition-all",
                showChart
                  ? "bg-primary/15 text-primary border border-primary/25"
                  : "text-muted-foreground border border-border/50 hover:border-border hover:text-foreground"
              )}
            >
              <PieChart className="w-3.5 h-3.5" />
              {showChart ? '隐藏' : '分布'}
            </button>
          </div>

          {/* 主体：饼图左 + 数据右 */}
          <div className="flex flex-col sm:flex-row gap-0">
            {/* 左：饼图 */}
            {showChart && (
              <div className="sm:w-[48%] sm:border-r border-border/30 sm:pr-4 flex items-center justify-center">
                {chartReady ? (
                  <div className="w-full animate-in fade-in duration-300">
                    <PortfolioChart
                      positions={positions}
                      cryptoPositions={cryptoRows}
                      balance={user.balance}
                      pendingSettlement={user.pendingSettlement}
                    />
                  </div>
                ) : (
                  <div className="w-full h-48 sm:h-56 flex items-center justify-center">
                    <svg className="w-7 h-7 text-muted-foreground/40 animate-spin" viewBox="0 0 24 24" fill="none">
                      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="2" />
                      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z" />
                    </svg>
                  </div>
                )}
              </div>
            )}

            {/* 右：指标区 */}
            <div className={cn("flex flex-col justify-center", showChart ? "sm:flex-1 sm:pl-5 pt-3 sm:pt-0" : "w-full")}>
              {/* 总资产主指标 */}
              <div className="mb-4">
                <span className="text-[11px] text-muted-foreground uppercase tracking-widest">总资产</span>
                <div className="flex items-baseline gap-2 mt-0.5">
                  <span className="text-3xl font-bold tabular-nums tracking-tight">{fmt(user.totalAssets)}</span>
                  <span className="text-xs text-muted-foreground font-normal">USDT</span>
                </div>
              </div>

              {/* 分隔线 */}
              <div className="h-px bg-gradient-to-r from-border/60 to-transparent mb-3" />

              {/* 次要指标列表 */}
              <div className="space-y-0 divide-y divide-border/20">
                <div className="flex items-center justify-between py-2">
                  <span className="text-[12px] text-muted-foreground">可用余额</span>
                  <span className="text-[13px] font-semibold tabular-nums">{fmt(user.balance)}</span>
                </div>

                <div className="flex items-center justify-between py-2">
                  <span className="text-[12px] text-muted-foreground">总盈亏</span>
                  <div className={cn(
                    "flex items-center gap-1 px-2 py-0.5 rounded-md text-[12px] font-bold tabular-nums",
                    isProfit ? "bg-red-500/10 text-red-400" : "bg-green-500/10 text-green-400"
                  )}>
                    {isProfit
                      ? <TrendingUp className="w-3 h-3" />
                      : <TrendingDown className="w-3 h-3" />
                    }
                    {isProfit ? '+' : ''}{user.profitPct.toFixed(2)}%
                    <span className="opacity-60 font-normal ml-0.5">({isProfit ? '+' : ''}{fmt(user.profit)})</span>
                  </div>
                </div>

                <div className="flex items-center justify-between py-2">
                  <span className="text-[12px] text-muted-foreground">杠杆借款</span>
                  <span className={cn(
                    "text-[13px] font-semibold tabular-nums",
                    user.marginLoanPrincipal > 0 ? "text-warning" : "text-muted-foreground"
                  )}>{fmt(user.marginLoanPrincipal)}</span>
                </div>

                <div className="flex items-center justify-between py-2">
                  <span className="text-[12px] text-muted-foreground">应计利息</span>
                  <span className={cn(
                    "text-[13px] font-semibold tabular-nums",
                    user.marginInterestAccrued > 0 ? "text-destructive/80" : "text-muted-foreground"
                  )}>{fmt(user.marginInterestAccrued)}</span>
                </div>

                {hasFutures && (
                  <>
                    <div className="flex items-center justify-between py-2">
                      <span className="text-[12px] text-muted-foreground">合约保证金</span>
                      <span className="text-[13px] font-semibold tabular-nums">{fmt(futuresMargin)}</span>
                    </div>
                    <div className="flex items-center justify-between py-2">
                      <span className="text-[12px] text-muted-foreground">合约浮盈</span>
                      <span className={cn(
                        "text-[13px] font-semibold tabular-nums",
                        futuresProfit >= 0 ? "text-red-400" : "text-green-400"
                      )}>{futuresProfit >= 0 ? '+' : ''}{fmt(futuresProfit)}</span>
                    </div>
                  </>
                )}
              </div>
            </div>
          </div>
        </div>

        {/* 底部线 */}
        <div className="h-px bg-gradient-to-r from-transparent via-border/40 to-transparent" />
      </div>

      {/* Tabs */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
        <div className="flex gap-2 p-1 bg-card rounded-lg border">
          <TabButton active={tab === 'positions'} onClick={() => setTab('positions')} icon={<Briefcase className="w-4 h-4" />}>
            持仓
          </TabButton>
          <TabButton active={tab === 'orders'} onClick={() => setTab('orders')} icon={<ClipboardList className="w-4 h-4" />}>
            订单
          </TabButton>
          <TabButton active={tab === 'settlements'} onClick={() => setTab('settlements')} icon={<Clock className="w-4 h-4" />}>
            待结算
          </TabButton>
        </div>

        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            setRefreshNonce((n) => n + 1);
            toast('已刷新', 'info');
          }}
        >
          <RefreshCcw className="w-4 h-4" />
          刷新
        </Button>
      </div>

      {/* Tab Content */}
      {tab === 'positions' && (
        <div className="space-y-3">
          {loading ? (
            <Card>
              <CardContent className="p-5 space-y-5">
                {Array.from({ length: 4 }).map((_, i) => (
                  <div key={i} className="flex items-center gap-3">
                    <Skeleton className="h-9 w-9 rounded-lg shrink-0" />
                    <div className="flex-1 space-y-2">
                      <Skeleton className="h-4 w-28" />
                      <Skeleton className="h-3 w-40" />
                    </div>
                    <div className="space-y-2 text-right">
                      <Skeleton className="h-4 w-20 ml-auto" />
                      <Skeleton className="h-3 w-16 ml-auto" />
                    </div>
                  </div>
                ))}
              </CardContent>
            </Card>
          ) : !hasPositions ? (
            <Card><CardContent className="p-0"><EmptyState icon={<Briefcase />} text="暂无持仓" /></CardContent></Card>
          ) : (
            <>
              {/* 股票持仓 */}
              {hasStock && (
                <Card className="overflow-hidden">
                  <div className="px-4 py-3 flex items-center justify-between border-b border-border/40 bg-gradient-to-r from-blue-500/[0.06] to-transparent">
                    <div className="flex items-center gap-2.5">
                      <div className="w-8 h-8 rounded-lg bg-blue-500/10 flex items-center justify-center ring-1 ring-blue-500/20">
                        <BarChart3 className="w-4 h-4 text-blue-400" />
                      </div>
                      <div>
                        <span className="text-sm font-semibold tracking-tight">股票持仓</span>
                        <span className="text-[11px] text-muted-foreground ml-1.5">{positions.length}只</span>
                      </div>
                    </div>
                    <div className="text-right">
                      <div className="text-[13px] font-bold tabular-nums tracking-tight">{fmt(stockTotal)}</div>
                      <div className={cn("text-[11px] tabular-nums font-medium", stockProfit >= 0 ? "text-red-400" : "text-green-400")}>
                        {stockProfit >= 0 ? '+' : ''}{fmt(stockProfit)}
                      </div>
                    </div>
                  </div>
                  <CardContent className="p-0 divide-y divide-border/30">
                    {positions.map((p) => {
                      const up = p.profit >= 0;
                      return (
                        <button
                          type="button"
                          key={p.id}
                          onClick={() => navigate(`/stock/${p.stockId}`)}
                          className="w-full text-left px-4 py-3.5 cursor-pointer hover:bg-accent/40 active:bg-accent/60 transition-colors group focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset"
                        >
                          <div className="flex items-center justify-between gap-3">
                            <div className="min-w-0 flex-1">
                              <div className="flex items-center gap-1.5 mb-1">
                                <span className="font-semibold text-[13px] truncate group-hover:text-primary transition-colors">{p.stockName}</span>
                                <span className="text-[11px] text-muted-foreground/70 shrink-0">{p.stockCode}</span>
                              </div>
                              <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
                                <span>{p.quantity}股</span>
                                <span className="text-border">·</span>
                                <span>成本 {p.avgCost.toFixed(2)}</span>
                                <span className="text-border">·</span>
                                <span>现价 {p.currentPrice.toFixed(2)}</span>
                              </div>
                            </div>
                            <div className="text-right shrink-0 flex items-center gap-2">
                              <div>
                                <div className={cn("text-[13px] font-bold tabular-nums", up ? "text-red-400" : "text-green-400")}>
                                  {up ? '+' : ''}{p.profit.toFixed(2)}
                                </div>
                                <div className={cn("text-[11px] tabular-nums font-medium", up ? "text-red-400/70" : "text-green-400/70")}>
                                  {up ? '+' : ''}{p.profitPct.toFixed(2)}%
                                </div>
                              </div>
                              <ChevronRight className="w-3.5 h-3.5 text-muted-foreground/40 group-hover:text-primary/60 transition-colors" />
                            </div>
                          </div>
                        </button>
                      );
                    })}
                  </CardContent>
                </Card>
              )}

              {/* 币种持仓 */}
              {hasCrypto && (
                <Card className="overflow-hidden">
                  <div className="px-4 py-3 flex items-center justify-between border-b border-border/40 bg-gradient-to-r from-orange-500/[0.06] to-transparent">
                    <div className="flex items-center gap-2.5">
                      <div className="w-8 h-8 rounded-lg bg-orange-500/10 flex items-center justify-center ring-1 ring-orange-500/20">
                        <CircleDollarSign className="w-4 h-4 text-orange-400" />
                      </div>
                      <div>
                        <span className="text-sm font-semibold tracking-tight">币种持仓</span>
                        <span className="text-[11px] text-muted-foreground ml-1.5">{cryptoRows.length}种</span>
                      </div>
                    </div>
                    <div className="text-right">
                      <div className="text-[13px] font-bold tabular-nums tracking-tight">{fmt(cryptoTotal)}</div>
                      <div className={cn("text-[11px] tabular-nums font-medium", cryptoProfit >= 0 ? "text-red-400" : "text-green-400")}>
                        {cryptoProfit >= 0 ? '+' : ''}{fmt(cryptoProfit)}
                      </div>
                    </div>
                  </div>
                  <CardContent className="p-0 divide-y divide-border/30">
                    {cryptoRows.map((c) => {
                      const meta = CRYPTO_META[c.symbol];
                      const Icon = meta?.icon ?? Coins;
                      const up = c.profit >= 0;
                      return (
                        <button
                          type="button"
                          key={c.id}
                          onClick={() => navigate(`/coin/${c.symbol}`)}
                          className="w-full text-left px-4 py-3.5 cursor-pointer hover:bg-accent/40 active:bg-accent/60 transition-colors group focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset"
                        >
                          <div className="flex items-center justify-between gap-3">
                            <div className="flex items-center gap-2.5 min-w-0 flex-1">
                              <div className={cn("w-8 h-8 rounded-lg flex items-center justify-center shrink-0 ring-1", meta?.bg ?? 'bg-muted', meta ? `ring-current/20 ${meta.color}` : 'ring-border')}>
                                <Icon className={cn("w-4 h-4", meta?.color ?? 'text-muted-foreground')} />
                              </div>
                              <div className="min-w-0">
                                <div className="flex items-center gap-1.5 mb-1">
                                  <span className="font-semibold text-[13px] group-hover:text-primary transition-colors">{meta?.name ?? c.symbol}</span>
                                  <span className="text-[11px] text-muted-foreground/60">/ USDT</span>
                                  {c.symbol === 'PAXGUSDT' && (
                                    <span className="text-[10px] text-yellow-500/60">1枚=1盎司（31.1g）</span>
                                  )}
                                </div>
                                <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
                                  <span>持有 {c.quantity}{c.symbol === 'PAXGUSDT' && <span className="text-yellow-500/60 ml-0.5">约合 {(c.quantity * 31.1035).toFixed(1)} 克</span>}</span>
                                  <span className="text-border">·</span>
                                  <span>均价 {fmt(c.avgCost)}</span>
                                  {c.currentPrice > 0 && (<><span className="text-border">·</span><span>现价 {fmt(c.currentPrice)}</span></>)}
                                </div>
                              </div>
                            </div>
                            <div className="text-right shrink-0 flex items-center gap-2">
                              <div>
                                <div className={cn("text-[13px] font-bold tabular-nums", up ? "text-red-400" : "text-green-400")}>
                                  {up ? '+' : ''}{fmt(c.profit)}
                                </div>
                                <div className={cn("text-[11px] tabular-nums font-medium", up ? "text-red-400/70" : "text-green-400/70")}>
                                  {up ? '+' : ''}{c.profitPct.toFixed(2)}%
                                </div>
                              </div>
                              <ChevronRight className="w-3.5 h-3.5 text-muted-foreground/40 group-hover:text-primary/60 transition-colors" />
                            </div>
                          </div>
                        </button>
                      );
                    })}
                  </CardContent>
                </Card>
              )}

              {/* 合约持仓 */}
              {hasFutures && (
                <Card className="overflow-hidden">
                  <div className="px-4 py-3 flex items-center justify-between border-b border-border/40 bg-gradient-to-r from-purple-500/[0.06] to-transparent">
                    <div className="flex items-center gap-2.5">
                      <div className="w-8 h-8 rounded-lg bg-purple-500/10 flex items-center justify-center ring-1 ring-purple-500/20">
                        <Scale className="w-4 h-4 text-purple-400" />
                      </div>
                      <div>
                        <span className="text-sm font-semibold tracking-tight">合约持仓</span>
                        <span className="text-[11px] text-muted-foreground ml-1.5">{futuresPositions.length}个</span>
                      </div>
                    </div>
                    <div className="text-right">
                      <div className="text-[11px] text-muted-foreground">保证金 <span className="text-foreground font-bold tabular-nums">{fmt(futuresMargin)}</span></div>
                      <div className={cn("text-[11px] tabular-nums font-medium", futuresProfit >= 0 ? "text-red-400" : "text-green-400")}>
                        浮盈 {futuresProfit >= 0 ? '+' : ''}{fmt(futuresProfit)}
                      </div>
                    </div>
                  </div>
                  <CardContent className="p-0 divide-y divide-border/30">
                    {futuresPositions.map((f) => {
                      const up = f.unrealizedPnl >= 0;
                      const isLong = f.side === 'LONG';
                      const meta = CRYPTO_META[f.symbol];
                      const Icon = meta?.icon ?? Coins;
                      return (
                        <button
                          type="button"
                          key={f.id}
                          onClick={() => navigate(`/coin/${f.symbol}`)}
                          className="w-full text-left px-4 py-3.5 cursor-pointer hover:bg-accent/40 active:bg-accent/60 transition-colors group focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset"
                        >
                          <div className="flex items-center justify-between gap-3">
                            <div className="flex items-center gap-2.5 min-w-0 flex-1">
                              <div className={cn("w-8 h-8 rounded-lg flex items-center justify-center shrink-0 ring-1", meta?.bg ?? 'bg-muted', meta ? `ring-current/20 ${meta.color}` : 'ring-border')}>
                                <Icon className={cn("w-4 h-4", meta?.color ?? 'text-muted-foreground')} />
                              </div>
                              <div className="min-w-0">
                                <div className="flex items-center gap-1.5 mb-1">
                                  <span className="font-semibold text-[13px] group-hover:text-primary transition-colors">{meta?.name ?? f.symbol}</span>
                                  <Badge className={cn("text-[9px] px-1 py-0", isLong ? "bg-red-500" : "bg-green-500")}>{isLong ? '多' : '空'}</Badge>
                                  <span className="text-[11px] text-muted-foreground">{f.leverage}x</span>
                                </div>
                                <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
                                  <span>数量 {f.quantity}</span>
                                  <span className="text-border">·</span>
                                  <span>开仓 {fmt(f.entryPrice)}</span>
                                  <span className="text-border">·</span>
                                  <span>保证金 {fmt(f.margin)}</span>
                                </div>
                              </div>
                            </div>
                            <div className="text-right shrink-0 flex items-center gap-2">
                              <div>
                                <div className={cn("text-[13px] font-bold tabular-nums", up ? "text-red-400" : "text-green-400")}>
                                  {up ? '+' : ''}{fmt(f.unrealizedPnl)}
                                </div>
                                <div className={cn("text-[11px] tabular-nums font-medium", up ? "text-red-400/70" : "text-green-400/70")}>
                                  {up ? '+' : ''}{f.unrealizedPnlPct.toFixed(2)}%
                                </div>
                              </div>
                              <ChevronRight className="w-3.5 h-3.5 text-muted-foreground/40 group-hover:text-primary/60 transition-colors" />
                            </div>
                          </div>
                        </button>
                      );
                    })}
                  </CardContent>
                </Card>
              )}

              {/* 合计汇总 */}
              {[hasStock, hasCrypto, hasFutures].filter(Boolean).length > 1 && (() => {
                const allProfit = stockProfit + cryptoProfit + futuresProfit;
                const allTotal = stockTotal + cryptoTotal + futuresTotal;
                const up = allProfit >= 0;
                return (
                  <div className="rounded-xl border border-dashed border-border/60 bg-card/50 backdrop-blur-sm px-4 py-3 flex items-center justify-between">
                    <div className="flex items-center gap-2 text-sm text-muted-foreground">
                      <Briefcase className="w-3.5 h-3.5" />
                      <span>持仓合计</span>
                    </div>
                    <div className="flex items-center gap-3">
                      <span className="text-sm font-bold tabular-nums">{fmt(allTotal)}</span>
                      <span className={cn("text-xs font-semibold tabular-nums px-1.5 py-0.5 rounded", up ? "text-red-400 bg-red-500/10" : "text-green-400 bg-green-500/10")}>
                        {up ? '+' : ''}{fmt(allProfit)}
                      </span>
                    </div>
                  </div>
                );
              })()}
            </>
          )}
        </div>
      )}

      {tab !== 'positions' && (
        <Card>
          <CardContent className="p-0">
            {loading ? (
              <div className="p-4 space-y-4">
                {Array.from({ length: 3 }).map((_, i) => (
                  <div key={i} className="flex justify-between items-center">
                    <div className="space-y-2"><Skeleton className="h-4 w-24" /><Skeleton className="h-3 w-16" /></div>
                    <Skeleton className="h-8 w-20" />
                  </div>
                ))}
              </div>
            ) : (
              <>
                {tab === 'orders' && (
                  orders.length === 0 ? (
                    <EmptyState icon={<ClipboardList />} text="暂无订单" />
                  ) : (
                    orders.map((o) => (
                      <div key={o.orderId} className="p-4 border-b border-border last:border-b-0">
                        <div className="flex justify-between items-start mb-2">
                          <div className="flex items-center gap-2">
                            <span className="font-medium">{o.stockName}</span>
                            <Badge variant={o.orderSide === 'BUY' ? 'destructive' : 'success'} className="text-xs">
                              {o.orderSide === 'BUY' ? '买入' : '卖出'}
                            </Badge>
                            <Badge variant={o.status === 'PENDING' || o.status === 'SETTLING' ? 'warning' : o.status === 'FILLED' ? 'success' : 'secondary'}>
                              {o.status}
                            </Badge>
                          </div>
                          {o.status === 'PENDING' && (
                            <Button
                              variant="ghost"
                              size="sm"
                              className="h-7 text-destructive hover:text-destructive hover:bg-destructive/10"
                              onClick={() => setCancelOrder(o)}
                              disabled={user.bankrupt}
                            >
                              <X className="w-3 h-3" />
                              取消
                            </Button>
                          )}
                        </div>
                        <div className="text-sm text-muted-foreground space-y-1">
                          <div>数量: {o.quantity}股 · {o.orderType === 'MARKET' ? '市价单' : '限价单'}</div>
                          {o.orderType === 'MARKET' ? (
                            o.filledPrice && (
                              <div>
                                成交: {o.filledPrice.toFixed(2)} · 总价: {o.filledAmount?.toFixed(2)}
                                {o.commission && ` · 手续费: ${o.commission.toFixed(2)}`}
                              </div>
                            )
                          ) : (
                            <>
                              <div>限价: {o.limitPrice?.toFixed(2)}{o.triggerPrice ? ` · 触发价: ${o.triggerPrice.toFixed(2)}` : ''}</div>
                              {o.filledPrice && (
                                <div>
                                  成交: {o.filledPrice.toFixed(2)} · 总价: {o.filledAmount?.toFixed(2)}
                                  {o.commission && ` · 手续费: ${o.commission.toFixed(2)}`}
                                </div>
                              )}
                            </>
                          )}
                        </div>
                      </div>
                    ))
                  )
                )}
                 {tab === 'orders' && orders.length > 0 && (
                     <div className="p-4 border-t flex items-center justify-between">
                       <div className="text-sm text-muted-foreground">
                         共 {orderTotal} 条订单
                       </div>
                       <div className="flex items-center gap-2">
                         <select
                             className="h-8 w-16 rounded-md border border-input bg-background px-2 py-1 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                             value={orderPageSize}
                             onChange={(e) => {
                                 setOrderPageSize(Number(e.target.value));
                                 setOrderPage(1);
                             }}
                         >
                             <option value="10">10</option>
                             <option value="20">20</option>
                             <option value="50">50</option>
                         </select>
                         <div className="flex items-center gap-1">
                             <Button
                                 variant="outline"
                                 size="sm"
                                 className="h-8 w-8 p-0"
                                 onClick={() => setOrderPage(p => Math.max(1, p - 1))}
                                 disabled={orderPage === 1}
                             >
                                 <ChevronRight className="h-4 w-4 rotate-180" />
                             </Button>
                             <div className="text-sm font-medium w-8 text-center">
                                 {orderPage}
                             </div>
                             <Button
                                 variant="outline"
                                 size="sm"
                                 className="h-8 w-8 p-0"
                                 onClick={() => setOrderPage(p => p + 1)}
                                 disabled={orderPage * orderPageSize >= orderTotal}
                             >
                                 <ChevronRight className="h-4 w-4" />
                             </Button>
                         </div>
                       </div>
                     </div>
                 )}

                {tab === 'settlements' && (
                  settlements.length === 0 ? (
                    <EmptyState icon={<Clock />} text="暂无待结算" />
                  ) : (
                    settlements.map((s) => {
                      const relatedOrder = orders.find(o => o.orderId === s.orderId);
                      return (
                        <div key={s.id} className="p-4 border-b border-border last:border-b-0">
                          <div className="flex justify-between items-start mb-2">
                            <div className="flex items-center gap-2">
                              <span className="font-medium">{relatedOrder?.stockName || `订单 #${s.orderId}`}</span>
                              {relatedOrder && (
                                <Badge variant="secondary" className="text-xs">
                                  {relatedOrder.stockCode}
                                </Badge>
                              )}
                            </div>
                            <Badge variant="secondary">{s.status}</Badge>
                          </div>
                          <div className="flex justify-between text-sm text-muted-foreground">
                            <span>
                              {relatedOrder ? `${relatedOrder.quantity}股 @ ${relatedOrder.filledPrice?.toFixed(2) || '-'}` : '-'}
                            </span>
                            <span className="font-medium tabular-nums text-foreground">
                              +{s.amount.toFixed(2)}
                            </span>
                          </div>
                          {s.settleTime && (
                            <div className="text-xs text-muted-foreground mt-1">
                              预计到账: {s.settleTime.replace('T', ' ').substring(0, 16)}
                            </div>
                          )}
                        </div>
                      );
                    })
                  )
                )}
              </>
            )}
          </CardContent>
        </Card>
      )}

      <Dialog
        open={!!cancelOrder}
        onClose={() => {
          if (cancelSubmitting) return;
          setCancelOrder(null);
        }}
      >
        <DialogHeader>
          <h3 className="text-lg font-semibold leading-tight pr-6">取消订单</h3>
        </DialogHeader>
        <DialogContent>
          <p className="text-sm text-muted-foreground">
            {cancelOrder ? `确定取消 ${cancelOrder.stockName} 的订单（#${cancelOrder.orderId}）？` : ''}
          </p>
        </DialogContent>
        <DialogFooter className="justify-end gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => setCancelOrder(null)}
            disabled={cancelSubmitting}
          >
            先不取消
          </Button>
          <Button
            size="sm"
            variant="destructive"
            onClick={async () => {
              if (!cancelOrder) return;
              setCancelSubmitting(true);
              try {
                const ok = await handleCancelOrder(cancelOrder.orderId);
                if (ok) setCancelOrder(null);
              } finally {
                setCancelSubmitting(false);
              }
            }}
            disabled={cancelSubmitting}
          >
            确认取消
          </Button>
        </DialogFooter>
      </Dialog>
    </div>
  );
}

function TabButton({ active, onClick, icon, children }: { active: boolean; onClick: () => void; icon: React.ReactNode; children: React.ReactNode }) {
  return (
    <button
      onClick={onClick}
      className={cn(
        "flex-1 flex items-center justify-center gap-2 py-2 px-3 rounded-md text-sm font-medium transition-all whitespace-nowrap border border-transparent",
        active
          ? "bg-primary text-primary-foreground shadow-md border-primary/20"
          : "text-muted-foreground hover:text-foreground hover:bg-accent/80 border-transparent"
      )}
    >
      {icon}
      {children}
    </button>
  );
}

function EmptyState({ icon, text }: { icon: React.ReactNode; text: string }) {
  return (
    <div className="p-12 text-center text-muted-foreground">
      <div className="w-12 h-12 mx-auto mb-3 rounded-full bg-muted flex items-center justify-center">
        {icon}
      </div>
      {text}
    </div>
  );
}
