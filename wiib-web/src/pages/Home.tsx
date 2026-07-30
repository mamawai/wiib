import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import NumberFlow from '@number-flow/react';
import { buffApi, cryptoOrderApi, futuresApi, userApi } from '../api';
import { HomeMarketSection } from '../components/HomeMarketSection';
import { DailyBuffModal } from '../components/DailyBuffCard';
import { LatestTradesCard } from '../components/LatestTradesCard';
import type { TradeItem } from '../components/LatestTradesCard';
import { ForceOrdersCard } from '../components/ForceOrdersCard';
import { NewsFlashCard } from '../components/NewsFlashCard';
import { HomeFaq } from '../components/HomeFaq';
import { Card, CardContent } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { useToast } from '../components/ui/use-toast';
import { SpotlightCard } from '../components/fx/SpotlightCard';
import { DecryptedText } from '../components/fx/DecryptedText';
import { Sparkline } from '../components/fx/Sparkline';
import { AnimNum } from '../components/fx/AnimNum';
import { DailyGrid } from '../components/DailyGrid';
import { DayDetailModal } from '../components/DayDetailModal';
import { HelpTip } from '../components/HelpTip';
import {
  RefreshCcw, Bell, Gamepad2, List, DollarSign, ArrowRight, Target, Brain, Gift,
} from 'lucide-react';
import type { BuffStatus, AssetSnapshot } from '../types';
import { useUserStore } from '../stores/userStore';
import { cn, fmtDate, fmtMoney } from '../lib/utils';
import { orderSideView } from '../lib/orderSide';

const HIDE_NOTICE_KEY = 'wiib-notice-hide-date';
function shouldShowNotice() { const d = localStorage.getItem(HIDE_NOTICE_KEY); return !d || d !== new Date().toDateString(); }

function greeting(): string {
  const h = new Date().getHours();
  if (h < 5) return '夜深了';
  if (h < 11) return '早上好';
  if (h < 13) return '中午好';
  if (h < 18) return '下午好';
  return '晚上好';
}

export function Home() {
  const navigate = useNavigate();
  const { toast } = useToast();
  const { user } = useUserStore();
  // 路由已挡住未登录，user 为 null 只可能是 fetchUser 还没回来。
  // 行情/成交那几块不依赖 user，先渲染出来，本人相关的卡等 user 到了再补
  const ready = !!user;
  const [refreshNonce, setRefreshNonce] = useState(0);

  const [buffStatus, setBuffStatus] = useState<BuffStatus | null>(null);
  const [buffOpen, setBuffOpen] = useState(false);
  const [latestTrades, setLatestTrades] = useState<TradeItem[]>([]);
  // tradesLoading 由"已加载 nonce 是否追上刷新 nonce"派生
  const [tradesLoadedNonce, setTradesLoadedNonce] = useState(-1);
  const tradesLoading = tradesLoadedNonce !== refreshNonce;

  // 驾驶舱数据：资产曲线(30d) + 实时快照(今日盈亏) + 月度网格(逐日快照)
  const [history, setHistory] = useState<AssetSnapshot[]>([]);
  const [realtime, setRealtime] = useState<AssetSnapshot | null>(null);
  const [monthCells, setMonthCells] = useState<AssetSnapshot[]>([]);
  const [gridMonth, setGridMonth] = useState(() => fmtDate().slice(0, 7));
  const [selectedDate, setSelectedDate] = useState<string | null>(null);

  useEffect(() => { if (shouldShowNotice()) navigate('/intro', { replace: true }); }, [navigate]);

  useEffect(() => {
    if (ready) buffApi.status().then(setBuffStatus).catch(() => {});
  }, [ready, refreshNonce]);

  useEffect(() => {
    if (!ready) return;
    userApi.assetHistory(30).then(setHistory).catch(() => {});
    userApi.assetRealtime().then(setRealtime).catch(() => {});
  }, [ready, refreshNonce]);

  // 网格按月拉：翻月就再问一次。旧月数据先留着不清，避免切月时整片格子闪白
  useEffect(() => {
    if (!ready) return;
    let cancelled = false;
    userApi.assetDaily(gridMonth)
      .then(rows => { if (!cancelled) setMonthCells(rows); })
      .catch(() => { if (!cancelled) setMonthCells([]); });
    return () => { cancelled = true; };
  }, [ready, gridMonth, refreshNonce]);

  useEffect(() => {
    Promise.all([cryptoOrderApi.live().catch(() => []), futuresApi.live().catch(() => [])])
      .then(([co, fo]) => {
        const ci: TradeItem[] = co.map(o => ({ id: `c-${o.orderId}`, orderSide: o.orderSide, sideTone: o.orderSide === 'BUY' ? 'buy' as const : 'sell' as const, name: o.symbol.replace('USDT', ''), quantity: o.quantity, unit: o.symbol.replace('USDT', ''), filledAmount: o.filledAmount, createdAt: o.createdAt }));
        const fi: TradeItem[] = fo.map(o => { const s = orderSideView(o.orderSide); const b = o.symbol.replace('USDT', ''); return { id: `f-${o.orderId}`, orderSide: o.orderSide, sideLabel: s.label, sideTone: s.tone, name: `${b} 合约`, quantity: o.quantity, unit: b, filledAmount: o.filledAmount, createdAt: o.createdAt, isAi: o.isAiTrader === true }; });
        setLatestTrades([...ci, ...fi].sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()).slice(0, 20));
      }).finally(() => setTradesLoadedNonce(refreshNonce));
  }, [refreshNonce]);

  const isProfit = (user?.profit ?? 0) >= 0;
  // 曲线尾端接上实时值，让"最新一格"跟着盘面动
  const equityCurve = history.length
    ? [...history.map(h => h.totalAssets), ...(realtime ? [realtime.totalAssets] : [])]
    : [];
  const todayProfit = realtime?.dailyProfit ?? null;
  const todayUp = (todayProfit ?? 0) >= 0;
  // 切月时 monthCells 还是上个月的，先按当前月份筛一道，合计和格子才不会串月
  const monthRows = monthCells.filter(s => s.date.slice(0, 7) === gridMonth);
  const gridCells = monthRows.map(s => ({ date: s.date, pnl: s.dailyProfit }));
  const monthTotal = monthRows.reduce((sum, s) => sum + s.dailyProfit, 0);
  const selectedSnapshot = monthRows.find(s => s.date === selectedDate) ?? null;
  const dateStr = new Date().toLocaleDateString('zh-CN', { month: 'long', day: 'numeric', weekday: 'long' });

  return (
    <div className="page-shell px-4 md:px-6 py-4 space-y-4">

      {ready ? (
        <>
          {/* ====== 问候行 ====== */}
          <div className="flex items-center gap-3 flex-wrap">
            <DecryptedText
              text={`${greeting()}，${user!.username}`}
              className="text-lg font-extrabold tracking-tight"
            />
            <span className="text-xs text-muted-foreground">{dateStr}</span>
            <div className="ml-auto flex items-center gap-1.5">
              <Button variant="ghost" size="icon" className="w-7 h-7" onClick={() => navigate('/intro')}><Bell className="w-3.5 h-3.5 text-primary" /></Button>
              <Button variant="ghost" size="icon" className="w-7 h-7" onClick={() => { setRefreshNonce(n => n + 1); toast('已刷新', 'info'); }}><RefreshCcw className="w-3.5 h-3.5" /></Button>
            </div>
          </div>

          {/* ====== 驾驶舱主行：总资产曲线 + 今日盈亏/AI 画像 ====== */}
          <div className="grid lg:grid-cols-[1.7fr_1fr] gap-4 items-stretch">
            <SpotlightCard className="p-5 flex flex-col">
              <div className="flex items-center gap-2">
                <span className="microlabel font-semibold text-xs">总资产 · USD</span>
                <span className={cn(
                  'ml-auto num text-xs font-bold px-2.5 py-1 rounded-full',
                  isProfit ? 'bg-gain/10 text-gain' : 'bg-loss/10 text-loss',
                )}>
                  {isProfit ? '▲ +' : '▼ '}{user!.profitPct.toFixed(2)}%
                </span>
              </div>
              <div className="mt-4 flex items-baseline gap-1.5">
                <span className="num text-2xl text-muted-foreground">$</span>
                <NumberFlow
                  value={user!.totalAssets}
                  format={{ minimumFractionDigits: 2, maximumFractionDigits: 2 }}
                  className="num text-5xl sm:text-6xl font-bold tracking-tighter"
                />
              </div>
              {/* 两个次级指标拆成独立数据块：一行挤三段文字读起来费劲，也撑不住卡的高度 */}
              <div className="mt-5 pt-4 grid grid-cols-2 gap-4 border-t border-border/50">
                <div>
                  <div className="microlabel font-semibold">可用余额</div>
                  {/* 走 fmtNum 千分位不走 fmtMoney 的"万"缩写：滚动数字得逐位可比才有看头 */}
                  <div className="num text-xl font-bold mt-1">
                    <AnimNum value={user!.balance} prefix="$" fromZero />
                  </div>
                </div>
                <div>
                  <div className="microlabel font-semibold">总盈亏</div>
                  {/* 负号写在 $ 前面：拿 '$'+fmtNum 拼会得到 $-1750 那种货币符号在负号后面的怪东西 */}
                  <div className={cn('num text-xl font-bold mt-1', isProfit ? 'text-gain' : 'text-loss')}>
                    <AnimNum value={Math.abs(user!.profit)} prefix={isProfit ? '+$' : '-$'} fromZero />
                  </div>
                </div>
              </div>
              <div className="mt-5 flex-1 min-h-16 max-h-40">
                {equityCurve.length > 1 && (
                  <Sparkline
                    /* key 随数据变 → 刷新后 path 重建，描线动画重跑一遍 */
                    key={`${equityCurve.length}:${equityCurve[equityCurve.length - 1]}`}
                    data={equityCurve}
                    stroke={isProfit ? 'var(--color-gain)' : 'var(--color-loss)'}
                    dot={false}
                    className="w-full h-full"
                  />
                )}
              </div>
            </SpotlightCard>

            <div className="flex flex-col gap-4">
              <Card>
                <CardContent className="pt-4 pb-4">
                  <div className="microlabel font-semibold mb-1.5">今日盈亏</div>
                  {todayProfit != null ? (
                    <div className={cn('num text-xl font-bold', todayUp ? 'text-gain' : 'text-loss')}>
                      {todayUp ? '+' : ''}{fmtMoney(todayProfit)}
                      {realtime?.dailyProfitPct != null && (
                        <span className="text-xs ml-2 font-semibold">
                          {todayUp ? '+' : ''}{realtime.dailyProfitPct.toFixed(2)}%
                        </span>
                      )}
                    </div>
                  ) : (
                    <div className="text-sm text-muted-foreground">—</div>
                  )}
                </CardContent>
              </Card>

              {/* 月度盈亏网格：今天不在里头（快照只写到昨天），今天的数在上面那张卡 */}
              <Card className="flex-1">
                <CardContent className="pt-4 pb-4">
                  <div className="flex items-baseline justify-between mb-2">
                    <span className="microlabel font-semibold inline-flex items-center gap-1">
                      月度盈亏
                      <HelpTip
                        iconClassName="w-3 h-3"
                        text={'格子里的数来自每天 0 点的资产快照，含股票/币/合约/预测/游戏全部品类的浮盈浮亏。\n\n服务没运行的日子不会留下快照。日盈亏是拿当天快照减前一天快照算的，所以缺一天会让那天和它后一天都算不出来，格子一并空着。\n\n仅供参考，可能不全。当天的实时盈亏看上面的今日盈亏。'}
                      />
                    </span>
                    {/* 整月一格都没有时给"—"不给 +0.00——0 会被读成"这个月不赚不亏" */}
                    {monthRows.length === 0 ? (
                      <span className="num text-xs text-muted-foreground">—</span>
                    ) : (
                      <span className={cn('num text-xs font-bold', monthTotal >= 0 ? 'text-gain' : 'text-loss')}>
                        {monthTotal >= 0 ? '+' : ''}{fmtMoney(monthTotal)}
                      </span>
                    )}
                  </div>
                  <DailyGrid
                    cells={gridCells}
                    month={gridMonth}
                    onMonthChange={setGridMonth}
                    selectedDate={selectedDate ?? undefined}
                    onSelectDate={setSelectedDate}
                  />
                </CardContent>
              </Card>
            </div>
          </div>
        </>
      ) : (
        /* ====== 游客 Hero（fetchUser 未返回时也短暂走这支） ====== */
        <Card>
          <CardContent className="pt-5">
            <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-5">
              <div className="flex-1 space-y-3">
                <div className="inline-flex items-center gap-2 border border-primary/30 bg-primary/10 rounded-full px-3.5 py-1 text-xs font-bold text-primary">
                  <span className="w-1.5 h-1.5 rounded-full bg-primary animate-pulse" />
                  模拟交易平台
                </div>
                <h2 className="text-2xl sm:text-3xl font-extrabold tracking-tight leading-tight">
                  模拟交易,<br />
                  <span className="text-primary">随时随地!</span>
                </h2>
                <p className="text-sm text-muted-foreground">体验"如果当初买了会怎样"。股票、BTC、合约全覆盖。</p>
                <div className="flex flex-wrap gap-2 pt-1">
                  <Button size="sm" onClick={() => navigate('/bstock')}>
                    开始交易 <ArrowRight className="w-3.5 h-3.5" />
                  </Button>
                  <Button variant="outline" size="sm" onClick={() => navigate('/intro')}>玩法说明</Button>
                </div>
              </div>
              <div className="flex gap-6 md:gap-8 shrink-0">
                {[
                  { num: '10+', label: '股票' },
                  { num: '6', label: '币种' },
                  { num: '24/7', label: 'BTC行情' },
                ].map(s => (
                  <div key={s.label} className="text-center">
                    <div className="text-2xl font-extrabold num">{s.num}</div>
                    <div className="text-xs text-muted-foreground">{s.label}</div>
                  </div>
                ))}
              </div>
            </div>
          </CardContent>
        </Card>
      )}

      {/* ====== 快捷入口：压成一行小件（含每日福利，点开弹窗） ====== */}
      <div className="flex flex-wrap gap-2">
        {[
          { icon: List, label: '股票', to: '/bstock', ic: 'text-blue-600 dark:text-blue-400' },
          { icon: DollarSign, label: 'Crypto', to: '/coin', ic: 'text-amber-600 dark:text-amber-400' },
          { icon: Target, label: '预测', to: '/prediction', ic: 'text-primary' },
          { icon: Brain, label: 'AI', to: '/ai', ic: 'text-cyan-600 dark:text-cyan-400' },
          { icon: Gamepad2, label: '游戏', to: '/games', ic: 'text-pink-600 dark:text-pink-400' },
        ].map(({ icon: Icon, label, to, ic }) => (
          <button
            key={to}
            onClick={() => navigate(to)}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md border border-border bg-card hover:bg-surface-hover hover:border-foreground/20 text-xs font-semibold transition-colors cursor-pointer"
          >
            <Icon className={cn('w-3.5 h-3.5', ic)} />
            {label}
          </button>
        ))}
        {ready && (
          <button
            onClick={() => setBuffOpen(true)}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md border border-border bg-card hover:bg-surface-hover hover:border-foreground/20 text-xs font-semibold transition-colors cursor-pointer"
          >
            <Gift className="w-3.5 h-3.5 text-primary" />
            福利
            {/* 今日未抽 → 亮灯提醒 */}
            {buffStatus?.canDraw && <span className="led" />}
          </button>
        )}
      </div>

      {/* ====== 市场行情：三分类终端表，点分类头去市场页，点行直达交易页 ====== */}
      <HomeMarketSection />

      {/* ====== 成交 + 快讯 + 爆仓 + FAQ ====== */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-4 items-start">
        <LatestTradesCard trades={latestTrades} loading={tradesLoading} />
        {/* 实时快讯：BlockBeats 缓存（quant 侧），与最新成交并列 */}
        <NewsFlashCard />
        {/* 爆仓动态：轻量入口横幅，点击进 /force-orders 全量页 */}
        <div className="md:col-span-2">
          <ForceOrdersCard />
        </div>
        {/* 新手教学 FAQ */}
        <div className="md:col-span-2">
          <HomeFaq />
        </div>
      </div>

      {/* 网格点某一天的下钻：当日五分类拆解 + 当天已平的合约仓位 */}
      <DayDetailModal
        date={selectedDate}
        snapshot={selectedSnapshot}
        onClose={() => setSelectedDate(null)}
      />

      {/* 每日福利弹窗（快捷入口触发） */}
      {ready && (
        <DailyBuffModal
          status={buffStatus}
          open={buffOpen}
          onClose={() => setBuffOpen(false)}
          onDrawn={() => setRefreshNonce(n => n + 1)}
        />
      )}

    </div>
  );
}
