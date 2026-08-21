import { useState, useEffect } from 'react';
import type { ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation, Trans } from 'react-i18next';
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
  RefreshCcw, Bell, Gamepad2, List, DollarSign, ArrowRight, Target, Brain, Gift, Swords,
} from 'lucide-react';
import type { BuffStatus, AssetSnapshot } from '../types';
import { useUserStore } from '../stores/userStore';
import { cn, fmtDate, fmtMoney } from '../lib/utils';

const HIDE_NOTICE_KEY = 'wiib-notice-hide-date';
const NOTICE_SEEN_KEY = 'wiib-notice-seen';
// seen = 点过「我知道了」，永久不再自动跳；hide-date 只压当天，明天还提醒一次
function shouldShowNotice() {
  if (localStorage.getItem(NOTICE_SEEN_KEY)) return false;
  const d = localStorage.getItem(HIDE_NOTICE_KEY);
  return !d || d !== new Date().toDateString();
}

/** 快捷入口定义。登录后收在总资产看板底部，游客态在页面上单独一行，共用这一份 */
const QUICK_ENTRIES = [
  { icon: List, labelKey: 'quick.stocks', to: '/bstock', ic: 'text-blue-600 dark:text-blue-400' },
  { icon: DollarSign, labelKey: 'quick.crypto', to: '/coin', ic: 'text-amber-600 dark:text-amber-400' },
  { icon: Target, labelKey: 'quick.prediction', to: '/prediction', ic: 'text-primary' },
  { icon: Brain, labelKey: 'quick.ai', to: '/ai', ic: 'text-cyan-600 dark:text-cyan-400' },
  // 竞技场此前全站只有桌面顶栏一个入口，手机用户压根进不去，靠这颗 chip 补上
  { icon: Swords, labelKey: 'quick.arena', to: '/arena', ic: 'text-violet-600 dark:text-violet-400' },
  { icon: Gamepad2, labelKey: 'quick.games', to: '/games', ic: 'text-pink-600 dark:text-pink-400' },
];

function EntryChip({ onClick, children }: { onClick: () => void; children: ReactNode }) {
  return (
    <button
      onClick={onClick}
      className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-md border border-border bg-card hover:bg-surface-hover hover:border-foreground/20 text-xs font-semibold transition-colors cursor-pointer"
    >
      {children}
    </button>
  );
}

/** 按时段挑问候语，返回的是词表 key：这里直接查词表会把文案定死在模块加载那一刻 */
function greetingKey(): string {
  const h = new Date().getHours();
  if (h < 5) return 'greeting.lateNight';
  if (h < 11) return 'greeting.morning';
  if (h < 13) return 'greeting.noon';
  if (h < 18) return 'greeting.afternoon';
  return 'greeting.evening';
}

export function Home() {
  const navigate = useNavigate();
  const { t, i18n } = useTranslation('home');
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

  // 这里只装后端原始值（方向枚举、去 USDT 的符号），方向标签和展示名交给 LatestTradesCard 渲染期算：
  // 在拉数这一刻就把字烤进 state 的话，切语言后这批卡片还是老语言
  useEffect(() => {
    Promise.all([cryptoOrderApi.live().catch(() => []), futuresApi.live().catch(() => [])])
      .then(([co, fo]) => {
        const ci: TradeItem[] = co.map(o => ({ id: `c-${o.orderId}`, orderSide: o.orderSide, base: o.symbol.replace('USDT', ''), quantity: o.quantity, filledAmount: o.filledAmount, createdAt: o.createdAt }));
        const fi: TradeItem[] = fo.map(o => ({ id: `f-${o.orderId}`, orderSide: o.orderSide, base: o.symbol.replace('USDT', ''), isFutures: true, quantity: o.quantity, filledAmount: o.filledAmount, createdAt: o.createdAt, isAi: o.isAiTrader === true }));
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
  // 月份名/星期名跟着界面语言走，不能钉死 zh-CN——英文界面下会漏出"8月20日星期三"。
  // 取 resolvedLanguage：language 可能是没落在支持列表里的原始值（与 lib/utils.ts 同口径）
  const dateStr = new Date().toLocaleDateString(i18n.resolvedLanguage ?? i18n.language, { month: 'long', day: 'numeric', weekday: 'long' });

  return (
    <div className="page-shell px-4 md:px-6 py-4 space-y-4">

      {ready ? (
        <>
          {/* ====== 问候行 ====== */}
          <div className="flex items-center gap-3 flex-wrap">
            <DecryptedText
              text={t('greeting.withName', { greeting: t(greetingKey()), name: user!.username })}
              className="text-lg font-extrabold tracking-tight"
            />
            <span className="text-xs text-muted-foreground">{dateStr}</span>
            <div className="ml-auto flex items-center gap-1.5">
              <Button variant="ghost" size="icon" className="w-7 h-7" onClick={() => navigate('/intro')}><Bell className="w-3.5 h-3.5 text-primary" /></Button>
              <Button variant="ghost" size="icon" className="w-7 h-7" onClick={() => { setRefreshNonce(n => n + 1); toast(t('dashboard.refreshed'), 'info'); }}><RefreshCcw className="w-3.5 h-3.5" /></Button>
            </div>
          </div>

          {/* ====== 驾驶舱主行：总资产曲线 + 今日盈亏/AI 画像 ====== */}
          <div className="grid lg:grid-cols-[1.7fr_1fr] gap-4 items-stretch">
            <SpotlightCard className="p-5 flex flex-col">
              <div className="flex items-center gap-2">
                <span className="microlabel font-semibold text-xs">{t('dashboard.totalAssets')}</span>
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
                  <div className="microlabel font-semibold">{t('dashboard.availableBalance')}</div>
                  {/* 走 fmtNum 千分位不走 fmtMoney 的"万"缩写：滚动数字得逐位可比才有看头 */}
                  <div className="num text-xl font-bold mt-1">
                    <AnimNum value={user!.balance} prefix="$" fromZero />
                  </div>
                </div>
                <div>
                  <div className="microlabel font-semibold">{t('dashboard.totalPnl')}</div>
                  {/* 负号写在 $ 前面：拿 '$'+fmtNum 拼会得到 $-1750 那种货币符号在负号后面的怪东西 */}
                  <div className={cn('num text-xl font-bold mt-1', isProfit ? 'text-gain' : 'text-loss')}>
                    <AnimNum value={Math.abs(user!.profit)} prefix={isProfit ? '+$' : '-$'} fromZero />
                  </div>
                </div>
              </div>
              <div className="mt-5 flex-1 min-h-16 max-h-56">
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
              {/* 快捷入口收进看板底部：右列（月度网格）更高，items-stretch 把本卡拉高后
                  曲线封顶（max-h）剩下的就是一段死空白，拿它放入口正合适。mt-auto 钉在卡底 */}
              <div className="mt-auto pt-4 border-t border-border/50 flex flex-wrap gap-2">
                {QUICK_ENTRIES.map(({ icon: Icon, labelKey, to, ic }) => (
                  <EntryChip key={to} onClick={() => navigate(to)}>
                    <Icon className={cn('w-3.5 h-3.5', ic)} />
                    {t(labelKey)}
                  </EntryChip>
                ))}
                <EntryChip onClick={() => setBuffOpen(true)}>
                  <Gift className="w-3.5 h-3.5 text-primary" />
                  {t('quick.buff')}
                  {/* 今日未抽 → 亮灯提醒 */}
                  {buffStatus?.canDraw && <span className="led" />}
                </EntryChip>
              </div>
            </SpotlightCard>

            <div className="flex flex-col gap-4">
              <Card>
                <CardContent className="pt-4 pb-4">
                  <div className="microlabel font-semibold mb-1.5">{t('dashboard.todayPnl')}</div>
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
                      {t('dashboard.monthPnl')}
                      <HelpTip
                        iconClassName="w-3 h-3"
                        text={t('dashboard.monthPnlHelp')}
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
                  {t('guest.badge')}
                </div>
                {/* 换行位置和高亮哪一半交给词表：中英断句不在同一个词上，写死在 JSX 里必有一门语言难看 */}
                <h2 className="text-2xl sm:text-3xl font-extrabold tracking-tight leading-tight">
                  <Trans
                    ns="home"
                    i18nKey="guest.headline"
                    components={[<br key="br" />, <span key="hl" className="text-primary" />]}
                  />
                </h2>
                <p className="text-sm text-muted-foreground">{t('guest.tagline')}</p>
                <div className="flex flex-wrap gap-2 pt-1">
                  <Button size="sm" onClick={() => navigate('/bstock')}>
                    {t('guest.start')} <ArrowRight className="w-3.5 h-3.5" />
                  </Button>
                  <Button variant="outline" size="sm" onClick={() => navigate('/intro')}>{t('guest.howToPlay')}</Button>
                </div>
              </div>
              <div className="flex gap-6 md:gap-8 shrink-0">
                {[
                  { num: '10+', label: t('guest.statStocks') },
                  { num: '6', label: t('guest.statCoins') },
                  { num: '24/7', label: t('guest.statMarket') },
                ].map(s => (
                  <div key={s.num} className="text-center">
                    <div className="text-2xl font-extrabold num">{s.num}</div>
                    <div className="text-xs text-muted-foreground">{s.label}</div>
                  </div>
                ))}
              </div>
            </div>
          </CardContent>
        </Card>
      )}

      {/* ====== 快捷入口（游客态）：登录后这排收进上面的总资产看板里，不再单独占一行 ====== */}
      {!ready && (
        <div className="flex flex-wrap gap-2">
          {QUICK_ENTRIES.map(({ icon: Icon, labelKey, to, ic }) => (
            <EntryChip key={to} onClick={() => navigate(to)}>
              <Icon className={cn('w-3.5 h-3.5', ic)} />
              {t(labelKey)}
            </EntryChip>
          ))}
        </div>
      )}

      {/* ====== 市场行情：三分类终端表，点分类头去市场页，点行直达交易页 ====== */}
      <HomeMarketSection />

      {/* ====== 快讯 + 成交（主次分栏 2:1）+ 爆仓 + FAQ ====== */}
      {/* 按内容量分宽度：快讯标题+全文吃 2/3，成交一行十几个字 1/3 够用（金额细节在 /trades）。
          1:1 时代的毛病是左空右挤，快讯还得截成两行 */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4 items-start">
        {/* 实时快讯：BlockBeats 缓存（quant 侧） */}
        <div className="md:col-span-2">
          <NewsFlashCard />
        </div>
        <LatestTradesCard trades={latestTrades} loading={tradesLoading} />
        {/* 爆仓动态：轻量入口横幅，点击进 /force-orders 全量页 */}
        <div className="md:col-span-3">
          <ForceOrdersCard />
        </div>
        {/* 新手教学 FAQ */}
        <div className="md:col-span-3">
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
