import { useState, useEffect, useRef, useCallback } from 'react';
import { useParams } from 'react-router-dom';
import * as echarts from 'echarts';
import { cryptoApi, cryptoOrderApi, buffApi, futuresApi } from '../api';
import { useUserStore } from '../stores/userStore';
import { useCryptoStream } from '../hooks/useCryptoStream';
import { useToast } from '../components/ui/use-toast';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Input } from '../components/ui/input';
import { Badge } from '../components/ui/badge';
import { Skeleton } from '../components/ui/skeleton';
import { Bitcoin, Coins, TrendingUp, TrendingDown, ChevronLeft, ChevronRight, Loader2, X, RefreshCw, Sparkles, Wallet, Warehouse, Scale } from 'lucide-react';
import TradingViewWidget from '../components/TradingViewWidget';
import type { CryptoOrder, CryptoPosition, PageResult, UserBuff, FuturesPosition, FuturesOrder } from '../types';

interface SymbolCfg {
  name: string;
  pair: string;
  tvSymbol: string;
  minQty: number;
  icon: typeof Bitcoin;
  colorClass: string;
  bgClass: string;
  gradientClass: string;
}

const SYMBOL_CFG: Record<string, SymbolCfg> = {
  BTCUSDT: { name: 'BTC', pair: 'BTC / USDT', tvSymbol: 'BINANCE:BTCUSD', minQty: 0.00001, icon: Bitcoin, colorClass: 'text-orange-500', bgClass: 'bg-orange-500/10', gradientClass: 'from-orange-500/5' },
  PAXGUSDT: { name: 'PAXG', pair: 'PAXG / USDT', tvSymbol: 'BINANCE:PAXGUSD', minQty: 0.001, icon: Coins, colorClass: 'text-yellow-500', bgClass: 'bg-yellow-500/10', gradientClass: 'from-yellow-500/5' },
};

const COMMISSION_RATE = 0.001;
const POSITION_PCTS = [0.25, 0.5, 0.75, 1];
const MMR = 0.005;

interface TabConfig {
  label: string;
  interval: string;
  limit: number;
}

const TABS: TabConfig[] = [
  { label: '1D', interval: '1m', limit: 720 },
  { label: '7D', interval: '15m', limit: 672 },
];

interface ChartPoint {
  time: number;
  open: number;
  high: number;
  low: number;
  close: number;
}

function parseKlines(raw: number[][]): ChartPoint[] {
  return raw.map(k => ({
    time: k[0] as number,
    open: parseFloat(String(k[1])),
    high: parseFloat(String(k[2])),
    low: parseFloat(String(k[3])),
    close: parseFloat(String(k[4])),
  }));
}

function formatTime(ts: number, interval: string): string {
  const d = new Date(ts);
  if (interval === '15m') {
    return `${(d.getMonth() + 1).toString().padStart(2, '0')}-${d.getDate().toString().padStart(2, '0')} ${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`;
  }
  return `${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`;
}

function formatPrice(n: number): string {
  return n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function roundHalfUp2(n: number): number {
  return Math.round((n + Number.EPSILON) * 100) / 100;
}

function roundCeil2(n: number): number {
  return Math.ceil((n - 1e-9) * 100) / 100;
}

function getStepPrecision(step: number): number {
  const s = String(step);
  const i = s.indexOf('.');
  return i >= 0 ? s.length - i - 1 : 0;
}

function formatStepValue(value: number, step: number): string {
  return value.toFixed(getStepPrecision(step)).replace(/0+$/, '').replace(/\.$/, '');
}

function calcFuturesOpenEstimate(marginQty: number, price: number, leverage: number) {
  const orderQty = marginQty * leverage;
  const positionValue = roundHalfUp2(price * orderQty);
  const margin = roundCeil2(positionValue / leverage);
  const commission = roundHalfUp2(positionValue * COMMISSION_RATE);
  const fundingFee = roundHalfUp2(positionValue * 0.0001);
  const totalCost = roundHalfUp2(margin + commission);
  return { orderQty, positionValue, margin, commission, fundingFee, totalCost };
}

function calcMaxAffordableMarginQty(balance: number, pct: number, price: number, leverage: number, step: number): number {
  const budget = balance * pct;
  if (budget <= 0 || price <= 0 || leverage <= 0 || step <= 0) return 0;

  const precision = getStepPrecision(step);
  let left = 0;
  let right = Math.floor((budget / price) / step);
  let best = 0;

  while (left <= right) {
    const mid = Math.floor((left + right) / 2);
    const qty = Number((mid * step).toFixed(precision));
    const estimate = calcFuturesOpenEstimate(qty, price, leverage);
    if (estimate.totalCost <= budget + 1e-9) {
      best = qty;
      left = mid + 1;
    } else {
      right = mid - 1;
    }
  }

  return best;
}

function formatDateTime(s: string): string {
  const d = new Date(s);
  return `${(d.getMonth() + 1).toString().padStart(2, '0')}-${d.getDate().toString().padStart(2, '0')} ${d.getHours().toString().padStart(2, '0')}:${d.getMinutes().toString().padStart(2, '0')}`;
}

const LEVERAGE_OPTIONS = Array.from({ length: 10 }, (_, i) => i + 1);
const ORDER_STATUS_FILTERS = [
  { label: '全部', value: '' },
  { label: '待成交', value: 'PENDING' },
  { label: '结算中', value: 'SETTLING' },
  { label: '已成交', value: 'FILLED' },
  { label: '已取消', value: 'CANCELLED' },
];

const FUTURES_ORDER_FILTERS = [
  { label: '全部', value: '' },
  { label: '待成交', value: 'PENDING' },
  { label: '已成交', value: 'FILLED' },
  { label: '已取消', value: 'CANCELLED' },
];

const FUTURES_SIDE_MAP: Record<string, { label: string; color: string }> = {
  OPEN_LONG: { label: '开多', color: 'text-red-500' },
  OPEN_SHORT: { label: '开空', color: 'text-green-500' },
  CLOSE_LONG: { label: '平多', color: 'text-green-500' },
  CLOSE_SHORT: { label: '平空', color: 'text-red-500' },
};

const STATUS_MAP: Record<string, { label: string; variant: 'default' | 'secondary' | 'destructive' | 'outline' | 'success' | 'warning' }> = {
  PENDING: { label: '待成交', variant: 'warning' },
  TRIGGERED: { label: '已触发', variant: 'default' },
  SETTLING: { label: '结算中', variant: 'warning' },
  FILLED: { label: '已成交', variant: 'success' },
  CANCELLED: { label: '已取消', variant: 'secondary' },
  EXPIRED: { label: '已过期', variant: 'secondary' },
};

export function CoinRoute() {
  const { symbol } = useParams<{ symbol: string }>();
  const s = symbol && SYMBOL_CFG[symbol] ? symbol : 'BTCUSDT';
  return <Coin key={s} symbol={s} />;
}

export function Coin({ symbol = 'BTCUSDT' }: { symbol?: string }) {
  const cfg = SYMBOL_CFG[symbol] ?? SYMBOL_CFG['BTCUSDT'];
  const Icon = cfg.icon;
  const MIN_QTY = cfg.minQty;
  const { toast } = useToast();
  const user = useUserStore(s => s.user);
  const fetchUser = useUserStore(s => s.fetchUser);
  const [activeTab, setActiveTab] = useState(0);
  const [points1D, setPoints1D] = useState<ChartPoint[]>([]);
  const [points7D, setPoints7D] = useState<ChartPoint[]>([]);
  const [loading, setLoading] = useState(true);
  const chartRef1D = useRef<HTMLDivElement>(null);
  const chartRef7D = useRef<HTMLDivElement>(null);
  const chartInst1D = useRef<echarts.ECharts | null>(null);
  const chartInst7D = useRef<echarts.ECharts | null>(null);

  const tick = useCryptoStream(symbol);

  // PAXG: USD/CNY 汇率 → 人民币/克
  const [usdCny, setUsdCny] = useState(0);
  useEffect(() => {
    if (symbol !== 'PAXGUSDT') return;
    fetch('https://open.er-api.com/v6/latest/USD')
      .then(r => r.json())
      .then(d => { if (d.result === 'success') setUsdCny(d.rates.CNY); })
      .catch(() => {});
  }, [symbol]);

  // 交易面板状态
  const [side, setSide] = useState<'BUY' | 'SELL'>('BUY');
  const [orderType, setOrderType] = useState<'MARKET' | 'LIMIT' | 'FUTURES'>('MARKET');
  const [qtyMap, setQtyMap] = useState<Record<string, string>>({});
  const qtyKey = `${side}_${orderType}`;
  const quantity = qtyMap[qtyKey] ?? '';
  const setQuantity = (v: string) => setQtyMap(m => ({ ...m, [qtyKey]: v }));
  const [limitPrice, setLimitPrice] = useState('');
  const [leverage, setLeverage] = useState(1);
  const [submitting, setSubmitting] = useState(false);

  // 合约专用状态
  const [futuresSide, setFuturesSide] = useState<'LONG' | 'SHORT'>('LONG');
  const [futuresLeverage, setFuturesLeverage] = useState(10);
  const [futuresOrderType, setFuturesOrderType] = useState<'MARKET' | 'LIMIT'>('MARKET');
  const [futuresPositions, setFuturesPositions] = useState<FuturesPosition[]>([]);
  const [futuresOrders, setFuturesOrders] = useState<FuturesOrder[]>([]);
  const [futuresOrderPage, setFuturesOrderPage] = useState(1);
  const [futuresOrderTotal, setFuturesOrderTotal] = useState(0);
  const [futuresOrderPages, setFuturesOrderPages] = useState(0);
  const [futuresOrderFilter, setFuturesOrderFilter] = useState('');
  const [futuresOrdersLoading, setFuturesOrdersLoading] = useState(false);
  const [futuresStopLoss, setFuturesStopLoss] = useState(false);
  const [futuresStopLossPct, setFuturesStopLossPct] = useState('');
  const [posAction, setPosAction] = useState<{ id: number; type: 'close' | 'margin' | 'stoploss' } | null>(null);
  const [posActionInput, setPosActionInput] = useState('');

  // 订单列表状态
  const [orderFilter, setOrderFilter] = useState('');
  const [orders, setOrders] = useState<CryptoOrder[]>([]);
  const [orderPage, setOrderPage] = useState(1);
  const [orderTotal, setOrderTotal] = useState(0);
  const [orderPages, setOrderPages] = useState(0);
  const [ordersLoading, setOrdersLoading] = useState(false);

  // 折扣券状态
  const [discountBuff, setDiscountBuff] = useState<UserBuff | null>(null);
  const [useBuff, setUseBuff] = useState(false);

  // 持仓
  const [position, setPosition] = useState<CryptoPosition | null>(null);
  const fetchPosition = useCallback(() => {
    cryptoOrderApi.position(symbol).then(setPosition).catch(() => setPosition(null));
  }, []);

  // 拉取K线
  const fetchKlines = useCallback(async (tabIdx: number) => {
    setLoading(true);
    try {
      const tab = TABS[tabIdx];
      const data = parseKlines(await cryptoApi.klines(symbol, tab.interval, tab.limit));
      if (tabIdx === 0) setPoints1D(data); else setPoints7D(data);
    } catch (e) {
      console.error('拉取K线失败', e);
      if (tabIdx === 0) setPoints1D([]); else setPoints7D([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { if (activeTab < TABS.length) fetchKlines(activeTab); }, [activeTab, fetchKlines]);

  // 切tab后 resize 当前图表（display:none → block 后尺寸需刷新）
  useEffect(() => {
    if (activeTab >= TABS.length) return;
    const inst = activeTab === 0 ? chartInst1D.current : chartInst7D.current;
    inst?.resize();
  }, [activeTab]);

  // 实时价格追加到1D图表末端
  useEffect(() => {
    if (!tick || points1D.length === 0) return;
    setPoints1D(prev => {
      const last = prev[prev.length - 1];
      if (!last) return prev;
      const minuteStart = Math.floor(tick.ts / 60000) * 60000;
      if (last.time === minuteStart) {
        return [...prev.slice(0, -1), { ...last, close: tick.price, high: Math.max(last.high, tick.price), low: Math.min(last.low, tick.price) }];
      }
      if (minuteStart > last.time) {
        return [...prev, { time: minuteStart, open: tick.price, high: tick.price, low: tick.price, close: tick.price }];
      }
      return prev;
    });
  }, [tick]);

  // 图表渲染函数
  const chart1DReady = useRef(false);
  const chart7DReady = useRef(false);

  const renderChart = useCallback((
    container: HTMLDivElement | null,
    instRef: React.MutableRefObject<echarts.ECharts | null>,
    readyRef: React.MutableRefObject<boolean>,
    data: ChartPoint[],
    tab: TabConfig,
    withEffect: boolean,
  ) => {
    if (!container) return;
    if (!instRef.current) instRef.current = echarts.init(container, 'dark');
    const chart = instRef.current;
    const seriesData = data.map(p => [p.time, p.close]);
    const closes = data.map(p => p.close);
    if (closes.length === 0) { chart.clear(); readyRef.current = false; return; }
    const first = closes[0];
    const last = closes[closes.length - 1];
    const isUp = last >= first;
    const lineColor = isUp ? '#ef4444' : '#22c55e';
    const areaStart = isUp ? 'rgba(239,68,68,0.25)' : 'rgba(34,197,94,0.25)';
    const areaEnd = isUp ? 'rgba(239,68,68,0.02)' : 'rgba(34,197,94,0.02)';

    if (!readyRef.current) {
      const now = Date.now();
      const isDay = tab.interval === '1m';
      chart.setOption({
        backgroundColor: 'transparent',
        grid: { left: 8, right: 8, top: 16, bottom: 32, containLabel: true },
        xAxis: { type: 'time', min: isDay ? now - 12 * 3600_000 : undefined, max: isDay ? now + 12 * 3600_000 : undefined, axisLine: { lineStyle: { color: '#333' } }, axisTick: { show: false }, axisLabel: { fontSize: 10, color: '#888', hideOverlap: true, formatter: (val: number) => formatTime(val, tab.interval) }, splitLine: { show: false } },
        yAxis: { type: 'value', scale: true, axisLine: { show: false }, axisTick: { show: false }, axisLabel: { fontSize: 10, color: '#888', formatter: (v: number) => formatPrice(v) }, splitLine: { lineStyle: { color: '#222', type: 'dashed' } } },
        series: [
          { type: 'line', data: seriesData, smooth: 0.2, showSymbol: false, lineStyle: { width: 1.5, color: lineColor }, areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: areaStart }, { offset: 1, color: areaEnd }]) } },
          ...(withEffect && seriesData.length > 0 ? [{ type: 'effectScatter', data: [seriesData[seriesData.length - 1]], symbolSize: 8, rippleEffect: { brushType: 'fill', scale: 5, period: 4, number: 2 }, itemStyle: { color: lineColor, shadowBlur: 12, shadowColor: lineColor }, z: 10 }] : []),
        ],
        tooltip: { trigger: 'axis', backgroundColor: 'rgba(20,20,20,0.9)', borderColor: '#333', textStyle: { color: '#fff', fontSize: 12 }, formatter: (params: unknown) => { const arr = params as { value: [number, number] }[]; const p = arr[0]; if (!p || p.value[1] == null) return ''; return `<div style="padding:4px 0"><div style="color:#888;margin-bottom:4px">${formatTime(p.value[0], tab.interval)}</div><div style="font-size:16px;font-weight:bold">$${formatPrice(p.value[1])}</div></div>`; } },
        dataZoom: [{ type: 'inside', start: 0, end: 100, minValueSpan: 20 }],
      });
      readyRef.current = true;
    } else {
      chart.setOption({
        series: [
          { data: seriesData, lineStyle: { color: lineColor }, areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: areaStart }, { offset: 1, color: areaEnd }]) } },
          ...(withEffect && seriesData.length > 0 ? [{ data: [seriesData[seriesData.length - 1]], itemStyle: { color: lineColor, shadowBlur: 12, shadowColor: lineColor } }] : []),
        ],
      });
    }
  }, []);

  // 1D 渲染
  useEffect(() => {
    renderChart(chartRef1D.current, chartInst1D, chart1DReady, points1D, TABS[0], true);
  }, [points1D, renderChart]);

  // 7D 渲染
  useEffect(() => {
    renderChart(chartRef7D.current, chartInst7D, chart7DReady, points7D, TABS[1], false);
  }, [points7D, renderChart]);

  // 窗口resize
  useEffect(() => {
    const onResize = () => {
      chartInst1D.current?.resize();
      chartInst7D.current?.resize();
    };
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      chartInst1D.current?.dispose(); chartInst1D.current = null; chart1DReady.current = false;
      chartInst7D.current?.dispose(); chartInst7D.current = null; chart7DReady.current = false;
    };
  }, []);

  // 订单列表
  const fetchOrders = useCallback(async (status: string, page: number) => {
    setOrdersLoading(true);
    try {
      const res = await cryptoOrderApi.list(status || undefined, page, 10, symbol) as unknown as PageResult<CryptoOrder>;
      setOrders(res.records);
      setOrderTotal(res.total);
      setOrderPages(res.pages);
    } catch { setOrders([]); }
    finally { setOrdersLoading(false); }
  }, [symbol]);

  useEffect(() => { fetchOrders(orderFilter, orderPage); }, [orderFilter, orderPage, fetchOrders]);

  // 加载折扣券 + 持仓 + 合约仓位
  useEffect(() => {
    buffApi.status().then(s => {
      const b = s.todayBuff;
      if (b && b.buffType.startsWith('DISCOUNT_') && !b.isUsed) setDiscountBuff(b);
      else setDiscountBuff(null);
    }).catch(() => {});
    fetchPosition();
    if (orderType === 'FUTURES') fetchFuturesPositions();
  }, [fetchPosition, orderType]);

  // 合约仓位查询
  const fetchFuturesPositions = useCallback(async () => {
    try {
      const positions = await futuresApi.positions(symbol);
      setFuturesPositions(positions);
    } catch (e) {
      console.error('查询合约仓位失败', e);
      setFuturesPositions([]);
    }
  }, [symbol]);

  // 用 WS 推送的 markPrice 实时更新合约仓位盈亏
  useEffect(() => {
    if (!tick?.mp || futuresPositions.length === 0) return;
    const mp = tick.mp;
    setFuturesPositions(prev => prev.map(pos => {
      const posValue = mp * pos.quantity;
      const unrealizedPnl = pos.side === 'LONG'
        ? (mp - pos.entryPrice) * pos.quantity
        : (pos.entryPrice - mp) * pos.quantity;
      const effectiveMargin = pos.margin + unrealizedPnl;
      const unrealizedPnlPct = pos.margin > 0 ? (unrealizedPnl / pos.margin) * 100 : 0;
      return { ...pos, markPrice: mp, currentPrice: mp, positionValue: posValue, unrealizedPnl, unrealizedPnlPct, effectiveMargin };
    }));
  }, [tick?.mp]);

  // 合约订单查询
  const fetchFuturesOrders = useCallback(async (status: string, page: number) => {
    setFuturesOrdersLoading(true);
    try {
      const res = await futuresApi.orders(status || undefined, page, 10, symbol) as unknown as PageResult<FuturesOrder>;
      setFuturesOrders(res.records);
      setFuturesOrderTotal(res.total);
      setFuturesOrderPages(res.pages);
    } catch (e) {
      console.error('查询合约订单失败', e);
      setFuturesOrders([]);
    } finally {
      setFuturesOrdersLoading(false);
    }
  }, [symbol]);

  // 合约订单：filter/page/tab 变化时拉取
  useEffect(() => {
    if (orderType === 'FUTURES') fetchFuturesOrders(futuresOrderFilter, futuresOrderPage);
  }, [futuresOrderFilter, futuresOrderPage, orderType, fetchFuturesOrders]);

  // 下单
  const handleSubmit = async () => {
    const qty = parseFloat(quantity);
    if (!qty || qty < MIN_QTY) { toast(`最小数量 ${MIN_QTY}`, 'error'); return; }

    // 合约下单
    if (orderType === 'FUTURES') {
      if (futuresOrderType === 'LIMIT') {
        const lp = parseFloat(limitPrice);
        if (!lp || lp <= 0) { toast('请输入有效限价', 'error'); return; }
      }
      setSubmitting(true);
      try {
        await futuresApi.open({
          symbol,
          side: futuresSide,
          quantity: qty * futuresLeverage,
          leverage: futuresLeverage,
          orderType: futuresOrderType,
          ...(futuresOrderType === 'LIMIT' ? { limitPrice: parseFloat(limitPrice) } : {}),
          ...(futuresStopLoss && futuresStopLossPct ? { stopLossPercent: parseFloat(futuresStopLossPct) } : {}),
        });
        toast(`${futuresSide === 'LONG' ? '做多' : '做空'}开仓成功`, 'success');
        setQuantity(String(MIN_QTY));
        setLimitPrice('');
        setFuturesStopLoss(false);
        setFuturesStopLossPct('');
        fetchFuturesPositions();
        fetchFuturesOrders('', 1);
        fetchUser();
      } catch (e: unknown) {
        toast((e as Error).message || '开仓失败', 'error');
      } finally { setSubmitting(false); }
      return;
    }

    // 现货下单
    if (orderType === 'LIMIT') {
      const lp = parseFloat(limitPrice);
      if (!lp || lp <= 0) { toast('请输入有效限价', 'error'); return; }
    }
    setSubmitting(true);
    try {
      const actualQty = side === 'BUY' && orderType === 'MARKET' && leverage > 1 ? qty * leverage : qty;
      const req = {
        symbol: symbol,
        quantity: actualQty,
        orderType,
        ...(orderType === 'LIMIT' ? { limitPrice: parseFloat(limitPrice) } : {}),
        ...(side === 'BUY' && orderType === 'MARKET' && leverage > 1 ? { leverageMultiple: leverage } : {}),
        ...(side === 'BUY' && orderType === 'MARKET' && useBuff && discountBuff ? { useBuffId: discountBuff.id } : {}),
      };
      if (side === 'BUY') {
        await cryptoOrderApi.buy(req);
        toast('买入成功', 'success');
      } else {
        await cryptoOrderApi.sell(req);
        toast('卖出成功', 'success');
      }
      if (useBuff && discountBuff) { setDiscountBuff(null); setUseBuff(false); }
      setQuantity(String(MIN_QTY));
      setLimitPrice('');
      setLeverage(1);
      fetchOrders(orderFilter, 1);
      setOrderPage(1);
      fetchPosition();
      fetchUser();
    } catch (e: unknown) {
      toast((e as Error).message || '下单失败', 'error');
    } finally { setSubmitting(false); }
  };

  // 取消订单
  const handleCancel = async (orderId: number) => {
    try {
      await cryptoOrderApi.cancel(orderId);
      toast('已取消', 'success');
      fetchOrders(orderFilter, orderPage);
    } catch (e: unknown) {
      toast((e as Error).message || '取消失败', 'error');
    }
  };

  // 合约平仓
  const handleFuturesClose = async (positionId: number) => {
    const qty = parseFloat(posActionInput);
    if (!qty || qty <= 0) { toast('请输入平仓数量', 'error'); return; }
    setSubmitting(true);
    try {
      await futuresApi.close({ positionId, quantity: qty, orderType: 'MARKET' });
      toast('平仓成功', 'success');
      setPosAction(null);
      fetchFuturesPositions();
      fetchFuturesOrders('', futuresOrderPage);
      fetchUser();
    } catch (e: unknown) {
      toast((e as Error).message || '平仓失败', 'error');
    } finally { setSubmitting(false); }
  };

  // 合约追加保证金
  const handleAddMargin = async (positionId: number) => {
    const amt = parseFloat(posActionInput);
    if (!amt || amt <= 0) { toast('请输入有效金额', 'error'); return; }
    setSubmitting(true);
    try {
      await futuresApi.addMargin({ positionId, amount: amt });
      toast('追加保证金成功', 'success');
      setPosAction(null);
      fetchFuturesPositions();
      fetchUser();
    } catch (e: unknown) {
      toast((e as Error).message || '追加保证金失败', 'error');
    } finally { setSubmitting(false); }
  };

  // 合约设置止损
  const handleSetStopLoss = async (positionId: number) => {
    const pct = parseFloat(posActionInput);
    if (!pct || pct <= 0) { toast('请输入止损比例', 'error'); return; }
    setSubmitting(true);
    try {
      await futuresApi.setStopLoss({ positionId, stopLossPercent: pct });
      toast('设置止损成功', 'success');
      setPosAction(null);
      fetchFuturesPositions();
    } catch (e: unknown) {
      toast((e as Error).message || '设置止损失败', 'error');
    } finally { setSubmitting(false); }
  };

  // 仓位操作切换
  const togglePosAction = (posId: number, type: 'close' | 'margin' | 'stoploss', pos?: FuturesPosition) => {
    if (posAction?.id === posId && posAction.type === type) {
      setPosAction(null);
    } else {
      setPosAction({ id: posId, type });
      setPosActionInput(type === 'stoploss' && pos?.stopLossPercent ? String(pos.stopLossPercent) : '');
    }
  };

  // 合约取消订单
  const handleFuturesCancel = async (orderId: number) => {
    try {
      await futuresApi.cancel(orderId);
      toast('已取消', 'success');
      fetchFuturesOrders('', futuresOrderPage);
    } catch (e: unknown) {
      toast((e as Error).message || '取消失败', 'error');
    }
  };

  // 计算涨跌
  const points = activeTab === 0 ? points1D : points7D;
  const currentPrice = tick?.price ?? (points.length > 0 ? points[points.length - 1].close : 0);
  const firstPrice = points.length > 0 ? points[0].close : 0;
  const change = firstPrice ? currentPrice - firstPrice : 0;
  const changePct = firstPrice ? (change / firstPrice) * 100 : 0;
  const isUp = change >= 0;

  // 预估金额
  const qtyNum = parseFloat(quantity) || 0;
  const priceForCalc = orderType === 'LIMIT' ? (parseFloat(limitPrice) || 0) : currentPrice;
  const futuresPriceForCalc = futuresOrderType === 'LIMIT' ? (parseFloat(limitPrice) || 0) : currentPrice;
  const discountRate = useBuff && discountBuff && orderType === 'MARKET' ? Number(discountBuff.buffType.match(/DISCOUNT_(\d+)/)?.[1] ?? 100) / 100 : 1;
  const leveragedQty = side === 'BUY' && orderType === 'MARKET' && leverage > 1 ? qtyNum * leverage : qtyNum;
  const estimatedAmount = leveragedQty * priceForCalc;
  const marginAmount = qtyNum * priceForCalc; // 保证金部分
  const estimatedCommission = estimatedAmount * COMMISSION_RATE;

  return (
    <div className="max-w-4xl mx-auto p-4 space-y-4">
      {/* 顶部价格 */}
      <Card>
        <CardContent className="p-4">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className={`p-2.5 rounded-xl ${cfg.bgClass}`}>
                <Icon className={`w-6 h-6 ${cfg.colorClass}`} />
              </div>
              <div>
                <div className="flex items-center gap-2">
                  <span className="text-lg font-bold">{cfg.pair}</span>
                  <span className="flex items-center gap-1.5 text-[10px]">
                    <span className="flex items-center gap-0.5">
                      <span className={`inline-block w-1.5 h-1.5 rounded-full ${tick?.ws ? 'bg-green-500' : 'bg-red-400 animate-pulse'}`} />
                      <span className="text-muted-foreground">行情</span>
                    </span>
                    <span className="flex items-center gap-0.5">
                      <span className={`inline-block w-1.5 h-1.5 rounded-full ${tick?.fws ? 'bg-green-500' : 'bg-red-400 animate-pulse'}`} />
                      <span className="text-muted-foreground">合约</span>
                    </span>
                  </span>
                </div>
                <span className="text-xs text-muted-foreground">Binance</span>
                {symbol === 'PAXGUSDT' && (
                  <span className="text-[11px] text-yellow-500/70">1枚=1盎司黄金（31.1035克）</span>
                )}
              </div>
            </div>
            <div className="text-right">
              {currentPrice > 0 ? (
                <>
                  <div className="text-2xl font-bold tracking-tight">${formatPrice(currentPrice)}</div>
                  {symbol === 'PAXGUSDT' && usdCny > 0 && currentPrice > 0 && (
                    <div className="text-sm text-yellow-500/80 font-mono">
                      ¥{(currentPrice * usdCny / 31.1035).toFixed(2)}/克
                    </div>
                  )}
                  <div className={`flex items-center justify-end gap-1 text-sm ${isUp ? 'text-red-500' : 'text-green-500'}`}>
                    {isUp ? <TrendingUp className="w-3.5 h-3.5" /> : <TrendingDown className="w-3.5 h-3.5" />}
                    <span>{isUp ? '+' : ''}{formatPrice(change)}</span>
                    <span>({isUp ? '+' : ''}{changePct.toFixed(2)}%)</span>
                  </div>
                </>
              ) : (<Skeleton className="h-8 w-32" />)}
            </div>
          </div>
        </CardContent>
      </Card>

      {/* 图表 */}
      <Card>
        <CardHeader className="pb-2">
          <div className="flex items-center justify-between">
            <CardTitle className="text-base">走势</CardTitle>
            <div className="flex gap-1">
              {TABS.map((tab, i) => (
                <Button key={tab.label} variant={activeTab === i ? 'default' : 'ghost'} size="sm" className="h-7 px-2.5 text-xs" onClick={() => setActiveTab(i)}>{tab.label}</Button>
              ))}
              <Button variant={activeTab === 2 ? 'default' : 'ghost'} size="sm" className="h-7 px-2.5 text-xs" onClick={() => setActiveTab(2)}>高级</Button>
            </div>
          </div>
        </CardHeader>
        <CardContent className="p-2 relative">
          {loading && activeTab < TABS.length && points.length === 0 && <Skeleton className="absolute inset-0 z-10 m-2" style={{ height: 300 }} />}
          <div ref={chartRef1D} className="w-full" style={{ height: 300, display: activeTab === 0 ? 'block' : 'none' }} />
          <div ref={chartRef7D} className="w-full" style={{ height: 300, display: activeTab === 1 ? 'block' : 'none' }} />
          {activeTab === 2 && <div style={{ height: 500 }}><TradingViewWidget symbol={cfg.tvSymbol} /></div>}
        </CardContent>
      </Card>

      {/* 交易面板 */}
      <Card className="overflow-hidden">
        {/* 持仓信息 */}
        {position && (position.quantity > 0 || position.frozenQuantity > 0) && (() => {
          const pnlPct = position.avgCost > 0 && currentPrice > 0
            ? ((currentPrice - position.avgCost) / position.avgCost) * 100 : 0;
          const pnlAmount = currentPrice > 0
            ? (currentPrice - position.avgCost) * position.quantity : 0;
          const isPnlUp = pnlPct >= 0;
          return (
            <div className="px-4 pt-4">
              <div className={`rounded-xl border border-border/60 bg-gradient-to-r ${cfg.gradientClass} to-transparent p-3 space-y-2`}>
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <div className={`p-1.5 rounded-lg ${cfg.bgClass}`}>
                      <Icon className={`w-4 h-4 ${cfg.colorClass}`} />
                    </div>
                    <div>
                      <span className="text-sm font-semibold">{cfg.name}</span>
                      <span className="text-xs text-muted-foreground ml-1.5">{position.quantity} 个</span>
                      {symbol === 'PAXGUSDT' && (
                        <span className="text-[11px] text-yellow-500/60 ml-1">约合 {(position.quantity * 31.1035).toFixed(1)} 克</span>
                      )}
                    </div>
                  </div>
                  <div className="text-right">
                    <div className={`text-sm font-bold ${isPnlUp ? 'text-red-500' : 'text-green-500'}`}>
                      {isPnlUp ? '+' : ''}{pnlPct.toFixed(2)}%
                    </div>
                    <div className={`text-xs ${isPnlUp ? 'text-red-500/70' : 'text-green-500/70'}`}>
                      {isPnlUp ? '+' : ''}${formatPrice(pnlAmount)}
                    </div>
                  </div>
                </div>
                <div className="flex items-center gap-4 text-xs text-muted-foreground">
                  <span>均价 <span className="text-foreground font-mono">${formatPrice(position.avgCost)}</span></span>
                  <span>现价 <span className="text-foreground font-mono">${formatPrice(currentPrice)}</span></span>
                  <span>市值 <span className="text-foreground font-mono">${formatPrice(currentPrice * position.quantity)}</span></span>
                  {position.frozenQuantity > 0 && <span>冻结 <span className="text-yellow-500 font-mono">{position.frozenQuantity}</span></span>}
                  {position.totalDiscount > 0 && <span>已省 <span className="text-yellow-500 font-mono">${formatPrice(position.totalDiscount)}</span></span>}
                </div>
              </div>
            </div>
          );
        })()}

        {/* 买卖/做多做空 + 市价限价合约 */}
        <div className="px-4 pt-4 flex items-center gap-3">
          <div className="grid grid-cols-2 gap-0 flex-1 rounded-lg border border-border overflow-hidden">
            {orderType === 'FUTURES' ? (
              <>
                <button onClick={() => setFuturesSide('LONG')} className={`py-2.5 text-sm font-medium transition-all ${futuresSide === 'LONG' ? 'bg-red-500 text-white' : 'bg-card text-muted-foreground hover:text-foreground hover:bg-accent/50'}`}>做多</button>
                <button onClick={() => setFuturesSide('SHORT')} className={`py-2.5 text-sm font-medium transition-all border-l border-border ${futuresSide === 'SHORT' ? 'bg-green-500 text-white' : 'bg-card text-muted-foreground hover:text-foreground hover:bg-accent/50'}`}>做空</button>
              </>
            ) : (
              <>
                <button onClick={() => setSide('BUY')} className={`py-2.5 text-sm font-medium transition-all ${side === 'BUY' ? 'bg-primary text-primary-foreground' : 'bg-card text-muted-foreground hover:text-foreground hover:bg-accent/50'}`}>买入</button>
                <button onClick={() => setSide('SELL')} className={`py-2.5 text-sm font-medium transition-all border-l border-border ${side === 'SELL' ? 'bg-primary text-primary-foreground' : 'bg-card text-muted-foreground hover:text-foreground hover:bg-accent/50'}`}>卖出</button>
              </>
            )}
          </div>
          <div className="flex rounded-lg border border-border overflow-hidden">
            {(['MARKET', 'LIMIT', 'FUTURES'] as const).map((t, i) => (
              <button key={t} onClick={() => setOrderType(t)} className={`px-3.5 py-2.5 text-xs font-medium transition-all ${i > 0 ? 'border-l border-border' : ''} ${orderType === t ? 'bg-primary text-primary-foreground' : 'bg-card text-muted-foreground hover:text-foreground hover:bg-accent/50'}`}>
                {t === 'MARKET' ? '市价' : t === 'LIMIT' ? '限价' : '合约'}
              </button>
            ))}
          </div>
        </div>

        <CardContent className="p-4 space-y-3">
          {/* 合约面板 */}
          {orderType === 'FUTURES' && (
            <>
              {/* 执行方式 */}
              <div className="flex items-center gap-2">
                <span className="text-xs text-muted-foreground shrink-0">执行方式</span>
                <div className="flex rounded-md border border-border overflow-hidden">
                  <button onClick={() => setFuturesOrderType('MARKET')} className={`px-3 py-1.5 text-xs font-medium transition-all ${futuresOrderType === 'MARKET' ? 'bg-primary text-primary-foreground' : 'bg-card text-muted-foreground hover:text-foreground'}`}>市价</button>
                  <button onClick={() => setFuturesOrderType('LIMIT')} className={`px-3 py-1.5 text-xs font-medium transition-all border-l border-border ${futuresOrderType === 'LIMIT' ? 'bg-primary text-primary-foreground' : 'bg-card text-muted-foreground hover:text-foreground'}`}>限价</button>
                </div>
              </div>

              {/* 限价输入 */}
              {futuresOrderType === 'LIMIT' && (
                <div className="space-y-1.5">
                  <label className="text-xs text-muted-foreground">限价 (USDT)</label>
                  <Input type="number" placeholder="输入限价" value={limitPrice} onChange={e => setLimitPrice(e.target.value)} step="0.01" min="0" />
                </div>
              )}

              {/* 杠杆选择 */}
              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <label className="text-xs text-muted-foreground flex items-center gap-1">
                    <Scale className="w-3 h-3" /> 杠杆
                  </label>
                  <span className="text-sm font-bold tabular-nums">{futuresLeverage}x</span>
                </div>
                <div className="flex gap-1">
                  {[1, 5, 10, 25, 50, 75, 100].map(lv => (
                    <button key={lv} onClick={() => setFuturesLeverage(lv)} className={`flex-1 py-1 text-[11px] font-medium rounded transition-all ${futuresLeverage === lv ? 'bg-primary text-primary-foreground' : 'bg-card border border-border text-muted-foreground hover:text-foreground'}`}>
                      {lv}x
                    </button>
                  ))}
                </div>
                <input
                  type="range" min={1} max={100}
                  value={futuresLeverage}
                  onChange={e => setFuturesLeverage(Number(e.target.value))}
                  className="w-full cursor-pointer"
                  style={{ accentColor: 'hsl(var(--primary))' }}
                />
              </div>

              {/* 数量输入 */}
              <div className="space-y-1.5">
                <div className="flex items-center justify-between">
                  <label className="text-xs text-muted-foreground">保证金数量 ({cfg.name})</label>
                  {user && (
                    <span className="text-xs text-muted-foreground flex items-center gap-1">
                      <Wallet className="w-3 h-3" />
                      {formatPrice(user.balance)} USDT
                    </span>
                  )}
                </div>
                <Input type="number" placeholder={String(MIN_QTY)} value={quantity} onChange={e => setQuantity(e.target.value)} step={String(MIN_QTY)} min={MIN_QTY} />
                {futuresPriceForCalc > 0 && (
                  <div className="flex gap-1.5">
                    {POSITION_PCTS.map(pct => {
                      const handlePct = () => {
                        const balance = user?.balance ?? 0;
                        const qty = calcMaxAffordableMarginQty(balance, pct, futuresPriceForCalc, futuresLeverage, MIN_QTY);
                        setQuantity(qty > 0 ? formatStepValue(qty, MIN_QTY) : '');
                      };
                      return (
                        <button key={pct} onClick={handlePct} className="flex-1 py-1.5 rounded-md text-xs font-medium border border-border bg-card text-muted-foreground hover:text-foreground hover:bg-accent transition-all">
                          {pct * 100}%
                        </button>
                      );
                    })}
                  </div>
                )}
              </div>

              {/* 预估信息 */}
              {parseFloat(quantity) > 0 && futuresPriceForCalc > 0 && (() => {
                const qty = parseFloat(quantity);
                const { positionValue, margin, commission, fundingFee, totalCost } = calcFuturesOpenEstimate(qty, futuresPriceForCalc, futuresLeverage);
                return (
                  <div className="space-y-1.5 p-3 rounded-lg bg-accent/30 border border-border/50">
                    <div className="flex justify-between text-xs">
                      <span className="text-muted-foreground">仓位价值</span>
                      <span className="font-mono">${formatPrice(positionValue)}</span>
                    </div>
                    <div className="flex justify-between text-xs">
                      <span className="text-muted-foreground">保证金</span>
                      <span className="font-mono">${formatPrice(margin)}</span>
                    </div>
                    <div className="flex justify-between text-xs">
                      <span className="text-muted-foreground">手续费 (0.1%)</span>
                      <span className="font-mono">${formatPrice(commission)}</span>
                    </div>
                    <div className="flex justify-between text-xs">
                      <span className="text-muted-foreground">资金费率 (0.01%/8h)</span>
                      <span className="font-mono">${formatPrice(fundingFee)}</span>
                    </div>
                    <div className="flex justify-between text-xs font-medium pt-1.5 border-t border-border/50">
                      <span className="text-muted-foreground">合计需要</span>
                      <span>${formatPrice(totalCost)}</span>
                    </div>
                  </div>
                );
              })()}

              {/* 开仓止损 (可选) */}
              <div className="space-y-1.5">
                <div className="flex items-center justify-between">
                  <label className="text-xs text-muted-foreground">开仓止损</label>
                  <button
                    type="button"
                    onClick={() => { setFuturesStopLoss(!futuresStopLoss); setFuturesStopLossPct(''); }}
                    className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${futuresStopLoss ? 'bg-primary' : 'bg-muted-foreground/30'}`}
                  >
                    <span className={`inline-block h-3.5 w-3.5 transform rounded-full bg-background transition-transform shadow-sm ${futuresStopLoss ? 'translate-x-[18px]' : 'translate-x-[3px]'}`} />
                  </button>
                </div>
                {futuresStopLoss && (() => {
                  const minPct = MMR * (futuresLeverage - 1) / (1 - MMR) * 100;
                  const pctVal = parseFloat(futuresStopLossPct) || 0;
                  let estPrice = 0;
                  if (pctVal > minPct && currentPrice > 0) {
                    const priceMove = currentPrice * (1 - pctVal / 100) / futuresLeverage;
                    estPrice = futuresSide === 'LONG' ? currentPrice - priceMove : currentPrice + priceMove;
                  }
                  return (
                    <div className="space-y-1.5 p-2.5 rounded-lg bg-accent/30 border border-border/50">
                      <div className="flex items-center gap-2">
                        <Input
                          type="number" placeholder={`最低 ${minPct.toFixed(2)}`}
                          value={futuresStopLossPct} onChange={e => setFuturesStopLossPct(e.target.value)}
                          step="0.1" min={minPct} max={99} className="flex-1"
                        />
                        <span className="text-xs text-muted-foreground shrink-0">% 保留</span>
                      </div>
                      <div className="text-[11px] text-muted-foreground">
                        保留保证金比例，越高止损越早 · 最低 {minPct.toFixed(2)}%
                      </div>
                      {estPrice > 0 && (
                        <div className="text-xs">
                          预估止损价 <span className="text-yellow-500 font-mono">${formatPrice(estPrice)}</span>
                        </div>
                      )}
                    </div>
                  );
                })()}
              </div>

              {/* 开仓按钮 */}
              <Button onClick={handleSubmit} disabled={submitting || currentPrice <= 0} className={`w-full h-11 font-medium text-sm rounded-lg ${futuresSide === 'LONG' ? 'bg-red-500 hover:bg-red-600' : 'bg-green-500 hover:bg-green-600'} text-white`}>
                {submitting ? <Loader2 className="w-4 h-4 animate-spin" /> : `${futuresSide === 'LONG' ? '做多' : '做空'} ${futuresLeverage}x`}
              </Button>

              {/* 合约持仓列表 */}
              {futuresPositions.length > 0 && (
                <div className="pt-3 border-t border-border/30 space-y-2">
                  <div className="flex items-center justify-between">
                    <div className="text-sm font-medium">持仓</div>
                    <span className="text-[11px] text-muted-foreground">{futuresPositions.length} 个</span>
                  </div>
                  {futuresPositions.map(pos => {
                    const isPnlUp = pos.unrealizedPnl >= 0;
                    const isLong = pos.side === 'LONG';
                    const isActive = posAction?.id === pos.id;
                    return (
                      <div key={pos.id} className={`p-3 rounded-lg border space-y-2 ${isLong ? 'border-red-500/20 bg-red-500/[0.03]' : 'border-green-500/20 bg-green-500/[0.03]'}`}>
                        <div className="flex items-center justify-between">
                          <div className="flex items-center gap-2">
                            <Badge className={`text-[10px] px-1.5 ${isLong ? 'bg-red-500' : 'bg-green-500'}`}>{isLong ? '多' : '空'}</Badge>
                            <span className="text-sm font-bold">{pos.leverage}x</span>
                            <span className="text-xs text-muted-foreground">{pos.quantity} {cfg.name}</span>
                          </div>
                          <div className="text-right">
                            <div className={`text-sm font-bold ${isPnlUp ? 'text-red-500' : 'text-green-500'}`}>
                              {isPnlUp ? '+' : ''}{pos.unrealizedPnlPct.toFixed(2)}%
                            </div>
                            <div className={`text-xs ${isPnlUp ? 'text-red-500/70' : 'text-green-500/70'}`}>
                              {isPnlUp ? '+' : ''}${formatPrice(pos.unrealizedPnl)}
                            </div>
                          </div>
                        </div>
                        <div className="grid grid-cols-3 gap-x-3 gap-y-1 text-[11px] text-muted-foreground">
                          <div>开仓 <span className="text-foreground font-mono">${formatPrice(pos.entryPrice)}</span></div>
                          <div>标记 <span className="text-foreground font-mono">${formatPrice(pos.markPrice)}</span></div>
                          <div>现价 <span className="text-foreground font-mono">${formatPrice(currentPrice)}</span></div>
                          <div>保证金 <span className="text-foreground font-mono">${formatPrice(pos.margin)}</span></div>
                          <div>强平 <span className="text-yellow-500 font-mono">${formatPrice(pos.liquidationPrice)}</span></div>
                          <div>止损 <span className="font-mono">{pos.stopLossPrice ? `$${formatPrice(pos.stopLossPrice)}` : '-'}</span></div>
                        </div>
                        {/* 操作按钮 */}
                        <div className="grid grid-cols-3 gap-2 pt-1">
                          <Button size="sm" variant={isActive && posAction.type === 'close' ? 'default' : 'outline'} className="h-7 text-[11px]" onClick={() => togglePosAction(pos.id, 'close')}>平仓</Button>
                          <Button size="sm" variant={isActive && posAction.type === 'margin' ? 'default' : 'outline'} className="h-7 text-[11px]" onClick={() => togglePosAction(pos.id, 'margin')}>+保证金</Button>
                          <Button size="sm" variant={isActive && posAction.type === 'stoploss' ? 'default' : 'outline'} className="h-7 text-[11px]" onClick={() => togglePosAction(pos.id, 'stoploss', pos)}>止损</Button>
                        </div>
                        {/* 内联操作面板 */}
                        {isActive && (
                          <div className="pt-2 mt-1 border-t border-border/30 space-y-2">
                            {posAction.type === 'close' && (
                              <>
                                <div className="flex items-center gap-2">
                                  <Input type="number" placeholder="平仓数量" value={posActionInput} onChange={e => setPosActionInput(e.target.value)} step={String(MIN_QTY)} min={MIN_QTY} max={pos.quantity} className="flex-1 h-8 text-xs" />
                                  <span className="text-[11px] text-muted-foreground shrink-0">/ {pos.quantity}</span>
                                </div>
                                <div className="flex gap-1">
                                  {POSITION_PCTS.map(pct => (
                                    <button key={pct} onClick={() => {
                                      const raw = pos.quantity * pct;
                                      setPosActionInput(pct === 1 ? String(pos.quantity) : (Math.round(raw / MIN_QTY) * MIN_QTY).toFixed(8).replace(/0+$/, '').replace(/\.$/, ''));
                                    }} className="flex-1 py-1 rounded text-[11px] font-medium border border-border bg-card text-muted-foreground hover:text-foreground hover:bg-accent transition-all">
                                      {pct * 100}%
                                    </button>
                                  ))}
                                </div>
                                <Button size="sm" className="w-full h-8 text-xs" onClick={() => handleFuturesClose(pos.id)} disabled={submitting}>
                                  {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : '确认平仓'}
                                </Button>
                              </>
                            )}
                            {posAction.type === 'margin' && (
                              <>
                                <Input type="number" placeholder="追加金额 (USDT)" value={posActionInput} onChange={e => setPosActionInput(e.target.value)} step="0.01" min="0" className="h-8 text-xs" />
                                {user && <div className="text-[11px] text-muted-foreground">可用余额 {formatPrice(user.balance)} USDT</div>}
                                <Button size="sm" className="w-full h-8 text-xs" onClick={() => handleAddMargin(pos.id)} disabled={submitting}>
                                  {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : '确认追加'}
                                </Button>
                              </>
                            )}
                            {posAction.type === 'stoploss' && (() => {
                              const minPct = MMR * (pos.leverage - 1) / (1 - MMR) * 100;
                              const pctVal = parseFloat(posActionInput) || 0;
                              let estPrice = 0;
                              if (pctVal > minPct && pos.entryPrice > 0) {
                                const maxLoss = pos.margin * (1 - pctVal / 100);
                                const priceMove = maxLoss / pos.quantity;
                                estPrice = isLong ? pos.entryPrice - priceMove : pos.entryPrice + priceMove;
                              }
                              return (
                                <>
                                  <div className="flex items-center gap-2">
                                    <Input type="number" placeholder={`最低 ${minPct.toFixed(2)}`} value={posActionInput} onChange={e => setPosActionInput(e.target.value)} step="0.1" min={minPct} max={99} className="flex-1 h-8 text-xs" />
                                    <span className="text-[11px] text-muted-foreground shrink-0">% 保留</span>
                                  </div>
                                  {pos.stopLossPercent != null && (
                                    <div className="text-[11px] text-muted-foreground">当前: {pos.stopLossPercent}% → ${pos.stopLossPrice ? formatPrice(pos.stopLossPrice) : '-'}</div>
                                  )}
                                  <div className="text-[11px] text-muted-foreground">最低 {minPct.toFixed(2)}%，越高止损越早</div>
                                  {estPrice > 0 && (
                                    <div className="text-xs">新止损价 <span className="text-yellow-500 font-mono">${formatPrice(estPrice)}</span></div>
                                  )}
                                  <Button size="sm" className="w-full h-8 text-xs" onClick={() => handleSetStopLoss(pos.id)} disabled={submitting}>
                                    {submitting ? <Loader2 className="w-3 h-3 animate-spin" /> : '确认设置'}
                                  </Button>
                                </>
                              );
                            })()}
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              )}
            </>
          )}

          {/* 现货面板（原有逻辑） */}
          {orderType !== 'FUTURES' && (
            <>
          {/* 限价输入 */}
          {orderType === 'LIMIT' && (
            <div className="space-y-1.5">
              <label className="text-xs text-muted-foreground">限价 (USDT)</label>
              <Input type="number" placeholder="输入限价" value={limitPrice} onChange={e => setLimitPrice(e.target.value)} step="0.01" min="0" />
            </div>
          )}

          {/* 数量 + 余额 */}
          <div className="space-y-1.5">
            <div className="flex items-center justify-between">
              <label className="text-xs text-muted-foreground">数量 ({cfg.name})</label>
              {user && (
                <span className="text-xs text-muted-foreground flex items-center gap-1">
                  <Wallet className="w-3 h-3" />
                  {side === 'BUY'
                    ? <>{formatPrice(user.balance)} USDT</>
                    : <>{position?.quantity ?? 0} {cfg.name}</>
                  }
                </span>
              )}
            </div>
            <Input type="number" placeholder={String(MIN_QTY)} value={quantity} onChange={e => setQuantity(e.target.value)} step="0.001" min={MIN_QTY} />
            {currentPrice > 0 && (
              <div className="space-y-1">
                <label className="text-xs text-muted-foreground flex items-center gap-1">
                  <Warehouse className="w-3 h-3" /> 仓位
                </label>
                <div className="flex gap-1.5">
                  {POSITION_PCTS.map(pct => {
                  const handlePct = () => {
                    let raw: number;
                    if (side === 'BUY') {
                      const balance = user?.balance ?? 0;
                      const lv = orderType === 'MARKET' ? leverage : 1;
                      raw = (balance * pct) / (currentPrice * (1 + COMMISSION_RATE * lv));
                    } else {
                      raw = (position?.quantity ?? 0) * pct;
                    }
                    const qty = Math.max(MIN_QTY, Math.round(raw / MIN_QTY) * MIN_QTY);
                    setQuantity(qty <= MIN_QTY && raw < MIN_QTY ? String(MIN_QTY) : qty.toFixed(5));
                  };
                  return (
                    <button key={pct} onClick={handlePct} className="flex-1 py-1.5 rounded-md text-xs font-medium border border-border bg-card text-muted-foreground hover:text-foreground hover:bg-accent transition-all">
                      {pct * 100}%
                    </button>
                  );
                })}
                </div>
              </div>
            )}
          </div>

          {/* 杠杆 - 仅市价买入 */}
          {side === 'BUY' && orderType === 'MARKET' && (
            <div className="space-y-1.5">
              <label className="text-xs text-muted-foreground flex items-center gap-1">
                <Scale className="w-3 h-3" /> 杠杆{useBuff ? ' (使用折扣时不支持)' : ''}
              </label>
              <div className={useBuff ? 'opacity-40 pointer-events-none' : ''}>
                <select
                  value={leverage}
                  onChange={e => setLeverage(Number(e.target.value))}
                  className="w-full h-9 rounded-md border border-border bg-card px-3 text-sm text-foreground focus:outline-none focus:ring-1 focus:ring-ring appearance-none cursor-pointer"
                  style={{ backgroundImage: `url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 24 24' fill='none' stroke='%23888' stroke-width='2'%3E%3Cpath d='m6 9 6 6 6-6'/%3E%3C/svg%3E")`, backgroundRepeat: 'no-repeat', backgroundPosition: 'right 10px center' }}
                >
                  {LEVERAGE_OPTIONS.map(lv => (
                    <option key={lv} value={lv}>{lv}x{lv === 1 ? ' (无杠杆)' : ''}</option>
                  ))}
                </select>
              </div>
            </div>
          )}

          {/* 折扣券 - 仅市价买入且有可用券 */}
          {side === 'BUY' && orderType === 'MARKET' && discountBuff && (
            <div className="space-y-1.5">
              <div className="flex items-center justify-between">
                <label className="text-xs text-muted-foreground flex items-center gap-1">
                  <Sparkles className="w-3.5 h-3.5 text-yellow-500" />
                  折扣券{leverage > 1 ? ' (使用杠杆时不支持)' : ''}
                </label>
                <button
                  type="button"
                  onClick={() => { if (leverage > 1) return; setUseBuff(v => !v); }}
                  disabled={leverage > 1}
                  className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors ${useBuff ? 'bg-yellow-500' : 'bg-muted-foreground/30'} ${leverage > 1 ? 'opacity-40 cursor-not-allowed' : ''}`}
                >
                  <span className={`inline-block h-3.5 w-3.5 transform rounded-full bg-background transition-transform shadow-sm ${useBuff ? 'translate-x-[18px]' : 'translate-x-[3px]'}`} />
                </button>
              </div>
              {useBuff && (
                <div className="flex items-center gap-2 text-xs bg-yellow-500/10 border border-yellow-500/20 rounded-lg px-3 py-2">
                  <Badge className="bg-yellow-100 text-yellow-700 text-[10px]">{discountBuff.buffName}</Badge>
                  <span className="text-muted-foreground">本次买入整单折扣</span>
                </div>
              )}
            </div>
          )}

          {/* 预估 + 提交 */}
          <div className="pt-2 border-t border-border/30 space-y-3">
            {qtyNum > 0 && priceForCalc > 0 && (
              <div className="space-y-1.5">
                {side === 'BUY' && leverage > 1 && (
                  <div className="flex justify-between text-xs text-muted-foreground">
                    <span>总仓位 ({leverage}x)</span>
                    <span className="font-mono text-foreground">${formatPrice(estimatedAmount)} USDT</span>
                  </div>
                )}
                <div className="flex justify-between text-xs text-muted-foreground">
                  <span>{side === 'BUY' && leverage > 1 ? '保证金' : `预估${side === 'BUY' ? '花费' : '收入'}`}</span>
                  <span className="font-medium text-foreground">
                    {useBuff && discountRate < 1 && side === 'BUY' && (
                      <span className="line-through text-muted-foreground mr-1">${formatPrice(marginAmount)}</span>
                    )}
                    ${formatPrice(marginAmount * discountRate)} USDT
                  </span>
                </div>
                <div className="flex justify-between text-xs text-muted-foreground">
                  <span>手续费 (0.1%)</span>
                  <span className="font-mono">${formatPrice(estimatedCommission * discountRate)} USDT</span>
                </div>
                {side === 'BUY' && (
                  <div className="flex justify-between text-xs font-medium">
                    <span className="text-muted-foreground">合计</span>
                    <span className="text-foreground">
                      ${formatPrice((marginAmount + estimatedCommission) * discountRate)} USDT
                    </span>
                  </div>
                )}
              </div>
            )}
            <Button onClick={handleSubmit} disabled={submitting || currentPrice <= 0} className="w-full h-11 font-medium text-sm rounded-lg bg-primary hover:bg-primary/90 text-primary-foreground">
              {submitting ? <Loader2 className="w-4 h-4 animate-spin" /> : (side === 'BUY' ? `买入 ${cfg.name}` : `卖出 ${cfg.name}`)}
            </Button>
          </div>
            </>
          )}
        </CardContent>
      </Card>

      {/* 订单列表 */}
      <Card>
        <CardHeader className="pb-2">
          <div className="flex items-center justify-between">
            <CardTitle className="text-base flex items-center gap-2">
              {orderType === 'FUTURES' ? '合约订单' : '订单'}
              <button onClick={() => orderType === 'FUTURES' ? fetchFuturesOrders(futuresOrderFilter, futuresOrderPage) : fetchOrders(orderFilter, orderPage)} className="text-muted-foreground hover:text-foreground transition-colors" title="刷新">
                <RefreshCw className={`w-3.5 h-3.5 ${(orderType === 'FUTURES' ? futuresOrdersLoading : ordersLoading) ? 'animate-spin' : ''}`} />
              </button>
            </CardTitle>
            <div className="flex gap-1">
              {orderType === 'FUTURES' ? (
                FUTURES_ORDER_FILTERS.map(f => (
                  <Button key={f.value} variant={futuresOrderFilter === f.value ? 'default' : 'ghost'} size="sm" className="h-7 px-2.5 text-xs" onClick={() => { setFuturesOrderFilter(f.value); setFuturesOrderPage(1); }}>
                    {f.label}
                  </Button>
                ))
              ) : (
                ORDER_STATUS_FILTERS.map(f => (
                  <Button key={f.value} variant={orderFilter === f.value ? 'default' : 'ghost'} size="sm" className="h-7 px-2.5 text-xs" onClick={() => { setOrderFilter(f.value); setOrderPage(1); }}>
                    {f.label}
                  </Button>
                ))
              )}
            </div>
          </div>
        </CardHeader>
        <CardContent className="p-0">
          {orderType === 'FUTURES' ? (
            /* 合约订单表 */
            futuresOrdersLoading && futuresOrders.length === 0 ? (
              <div className="p-4"><Skeleton className="w-full h-32" /></div>
            ) : futuresOrders.length === 0 ? (
              <div className="p-8 text-center text-sm text-muted-foreground">暂无合约订单</div>
            ) : (
              <>
                <div className="overflow-x-auto">
                  <table className="w-full text-xs">
                    <thead>
                      <tr className="border-b border-border/50 text-muted-foreground">
                        <th className="text-left px-4 py-2.5 font-medium">时间</th>
                        <th className="text-left px-2 py-2.5 font-medium">方向</th>
                        <th className="text-left px-2 py-2.5 font-medium">类型</th>
                        <th className="text-right px-2 py-2.5 font-medium">数量</th>
                        <th className="text-right px-2 py-2.5 font-medium">杠杆</th>
                        <th className="text-right px-2 py-2.5 font-medium">限价</th>
                        <th className="text-right px-2 py-2.5 font-medium">成交价</th>
                        <th className="text-right px-2 py-2.5 font-medium">盈亏</th>
                        <th className="text-center px-2 py-2.5 font-medium">状态</th>
                        <th className="text-center px-4 py-2.5 font-medium">操作</th>
                      </tr>
                    </thead>
                    <tbody>
                      {futuresOrders.map(o => {
                        const sm = FUTURES_SIDE_MAP[o.orderSide] || { label: o.orderSide, color: 'text-foreground' };
                        const st = STATUS_MAP[o.status] || { label: o.status, variant: 'outline' as const };
                        const hasPnl = o.realizedPnl != null && o.realizedPnl !== 0;
                        return (
                          <tr key={o.orderId} className="border-b border-border/30 hover:bg-accent/30 transition-colors">
                            <td className="px-4 py-2.5 text-muted-foreground whitespace-nowrap">{formatDateTime(o.createdAt)}</td>
                            <td className={`px-2 py-2.5 font-medium ${sm.color}`}>{sm.label}</td>
                            <td className="px-2 py-2.5">{o.orderType === 'MARKET' ? '市价' : '限价'}</td>
                            <td className="px-2 py-2.5 text-right font-mono">{o.quantity}</td>
                            <td className="px-2 py-2.5 text-right font-mono">{o.leverage}x</td>
                            <td className="px-2 py-2.5 text-right font-mono">{o.limitPrice != null ? formatPrice(o.limitPrice) : '-'}</td>
                            <td className="px-2 py-2.5 text-right font-mono">{o.filledPrice != null ? formatPrice(o.filledPrice) : '-'}</td>
                            <td className={`px-2 py-2.5 text-right font-mono ${hasPnl ? (o.realizedPnl! > 0 ? 'text-red-500' : 'text-green-500') : ''}`}>
                              {hasPnl ? `${o.realizedPnl! > 0 ? '+' : ''}${formatPrice(o.realizedPnl!)}` : '-'}
                            </td>
                            <td className="px-2 py-2.5 text-center"><Badge variant={st.variant}>{st.label}</Badge></td>
                            <td className="px-4 py-2.5 text-center">
                              {o.status === 'PENDING' ? (
                                <button onClick={() => handleFuturesCancel(o.orderId)} className="text-muted-foreground hover:text-red-500 transition-colors" title="取消"><X className="w-3.5 h-3.5 inline" /></button>
                              ) : <span className="text-muted-foreground/30">-</span>}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
                {futuresOrderPages > 1 && (
                  <div className="flex items-center justify-between px-4 py-3 border-t border-border/30">
                    <span className="text-xs text-muted-foreground">共 {futuresOrderTotal} 条</span>
                    <div className="flex items-center gap-1">
                      <Button variant="ghost" size="sm" className="h-7 w-7 p-0" disabled={futuresOrderPage <= 1} onClick={() => setFuturesOrderPage(p => p - 1)}>
                        <ChevronLeft className="w-3.5 h-3.5" />
                      </Button>
                      <span className="text-xs px-2">{futuresOrderPage} / {futuresOrderPages}</span>
                      <Button variant="ghost" size="sm" className="h-7 w-7 p-0" disabled={futuresOrderPage >= futuresOrderPages} onClick={() => setFuturesOrderPage(p => p + 1)}>
                        <ChevronRight className="w-3.5 h-3.5" />
                      </Button>
                    </div>
                  </div>
                )}
              </>
            )
          ) : (
            /* 现货订单表 */
            ordersLoading && orders.length === 0 ? (
              <div className="p-4"><Skeleton className="w-full h-32" /></div>
            ) : orders.length === 0 ? (
              <div className="p-8 text-center text-sm text-muted-foreground">暂无订单</div>
            ) : (
              <>
                <div className="overflow-x-auto">
                  <table className="w-full text-xs">
                    <thead>
                      <tr className="border-b border-border/50 text-muted-foreground">
                        <th className="text-left px-4 py-2.5 font-medium">时间</th>
                        <th className="text-left px-2 py-2.5 font-medium">方向</th>
                        <th className="text-left px-2 py-2.5 font-medium">类型</th>
                        <th className="text-right px-2 py-2.5 font-medium">数量</th>
                        <th className="text-right px-2 py-2.5 font-medium">挂单价</th>
                        <th className="text-right px-2 py-2.5 font-medium">触发价</th>
                        <th className="text-right px-2 py-2.5 font-medium">金额</th>
                        <th className="text-center px-2 py-2.5 font-medium">状态</th>
                        <th className="text-center px-4 py-2.5 font-medium">操作</th>
                      </tr>
                    </thead>
                    <tbody>
                      {orders.map(o => {
                        const isBuy = o.orderSide === 'BUY';
                        const st = STATUS_MAP[o.status] || { label: o.status, variant: 'outline' as const };
                        return (
                          <tr key={o.orderId} className="border-b border-border/30 hover:bg-accent/30 transition-colors">
                            <td className="px-4 py-2.5 text-muted-foreground whitespace-nowrap">{formatDateTime(o.createdAt)}</td>
                            <td className={`px-2 py-2.5 font-medium ${isBuy ? 'text-red-500' : 'text-green-500'}`}>{isBuy ? '买入' : '卖出'}</td>
                            <td className="px-2 py-2.5">{o.orderType === 'MARKET' ? '市价' : '限价'}{o.leverage > 1 ? ` ${o.leverage}x` : ''}</td>
                            <td className="px-2 py-2.5 text-right font-mono">{o.quantity}</td>
                            <td className="px-2 py-2.5 text-right font-mono">{o.limitPrice != null ? formatPrice(o.limitPrice) : '-'}</td>
                            <td className="px-2 py-2.5 text-right font-mono">{o.triggerPrice != null ? formatPrice(o.triggerPrice) : '-'}</td>
                            <td className="px-2 py-2.5 text-right font-mono">{o.filledAmount != null ? formatPrice(o.filledAmount) : '-'}</td>
                            <td className="px-2 py-2.5 text-center"><Badge variant={st.variant}>{st.label}</Badge></td>
                            <td className="px-4 py-2.5 text-center">
                              {o.status === 'PENDING' ? (
                                <button onClick={() => handleCancel(o.orderId)} className="text-muted-foreground hover:text-red-500 transition-colors" title="取消"><X className="w-3.5 h-3.5 inline" /></button>
                              ) : <span className="text-muted-foreground/30">-</span>}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
                {orderPages > 1 && (
                  <div className="flex items-center justify-between px-4 py-3 border-t border-border/30">
                    <span className="text-xs text-muted-foreground">共 {orderTotal} 条</span>
                    <div className="flex items-center gap-1">
                      <Button variant="ghost" size="sm" className="h-7 w-7 p-0" disabled={orderPage <= 1} onClick={() => setOrderPage(p => p - 1)}>
                        <ChevronLeft className="w-3.5 h-3.5" />
                      </Button>
                      <span className="text-xs px-2">{orderPage} / {orderPages}</span>
                      <Button variant="ghost" size="sm" className="h-7 w-7 p-0" disabled={orderPage >= orderPages} onClick={() => setOrderPage(p => p + 1)}>
                        <ChevronRight className="w-3.5 h-3.5" />
                      </Button>
                    </div>
                  </div>
                )}
              </>
            )
          )}
        </CardContent>
      </Card>
    </div>
  );
}
