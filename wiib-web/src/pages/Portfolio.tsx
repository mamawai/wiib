import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useUserStore } from '../stores/userStore';
import { userApi, orderApi, settlementApi } from '../api';
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
  PieChart
} from 'lucide-react';
import type { Position, Order, Settlement, AssetChangeEvent, PositionChangeEvent, OrderStatusEvent } from '../types';
import { useDedupedEffect } from '../hooks/useDedupedEffect';

export function Portfolio() {
  const navigate = useNavigate();
  const { user } = useUserStore();
  const { toast } = useToast();
  const [positions, setPositions] = useState<Position[]>([]);
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
  const orderPageRef = useRef(orderPage);
  const orderPageSizeRef = useRef(orderPageSize);

  useEffect(() => {
    orderPageRef.current = orderPage;
  }, [orderPage]);

  useEffect(() => {
    orderPageSizeRef.current = orderPageSize;
  }, [orderPageSize]);

  // 监听WebSocket事件
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
          // 清仓，移除持仓
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
        // 新订单 (插入头部) - 注意：这里只是为了即时反馈，如果分页不在第一页可能看不到
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
            // 简单处理：插入第一条，如果超过pageSize则移除最后一条
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

  const requestKey = user ? `portfolio:user=${user.id}:refresh=${refreshNonce}:page=${orderPage}:size=${orderPageSize}` : null;
  useDedupedEffect(
    requestKey,
    () => {
      if (!user) return;
      let cancelled = false;
      activeRequestKey.current = requestKey ?? '';

      setLoading(true);
      Promise.all([
          userApi.portfolio(),
          userApi.positions(),
          orderApi.list(undefined, orderPage, orderPageSize),
          settlementApi.pending()
      ])
        .then(([u, p, o, s]) => {
          if (cancelled) return;
          if (activeRequestKey.current !== (requestKey ?? '')) return;
          // 更新 user 数据（包括 profit/profitPct）
          useUserStore.setState({ user: u });
          setPositions(p);
          setOrders(o.records);
          setOrderTotal(o.total);
          setSettlements(s);
        })
        .catch(() => {
          if (cancelled) return;
          if (activeRequestKey.current !== (requestKey ?? '')) return;
          setPositions([]);
          setOrders([]);
          setOrderTotal(0);
          setSettlements([]);
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
      {/* Assets Summary Card */}
      <Card className="bg-gradient-to-br from-primary/20 via-primary/10 to-transparent border-primary/20 overflow-hidden relative">
        <div className="absolute top-0 right-0 w-32 h-32 bg-primary/10 rounded-full blur-2xl -translate-y-1/2 translate-x-1/2" />
        <CardContent className="p-6 relative">
          <div className="flex justify-between items-start mb-6">
            <div className="flex items-center gap-3">
              {user.avatar ? (
                <img src={user.avatar} alt="" className="w-12 h-12 rounded-full ring-2 ring-primary/20" />
              ) : (
                <div className="w-12 h-12 rounded-full bg-primary/20 flex items-center justify-center">
                  <Wallet className="w-6 h-6 text-primary" />
                </div>
              )}
              <div>
                <h2 className="text-lg font-semibold">{user.username}</h2>
                <p className="text-sm text-muted-foreground">模拟账户</p>
              </div>
            </div>
             <Button
               variant="ghost"
               size="sm"
               onClick={() => setShowChart(!showChart)}
               className={cn("gap-1", showChart && "bg-primary/10 text-primary")}
             >
               <PieChart className="w-4 h-4" />
               {showChart ? '隐藏分布' : '资产分布'}
             </Button>
          </div>

          <div className="grid grid-cols-3 gap-4 mb-4">
            <div className="text-center p-3 rounded-xl bg-background/50 backdrop-blur-sm">
              <span className="text-xs text-muted-foreground block mb-1">总资产</span>
              <span className="text-lg font-bold tabular-nums">{user.totalAssets.toFixed(2)}</span>
            </div>
            <div className="text-center p-3 rounded-xl bg-background/50 backdrop-blur-sm">
              <span className="text-xs text-muted-foreground block mb-1">可用余额</span>
              <span className="text-lg font-bold tabular-nums">{user.balance.toFixed(2)}</span>
            </div>
            <div className="text-center p-3 rounded-xl bg-background/50 backdrop-blur-sm">
              <span className="text-xs text-muted-foreground block mb-1">总盈亏</span>
              <div className="flex items-center justify-center gap-1">
                {isProfit ? (
                  <TrendingUp className="w-4 h-4 text-red-500" />
                ) : (
                  <TrendingDown className="w-4 h-4 text-green-500" />
                )}
                <span className={cn("text-lg font-bold tabular-nums", isProfit ? "text-red-500" : "text-green-500")}>
                  {isProfit ? '+' : ''}{user.profitPct.toFixed(2)}%
                </span>
              </div>
            </div>
          </div>

          <div className="grid grid-cols-2 gap-4 mb-4">
            <div className="text-center p-3 rounded-xl bg-background/50 backdrop-blur-sm">
              <span className="text-xs text-muted-foreground block mb-1">杠杆借款</span>
              <span className="text-base font-bold tabular-nums">{user.marginLoanPrincipal.toFixed(2)}</span>
            </div>
            <div className="text-center p-3 rounded-xl bg-background/50 backdrop-blur-sm">
              <span className="text-xs text-muted-foreground block mb-1">应计利息</span>
              <span className="text-base font-bold tabular-nums">{user.marginInterestAccrued.toFixed(2)}</span>
            </div>
          </div>
          
           {showChart && (
            <div className="animate-in fade-in slide-in-from-top-4 duration-300">
               <PortfolioChart 
                 positions={positions} 
                 balance={user.balance} 
                 pendingSettlement={user.pendingSettlement}
               />
            </div>
          )}
        </CardContent>
      </Card>

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
      <Card>
        <CardContent className="p-0">
          {loading ? (
            <div className="p-4 space-y-4">
              {Array.from({ length: 3 }).map((_, i) => (
                <div key={i} className="flex justify-between items-center">
                  <div className="space-y-2">
                    <Skeleton className="h-4 w-24" />
                    <Skeleton className="h-3 w-16" />
                  </div>
                  <Skeleton className="h-8 w-20" />
                </div>
              ))}
            </div>
          ) : (
            <>
              {tab === 'positions' && (
                positions.length === 0 ? (
                  <EmptyState icon={<Briefcase />} text="暂无持仓" />
                ) : (
                  positions.map((p) => (
                    <button
                      type="button"
                      key={p.id}
                      onClick={() => navigate(`/stock/${p.stockId}`)}
                      className="w-full text-left p-4 border-b border-border last:border-b-0 cursor-pointer hover:bg-accent/50 transition-colors group focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                    >
                      <div className="flex justify-between items-start mb-2">
                        <div>
                          <span className="font-medium group-hover:text-primary transition-colors">{p.stockName}</span>
                          <span className="text-xs text-muted-foreground ml-2">{p.stockCode}</span>
                        </div>
                        <ChevronRight className="w-4 h-4 text-muted-foreground group-hover:text-primary transition-colors" />
                      </div>
                      <div className="flex justify-between text-sm text-muted-foreground">
                        <span>持仓 {p.quantity} 股 · 成本 {p.avgCost.toFixed(2)}</span>
                        <span className={cn("font-medium tabular-nums", p.profit >= 0 ? "text-red-500" : "text-green-500")}>
                          {p.profit >= 0 ? '+' : ''}{p.profit.toFixed(2)} ({p.profitPct.toFixed(2)}%)
                        </span>
                      </div>
                    </button>
                  ))
                )
              )}

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
                               setOrderPage(1); // Reset to first page
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
                    // 通过orderId查找对应的订单信息
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
