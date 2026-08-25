import { useState, useEffect } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { rankingApi } from '../api';
import { Card, CardContent } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Skeleton } from '../components/ui/skeleton';
import { EmptyState } from '../components/EmptyState';
import { useUserStore } from '../stores/userStore';
import { cn, fmtNum } from '../lib/utils';
import { ChevronLeft, ChevronRight, Clock, Info, Trophy } from 'lucide-react';
import type { RankingItem, RankingSort } from '../types';

const PAGE_SIZE = 20;

/**
 * 列宽模板。表头与数据行共用一份，各写各的迟早错位（同 PositionHistoryList）。
 * 窄屏退化成带微标签的两列格子，表头整条隐藏。
 */
const GRID = 'grid grid-cols-2 gap-x-3 gap-y-2 md:gap-y-0 md:items-center md:grid-cols-[2.25rem_minmax(7rem,1.4fr)_minmax(0,1.1fr)_minmax(0,.85fr)_minmax(0,1.05fr)_minmax(0,1.1fr)_1rem]';

type Numeric = number | null | undefined;

const num = (v: Numeric) => Number.isFinite(v) ? v as number : 0;
const fmt = (v: Numeric) => fmtNum(num(v));  // 缺失值按 0.00 展示（榜单口径）

/** 窄屏紧凑数字：1.23M / 12.3K，避免小屏格子里大数字换行 */
const fmtCompact = (v: Numeric) => {
  const n = num(v);
  const abs = Math.abs(n);
  const sign = n < 0 ? '-' : '';
  if (abs >= 1e9) return sign + (abs / 1e9).toFixed(2) + 'B';
  if (abs >= 1e6) return sign + (abs / 1e6).toFixed(2) + 'M';
  if (abs >= 1e4) return sign + (abs / 1e3).toFixed(1) + 'K';
  return fmt(n);
};

/** 名次序号：等宽补零，前三名用主色。数字本身就是层级，不再叠奖牌 emoji */
function RankNum({ rank, className }: { rank: number; className?: string }) {
  return (
    <span className={cn('num tabular-nums', rank <= 3 ? 'text-primary' : 'text-muted-foreground', className)}>
      {String(rank).padStart(2, '0')}
    </span>
  );
}

/** 尺寸由调用方传（前三卡窄屏要缩），所以不做 size 枚举 */
function Avatar({ username, avatar, className }: { username: string; avatar?: string; className?: string }) {
  const base = 'rounded-md border border-border shrink-0';
  if (avatar) {
    return <img src={avatar} alt="" className={cn(base, 'object-cover', className)} />;
  }
  return (
    <div className={cn(base, 'bg-card-2 flex items-center justify-center font-bold', className)}>
      {username.charAt(0).toUpperCase()}
    </div>
  );
}

function Pct({ value, className }: { value: Numeric; className?: string }) {
  const v = num(value);
  const up = v >= 0;
  return (
    <span className={cn('num', up ? 'text-gain' : 'text-loss', className)}>
      {up ? '+' : ''}{v.toFixed(2)}%
    </span>
  );
}

function TradingProfit({ value, className }: { value: Numeric; className?: string }) {
  const v = num(value);
  const up = v >= 0;
  return (
    <span className={cn('num', up ? 'text-gain' : 'text-loss', className)}>
      {up ? '+' : ''}{fmt(v)}
    </span>
  );
}

/** 窄屏每格自带微标签（表头看不见了），宽屏交给表头 */
function Cell({ label, className, children }: { label: string; className?: string; children: React.ReactNode }) {
  return (
    <div className={cn('min-w-0', className)}>
      <div className="microlabel md:hidden mb-0.5">{label}</div>
      {children}
    </div>
  );
}

const PLACE_LABEL_KEY = ['ranking.place.first', 'ranking.place.second', 'ranking.place.third'];

const SORT_TABS: { key: RankingSort; labelKey: string; hintKey: string }[] = [
  { key: 'ASSETS', labelKey: 'ranking.metric.assets', hintKey: 'ranking.hint.assets' },
  { key: 'TRADING_PROFIT', labelKey: 'ranking.metric.profit', hintKey: 'ranking.hint.profit' },
];

const METRIC_LABEL_KEY: Record<RankingSort, string> = {
  ASSETS: 'ranking.metric.assets',
  TRADING_PROFIT: 'ranking.metric.profit',
};

const ALL_METRICS: RankingSort[] = ['ASSETS', 'TRADING_PROFIT'];

/** 按维度取值渲染。前三卡的主数字要跟着当前排序走，否则「按交易盈利排的榜、卡上最大的数是总资产」会看懵 */
function MetricValue({ metric, item, className }: { metric: RankingSort; item: RankingItem; className?: string }) {
  if (metric === 'TRADING_PROFIT') return <TradingProfit value={item.tradingProfit} className={className} />;
  return (
    <span className={cn('num', className)}>
      <span className="sm:hidden">{fmtCompact(item.totalAssets)}</span>
      <span className="hidden sm:inline">{fmt(item.totalAssets)}</span>
    </span>
  );
}

/**
 * 前三名重点卡。用「大号序号 + 顶部高光条」拉层级，不用奖牌 emoji 和渐变台阶——
 * 台阶那套是游戏皮，跟全站仪器风不是一个语言。三张等高并排，冠军多一条主色高光。
 */
function TopCard({ item, place, sort, onOpen }: {
  item: RankingItem; place: 0 | 1 | 2; sort: RankingSort; onOpen: () => void;
}) {
  const { t } = useTranslation('community');
  const champion = place === 0;
  return (
    <button
      type="button"
      onClick={onOpen}
      title={t('ranking.rowTitle', { name: item.username })}
      className={cn(
        'relative overflow-hidden rounded-lg pt-card text-left p-2.5 sm:p-3.5 transition-colors group',
        'hover:bg-accent/25 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
        champion && 'border-primary/40',
      )}
    >
      {/* 顶部 1px 高光：冠军主色，二三名素色。仪器面板的选中感 */}
      <div className={cn(
        'absolute top-0 inset-x-0 h-px bg-gradient-to-r from-transparent to-transparent',
        champion ? 'via-primary/60' : 'via-border',
      )} />

      {/* 窄屏三卡并排每张只有 ~110px，横排放不下"序号+头像+名字"，改成竖排居中 */}
      <div className="flex flex-col items-center text-center gap-1.5 sm:flex-row sm:items-center sm:text-left sm:gap-2.5">
        <RankNum
          rank={item.rank}
          className={cn('leading-none font-black', champion ? 'text-xl sm:text-2xl' : 'text-lg sm:text-xl opacity-70')}
        />
        <Avatar
          username={item.username}
          avatar={item.avatar}
          className="w-9 h-9 text-sm sm:w-11 sm:h-11 sm:text-base"
        />
        <div className="min-w-0 w-full sm:flex-1">
          <div className="text-[12px] sm:text-[13px] font-bold truncate group-hover:text-primary transition-colors">
            {item.username}
          </div>
          <div className="microlabel">{t(PLACE_LABEL_KEY[place])}</div>
        </div>
      </div>

      <div className="mt-2.5 sm:mt-3 pt-2.5 sm:pt-3 border-t border-border/40 space-y-1.5">
        {/* 主数字 = 当前排序维度，其余维度降到下面的明细行 */}
        <div className="text-center sm:text-left">
          <div className="microlabel">{t(METRIC_LABEL_KEY[sort])}</div>
          <MetricValue metric={sort} item={item} className="block text-[15px] sm:text-lg font-bold leading-tight truncate" />
        </div>
        <div className="flex items-center justify-center sm:justify-between gap-2">
          <span className="microlabel hidden sm:inline">{t('ranking.metric.return')}</span>
          <Pct value={item.profitPct} className="text-[12px] font-semibold" />
        </div>

        {/* 窄屏收起下面几行：110px 宽塞五行标签值必挤成一团，这些数点进详情页都有 */}
        <div className="hidden sm:block space-y-1.5">
          {ALL_METRICS.filter(m => m !== sort).map(m => (
            <div key={m} className="flex items-center justify-between gap-2">
              <span className="microlabel">{t(METRIC_LABEL_KEY[m])}</span>
              <MetricValue metric={m} item={item} className="text-[12px] font-semibold" />
            </div>
          ))}
          <div className="flex items-center justify-between gap-2 pt-1 border-t border-border/25">
            <span className="microlabel">{t('ranking.metric.wallet')}</span>
            <span className="num text-[10px] text-muted-foreground truncate">
              {fmtCompact(item.balanceWallet)} / {fmtCompact(item.gameWallet)}
            </span>
          </div>
        </div>
      </div>
    </button>
  );
}

/** 「我」徽标。名次列宽只够放两位数字，塞不进去，所以跟在用户名后面 */
function MeBadge() {
  const { t } = useTranslation('community');
  return (
    <span className="microlabel text-primary shrink-0 px-1 py-px rounded border border-primary/40 leading-none">
      {t('ranking.me.badge')}
    </span>
  );
}

function RankRow({ item, sort, me, onOpen }: {
  item: RankingItem; sort: RankingSort; me?: boolean; onOpen: () => void;
}) {
  const { t } = useTranslation('community');
  return (
    <button
      type="button"
      onClick={onOpen}
      title={t('ranking.rowTitle', { name: item.username })}
      className={cn(GRID, 'w-full text-left px-3 sm:px-4 py-2.5 border-b border-border/25 last:border-b-0',
        'hover:bg-accent/25 transition-colors group focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset',
        // 自己那条：左侧主色竖条 + 极淡主色底，跟表格行同构但一眼能挑出来
        me && 'relative bg-primary/5 hover:bg-primary/10 before:absolute before:inset-y-0 before:left-0 before:w-0.5 before:bg-primary')}
    >
      {/* 名次 + 用户：窄屏并成一行占满，箭头也跟着挪到这行尾（宽屏那个在表格最后一列） */}
      <div className="col-span-2 md:col-span-1 flex items-center gap-2 md:gap-0">
        <RankNum rank={item.rank} className={cn('text-[13px] font-bold', me && 'text-primary')} />
        <div className="flex items-center gap-2 md:hidden min-w-0 flex-1">
          <Avatar username={item.username} avatar={item.avatar} className="w-7 h-7 text-[11px]" />
          <span className="text-[13px] font-semibold truncate">{item.username}</span>
          {me && <MeBadge />}
          <ChevronRight className="w-3.5 h-3.5 text-muted-foreground/35 ml-auto shrink-0" />
        </div>
      </div>
      <div className="hidden md:flex items-center gap-2 min-w-0">
        <Avatar username={item.username} avatar={item.avatar} className="w-7 h-7 text-[11px]" />
        <span className="text-[13px] font-semibold truncate group-hover:text-primary transition-colors">
          {item.username}
        </span>
        {me && <MeBadge />}
      </div>

      <Cell label={t('ranking.metric.assets')} className={cn('md:text-right', sort === 'ASSETS' && 'text-primary')}>
        <span className="num text-[13px] font-bold">
          <span className="md:hidden">{fmtCompact(item.totalAssets)}</span>
          <span className="hidden md:inline">{fmt(item.totalAssets)}</span>
        </span>
      </Cell>

      <Cell label={t('ranking.metric.return')} className="md:text-right">
        <Pct value={item.profitPct} className="text-[12px] font-semibold" />
      </Cell>

      <Cell label={t('ranking.metric.profit')} className={cn('md:text-right', sort === 'TRADING_PROFIT' && 'text-primary')}>
        <TradingProfit value={item.tradingProfit} className="text-[12px] font-semibold" />
      </Cell>

      <Cell label={t('ranking.metric.wallet')} className="md:text-right">
        <span className="num text-[11px] text-muted-foreground truncate">
          {fmtCompact(item.balanceWallet)} / {fmtCompact(item.gameWallet)}
        </span>
      </Cell>

      <div className="hidden md:flex justify-end">
        <ChevronRight className="w-3.5 h-3.5 text-muted-foreground/35 group-hover:text-primary/60 transition-colors" />
      </div>
    </button>
  );
}

export function Ranking() {
  const { t } = useTranslation('community');
  const navigate = useNavigate();
  const myUserId = useUserStore(s => s.user?.id);
  const [ranking, setRanking] = useState<RankingItem[]>([]);
  // 三态：undefined=这块整条不显示（未登录/请求失败），null=已登录但没上榜（显示提示条），有值=显示横条
  const [myRow, setMyRow] = useState<RankingItem | null | undefined>(undefined);
  const [sort, setSort] = useState<RankingSort>('ASSETS');
  const [page, setPage] = useState(1);
  const [pages, setPages] = useState(0);
  const [total, setTotal] = useState(0);
  // loading 由"已加载 key 是否追上请求 key"派生：在 effect 里同步 setLoading(true)
  // 会触发级联渲染，eslint 的 react-hooks/set-state-in-effect 直接判错（同 ForceOrders）
  const requestKey = `${sort}:${page}`;
  const [loadedKey, setLoadedKey] = useState<string | null>(null);
  const loading = loadedKey !== requestKey;

  // 自己那条只在第 1 页出现（前三卡也只在第 1 页），未登录不要——那条接口未登录是 401
  const showMine = page === 1 && myUserId != null;

  useEffect(() => {
    let cancelled = false;
    const mine = showMine
      ? rankingApi.me(sort).catch(() => undefined)   // 拉不到就整条不显示，别拿"未上榜"糊弄
      : Promise.resolve(undefined);
    Promise.all([rankingApi.list(sort, page, PAGE_SIZE), mine])
      .then(([res, row]) => {
        if (cancelled) return;
        setRanking(res.records);
        setPages(res.pages);
        setTotal(res.total);
        setMyRow(row);
      })
      .catch(() => { if (!cancelled) { setRanking([]); setMyRow(undefined); } })
      .finally(() => { if (!cancelled) setLoadedKey(requestKey); });
    return () => { cancelled = true; };
  }, [requestKey, sort, page, showMine]);

  const openUser = (userId: number) => navigate(`/user/${userId}`);

  // 换维度必须回到第 1 页：停在第 3 页换榜，看到的是新榜的第 41 名开始，
  // 而前三名重点卡只在第 1 页出现，换完一片空
  const switchSort = (next: RankingSort) => {
    if (next === sort) return;
    setSort(next);
    setPage(1);
  };

  // 前三名重点卡只在第 1 页出现：第 2 页往后的"前三个"是这一页的前三个，不是全榜前三
  const showTop = page === 1;
  const top = showTop ? ranking.slice(0, 3) : [];
  const rest = showTop ? ranking.slice(3) : ranking;

  return (
    <div className="page-shell p-4 md:p-6 space-y-4">
      {/* 页头 */}
      <Card>
        <CardContent className="py-4">
          <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
            <h1 className="flex items-center gap-2.5 text-lg font-black tracking-tight">
              <span className="p-1.5 rounded-xl bg-primary/10 text-primary">
                <Trophy className="w-4 h-4" />
              </span>
              {t('ranking.title')}
            </h1>
            <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-[11px] text-muted-foreground">
              <span className="flex items-center gap-1.5">
                <span className="led" />
                <Clock className="w-3 h-3" />
                {t('ranking.updateHint')}
              </span>
              <span>
                <Trans
                  ns="community"
                  i18nKey="ranking.totalUsers"
                  values={{ total }}
                  components={[<span key="total" className="num text-foreground" />]}
                />
              </span>
              <span>{t('ranking.rowHint')}</span>
            </div>
          </div>

          {/* 排序维度：换维度名次跟着重算，01 就是该维度第一 */}
          <div className="mt-3.5 flex items-center gap-1 p-1 rounded-lg bg-card-2 border border-border/50">
            {SORT_TABS.map(tab => (
              <button
                key={tab.key}
                type="button"
                onClick={() => switchSort(tab.key)}
                title={t(tab.hintKey)}
                className={cn(
                  'flex-1 py-1.5 px-2 rounded-md text-xs font-medium transition-colors whitespace-nowrap',
                  sort === tab.key
                    ? 'bg-card text-foreground border border-border shadow-[inset_0_2px_0_var(--color-primary)]'
                    : 'text-muted-foreground hover:text-foreground hover:bg-surface-hover',
                )}
              >
                {t(tab.labelKey)}
              </button>
            ))}
          </div>
        </CardContent>
      </Card>

      {loading ? (
        <>
          {showMine && <Skeleton className="h-14 w-full rounded-lg" />}
          <div className="grid grid-cols-3 gap-2 sm:gap-3">
            {[...Array(3)].map((_, i) => <Skeleton key={i} className="h-40 sm:h-52 w-full rounded-lg" />)}
          </div>
          <Card><CardContent className="p-4 space-y-2.5">
            {[...Array(8)].map((_, i) => <Skeleton key={i} className="h-11 w-full rounded-lg" />)}
          </CardContent></Card>
        </>
      ) : ranking.length === 0 ? (
        <Card><CardContent className="p-0"><EmptyState icon={<Trophy />} text={t('ranking.empty')} /></CardContent></Card>
      ) : (
        <>
          {/* 自己那条提到最上面。下面的名次表照旧也有这个人，这里是提出来的一份，不是搬走 */}
          {showMine && myRow !== undefined && (
            <Card className="overflow-hidden border-primary/40">
              <CardContent className="p-0">
                {myRow ? (
                  <RankRow item={myRow} sort={sort} me onOpen={() => openUser(myRow.userId)} />
                ) : (
                  <div className="flex items-center gap-2 px-3 sm:px-4 py-3 text-[12px] text-muted-foreground">
                    <Info className="w-3.5 h-3.5 shrink-0" />
                    {t('ranking.me.notRanked')}
                  </div>
                )}
              </CardContent>
            </Card>
          )}

          {/* 前三名：并排等高，不做台阶。名次靠序号和高光条区分 */}
          {top.length > 0 && (
            <div className="grid grid-cols-3 gap-2 sm:gap-3">
              {top.map((item, i) => (
                <TopCard key={item.userId} item={item} place={i as 0 | 1 | 2} sort={sort} onOpen={() => openUser(item.userId)} />
              ))}
            </div>
          )}

          {rest.length > 0 && (
            <Card className="overflow-hidden">
              <CardContent className="p-0">
                {/* 表头只在宽屏出现，窄屏每格自带微标签 */}
                <div className={cn(GRID, 'hidden md:grid px-4 py-2 bg-card-2 border-b border-border/30')}>
                  <span className="microlabel font-bold">#</span>
                  <span className="microlabel font-bold">{t('ranking.metric.user')}</span>
                  <span className={cn('microlabel font-bold text-right', sort === 'ASSETS' && 'text-primary')}>
                    {t('ranking.metric.assets')}
                  </span>
                  <span className="microlabel font-bold text-right">{t('ranking.metric.return')}</span>
                  <span className={cn('microlabel font-bold text-right', sort === 'TRADING_PROFIT' && 'text-primary')}
                    title={t('ranking.hint.profitCol')}>{t('ranking.metric.profit')}</span>
                  <span className="microlabel font-bold text-right" title={t('ranking.hint.walletCol')}>
                    {t('ranking.metric.wallet')}
                  </span>
                  <span />
                </div>

                {rest.map(item => (
                  <RankRow key={item.userId} item={item} sort={sort} onOpen={() => openUser(item.userId)} />
                ))}
              </CardContent>
            </Card>
          )}

          {pages > 1 && (
            <div className="flex items-center justify-between px-1">
              <span className="text-xs text-muted-foreground">
                <Trans
                  ns="community"
                  i18nKey="ranking.page"
                  values={{ page, pages }}
                  components={[<span key="page" className="num" />, <span key="pages" className="num" />]}
                />
              </span>
              <div className="flex items-center gap-1">
                <Button variant="ghost" size="sm" className="h-8 w-8 p-0"
                  disabled={page <= 1} onClick={() => setPage(p => p - 1)}>
                  <ChevronLeft className="w-4 h-4" />
                </Button>
                <Button variant="ghost" size="sm" className="h-8 w-8 p-0"
                  disabled={page >= pages} onClick={() => setPage(p => p + 1)}>
                  <ChevronRight className="w-4 h-4" />
                </Button>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}
