import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { campaignApi } from '../api';
import { buildAuthorizeUrl, CLAIM_STATE_PREFIX, OAUTH_STATE_KEY } from './Login';
import { useUserStore } from '../stores/userStore';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Badge } from '../components/ui/badge';
import { Skeleton } from '../components/ui/skeleton';
import { EmptyState } from '../components/EmptyState';
import { useToast } from '../components/ui/use-toast';
import { cn, fmtDateTime, fmtNum } from '../lib/utils';
import {
  Gift, CalendarCheck, Trophy, TrendingUp, TrendingDown, Check, Loader2, TriangleAlert, RefreshCw,
} from 'lucide-react';
import type {
  CampaignInfo, CampaignReward, CampaignScore, CampaignScoreItem, MyCampaignView,
} from '../types';

type TaskGroup = '交易' | '日常' | '投票' | '罚分';

/** 分组渲染顺序。罚分固定垫底 —— 谁也不想一进来先看见自己被扣了多少 */
const GROUP_ORDER: TaskGroup[] = ['交易', '日常', '投票', '罚分'];

interface TaskDef {
  /** 与后端 ScoreItem.code 对齐。后端只下发"已达成"的条目，未达成的靠这份清单占位 */
  code: string;
  label: string;
  hint: string;
  group: TaskGroup;
}

/**
 * 任务全集。后端 items 里只有已达成的条目，光渲染它的话新人进来是一片空白，
 * 「再做一个任务能多拿多少」这件事就无从谈起 —— 阶梯的推力全靠这份清单撑着。
 * 分值文案出自设计文档 §2（与 ScoreRules 同源），改规则时两边一起改。
 */
const TASKS: TaskDef[] = [
  { code: 'ROI50', label: '单仓位 ROI ≥ 50%', hint: '保证金 ≥ 500；前 5 笔各 +5，之后各 +1', group: '交易' },
  { code: 'ROI100', label: '单仓位 ROI ≥ 100%', hint: '保证金 ≥ 500；首笔 +15，第 2-3 笔各 +5，之后各 +1', group: '交易' },
  { code: 'GODLY', label: '单笔封神：ROI ≥ 300%', hint: '保证金 ≥ 500；一次性 +20', group: '交易' },
  { code: 'SPOT', label: '现货标的整体收益 ≥ 10%', hint: '活动期该标的累计买入 ≥ 1000；前 3 个各 +5，之后各 +1', group: '交易' },
  { code: 'PREDICTION', label: '预测市场持有到结算且猜中', hint: '单次额度 ≥ 50；每次 +5', group: '交易' },
  { code: 'TRIPLE', label: '三市通吃', hint: '加密合约 / 黄金原油 / 美股永续 各拿下一笔 ROI ≥ 50%；一次性 +15', group: '交易' },
  { code: 'STOP_LOSS_HERO', label: '止损英雄', hint: '挂过止损并被触发；一次性 +3', group: '交易' },
  { code: 'CHECKIN', label: '每日签到', hint: '每天 +1', group: '日常' },
  { code: 'STREAK', label: '连续签到 3 / 7 / 14 天', hint: '+5 / +15 / +40 累进，断签重计', group: '日常' },
  { code: 'FIRST_COMMENT', label: '首次评论', hint: '一次性 +1', group: '日常' },
  { code: 'VOTE', label: '每日多空投票', hint: '每天 100 分池按当日正确票数均分，单人单日封顶 6 分', group: '投票' },
  { code: 'LIQ_ISOLATED', label: '逐仓强平', hint: '每次 −5', group: '罚分' },
  { code: 'LIQ_CROSS', label: '全仓爆仓', hint: '每次 −30', group: '罚分' },
];

/** 整数不显示小数：任务分大多是整数，"+5.00" 读起来像金额 */
function fmtScore(n: number): string {
  return Number.isInteger(n) ? String(n) : fmtNum(n);
}

/** 进度文案。签到那两条的 count 是天数，写成 "×8" 会被读成签了 8 次不同的到 */
function progressText(code: string, count: number): string {
  if (code === 'CHECKIN') return `已签 ${count} 天`;
  if (code === 'STREAK') return `最长连续 ${count} 天`;
  return `×${count}`;
}

function TaskRow({ def, item }: { def: TaskDef; item: CampaignScoreItem | null }) {
  const done = item != null;
  const negative = (item?.score ?? 0) < 0;
  return (
    <div className="flex items-center gap-3 px-4 py-2.5 border-b border-border/25 last:border-b-0">
      <span
        className={cn(
          'w-4 h-4 shrink-0 rounded-full border flex items-center justify-center',
          done
            ? negative ? 'border-loss/50 bg-loss/12 text-loss' : 'border-gain/50 bg-gain/12 text-gain'
            : 'border-border',
        )}
      >
        {done && <Check className="w-2.5 h-2.5" />}
      </span>

      <div className="min-w-0 flex-1">
        <div className={cn('text-[13px] font-semibold truncate', !done && 'text-muted-foreground')}>
          {/* 有后端条目就用它的 label：code 才是稳定标识，文案以后端为准 */}
          {item?.label ?? def.label}
        </div>
        <div className="text-[10px] text-muted-foreground truncate">{def.hint}</div>
      </div>

      {item != null && item.count > 0 && (
        <span className="num text-[11px] text-muted-foreground shrink-0 tabular-nums">
          {progressText(def.code, item.count)}
        </span>
      )}

      <span
        className={cn(
          'num text-[13px] font-bold tabular-nums w-14 text-right shrink-0',
          !done ? 'text-muted-foreground/50' : negative ? 'text-loss' : 'text-gain',
        )}
      >
        {done ? `${item.score > 0 ? '+' : ''}${fmtScore(item.score)}` : '—'}
      </span>
    </div>
  );
}

/**
 * 票盖的是<b>明天</b>的 UTC 日戳（后端 CampaignVoteService.votingDate 就是这么算的）。
 * 投当天没得玩：结算比的是当日收盘 vs 前日收盘，而那根 K 线在本站自己的图上就看得见，
 * 临收盘才投等于照着答案填。投明天则收票在这一天开始之前就截止了，谁都没有前瞻。
 * 这里独立算一遍只为把目标日显示出来 —— 不显示的话"投的是明天"这件事在界面上完全看不出来。
 */
function tomorrowUtc(now: Date): string {
  return new Date(now.getTime() + 86400_000).toISOString().slice(0, 10);
}

/**
 * 结算锁盘窗口 UTC [23:55, 00:05)：日线在切、00:05 那次投票结算在往库里写结果，
 * 而且这 10 分钟正好横跨"票落在哪一天"的翻页点。
 * 说了算的是后端那道闸（CampaignVoteService.requireNotLocked），这里只为提前把按钮压灰 ——
 * 让人点下去才被顶回来，比灰着并写明原因难受得多。
 */
function inSettleLock(now: Date): boolean {
  const minutes = now.getUTCHours() * 60 + now.getUTCMinutes();
  return minutes >= 23 * 60 + 55 || minutes < 5;
}

function VoteSide({ dir, count, mine, disabled, onClick }: {
  dir: 'UP' | 'DOWN';
  count: number;
  mine: 'UP' | 'DOWN' | null;
  disabled: boolean;
  onClick: () => void;
}) {
  const up = dir === 'UP';
  const picked = mine === dir;
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className={cn(
        'flex flex-col items-center gap-0.5 rounded-md border py-2.5 transition-colors',
        picked
          ? up ? 'border-gain/50 bg-gain/12 text-gain' : 'border-loss/50 bg-loss/12 text-loss'
          : 'border-border text-muted-foreground',
        // 已投过的另一侧压暗，跟"还没投但现在不能投"区分开
        !picked && mine !== null && 'opacity-40',
        !disabled && !picked && (up
          ? 'cursor-pointer hover:border-gain/50 hover:text-gain'
          : 'cursor-pointer hover:border-loss/50 hover:text-loss'),
        disabled && 'cursor-not-allowed',
      )}
    >
      <span className="text-[13px] font-bold flex items-center gap-1">
        {up ? <TrendingUp className="w-3.5 h-3.5" /> : <TrendingDown className="w-3.5 h-3.5" />}
        {up ? '看涨' : '看跌'}
      </span>
      <span className="num text-[11px] tabular-nums">{count} 票</span>
    </button>
  );
}

/** 一维分数块（交易/日常/投票/罚分）。罚分是 0 时不上红，没被扣过不该看着像被扣了 */
function ScoreCell({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <div className="microlabel font-semibold">{label}</div>
      <div className={cn('num text-lg font-bold mt-0.5 tabular-nums', value < 0 && 'text-loss')}>
        {fmtScore(value)}
      </div>
    </div>
  );
}

/**
 * 把一次请求收成 [值, 是否失败]。
 * <p>拉失败与"确实是空"必须分开：合成同一个 null 的话，一次 500 就变成
 * 「当前没有进行中的活动」——活动进行中后端抖一下，页面就对着用户否认活动的存在，
 * 而这是一个真发钱的页面。
 */
function tryLoad<T>(p: Promise<T>, fallback: T): Promise<[T, boolean]> {
  return p.then(v => [v, false] as [T, boolean]).catch(() => [fallback, true]);
}

export function Campaign() {
  const { toast } = useToast();
  const { user } = useUserStore();

  const [view, setView] = useState<MyCampaignView | null>(null);
  const [info, setInfo] = useState<CampaignInfo | null>(null);
  const [reward, setReward] = useState<CampaignReward | null>(null);
  const [board, setBoard] = useState<CampaignScore[]>([]);
  // 四个"没拉到"标记，一个都不省。/current 的这个曾经被我判成"不需要"，理由是它只喂
  // 领取卡那句永远为真的兜底话 —— 那句理由是错的：settled 同时还喂着 canAct、状态徽标
  // 和预估卡。首屏就把 /current 拉挂、而活动其实已结算的话，徽标会写成"已结束 · 待结算"，
  // 预估卡会退回"预估到手"。都不是关于钱的假话（领取仍走 reward 那支，照常能领），
  // 但确实是错的状态，所以宁可挂一条"没拉全"的提示，也别假装页面是准的
  const [viewFailed, setViewFailed] = useState(false);
  const [currentFailed, setCurrentFailed] = useState(false);
  const [rewardFailed, setRewardFailed] = useState(false);
  const [boardFailed, setBoardFailed] = useState(false);

  const [nonce, setNonce] = useState(0);
  // loading 由"已加载 nonce 是否追上请求 nonce"派生：在 effect 里同步 setLoading(true)
  // 会被 eslint 的 react-hooks/set-state-in-effect 判错（同 Ledger / ForceOrders）
  const [loadedNonce, setLoadedNonce] = useState(-1);
  const loading = loadedNonce !== nonce;

  // 签到/投票 与 领取跳转 分两个忙标记：共用一个的话点了签到，领取按钮会跟着写"跳转授权中"
  const [acting, setActing] = useState(false);
  const [redirecting, setRedirecting] = useState(false);
  const reload = useCallback(() => setNonce(n => n + 1), []);

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      tryLoad(campaignApi.me(), null as MyCampaignView | null),
      tryLoad(campaignApi.current(), null as CampaignInfo | null),
      tryLoad(campaignApi.reward(), null as CampaignReward | null),
      tryLoad(campaignApi.board(), [] as CampaignScore[]),
    ]).then(([[me, meErr], [cur, curErr], [rw, rwErr], [bd, bdErr]]) => {
      if (cancelled) return;
      // 只在成功时覆盖：签到/投票之后走的是同一条刷新链路，网络抖一下不该把
      // 已经看到的内容清成空白，更不该把"有活动"翻成"没活动"
      if (!meErr) setView(me);
      if (!curErr) setInfo(cur);
      if (!rwErr) setReward(rw);
      if (!bdErr) setBoard(bd);
      setViewFailed(meErr);
      setCurrentFailed(curErr);
      setRewardFailed(rwErr);
      setBoardFailed(bdErr);
      setLoadedNonce(nonce);
    });
    return () => { cancelled = true; };
  }, [nonce]);

  // 锁盘状态得自己走一步：页面别处的时间都是每次渲染现取的，不刷新就不动 ——
  // 别处不动只是显示旧了，这里不动却会把按钮一直灰着，人就投不成票了。30 秒一跳，
  // 两端最多差半分钟：起点那半分钟点下去后端会顶回来（有 toast），终点那半分钟等一下就好
  const [voteLocked, setVoteLocked] = useState(() => inSettleLock(new Date()));
  // 上一跳的锁盘状态。用 ref 不用 state：只为认出"解锁那一跳"，进 effect 依赖会把 30 秒的计时器重置掉
  const lockedRef = useRef(voteLocked);
  useEffect(() => {
    const timer = setInterval(() => {
      const locked = inSettleLock(new Date());
      // true→false 这一跳只可能发生在 UTC 00:05：中间刚跨过 0 点，voteDay 已经翻页，
      // 可票数和"我投了没"还是上一天那份 —— 不重拉的话，昨天投过的人会看着新日期被告知
      // "UTC xx-xx 的看涨已投"，而那天他一票没投。只在这一跳拉，不做轮询
      if (lockedRef.current && !locked) reload();
      lockedRef.current = locked;
      setVoteLocked(locked);
    }, 30_000);
    return () => clearInterval(timer);
  }, [reload]);

  const handleCheckin = async () => {
    setActing(true);
    try {
      const streak = await campaignApi.checkin();
      toast(`签到成功，最长连续 ${streak} 天`, 'success');
      reload();
    } catch (e) {
      toast((e as Error).message || '签到失败', 'error');
    } finally {
      setActing(false);
    }
  };

  const handleVote = async (symbol: string, direction: 'UP' | 'DOWN') => {
    setActing(true);
    try {
      await campaignApi.vote(symbol, direction);
      toast('投票成功', 'success');
      reload();
    } catch (e) {
      toast((e as Error).message || '投票失败', 'error');
    } finally {
      setActing(false);
    }
  };

  /** 领取要走一次 LinuxDo 授权：平台存的 username 可能是旧的，分发接口拿它做二次校验会失败 */
  const startClaim = () => {
    setRedirecting(true);
    const state = CLAIM_STATE_PREFIX + Math.random().toString(36).slice(2);
    localStorage.setItem(OAUTH_STATE_KEY, state);
    window.location.href = buildAuthorizeUrl(state);
  };

  // 任务清单：按 code 把后端条目并进任务全集；后端出了清单外的 code 也照样渲染，
  // 负分的一律归到罚分组（将来日常侧加扣分规则时不用改这里）
  const grouped = useMemo(() => {
    const items = view?.me.items ?? [];
    const byCode = new Map(items.map(i => [i.code, i]));
    const known = new Set(TASKS.map(t => t.code));
    const rows: { def: TaskDef; item: CampaignScoreItem | null }[] = [
      ...TASKS.map(def => ({ def, item: byCode.get(def.code) ?? null })),
      ...items.filter(i => !known.has(i.code)).map(i => ({
        def: { code: i.code, label: i.label, hint: '', group: (i.score < 0 ? '罚分' : '交易') as TaskGroup },
        item: i,
      })),
    ];
    return GROUP_ORDER.map(g => ({
      group: g,
      rows: rows.filter(r => ((r.item?.score ?? 0) < 0 ? '罚分' : r.def.group) === g),
    })).filter(g => g.rows.length > 0);
  }, [view]);

  const now = Date.now();
  /**
   * 这一票管的是哪天。跨 UTC 0 点那次翻页由上面锁盘计时器的"解锁那一跳"带着 reload 一起走，
   * 所以页面挂一整晚也不会出现"日期是新的、票况是旧的"
   */
  const voteDay = tomorrowUtc(new Date(now));
  const startMs = view ? new Date(view.startAt).getTime() : 0;
  const endMs = view ? new Date(view.endAt).getTime() : 0;
  const notStarted = view != null && now < startMs;
  const ended = view != null && now >= endMs;
  // 结算与否只有 status 认得：/reward 为 null 时，"还没结算"和"结算了但你一分没分到"长得一模一样
  const settled = info?.status === 'SETTLING';
  // 只有明确 SETTLING 才关签到/投票的门。/current 万一没拉到（info 为 null）就照常放行，
  // 真不该点后端会拿准确的时间窗顶回来，总好过一次网络抖动把活动页锁死
  const canAct = view != null && !notStarted && !ended && !settled;

  /**
   * "有没有分到钱这件事，现在说不准"。预估卡与领取卡共用这一个判断。
   * <p>【为什么必须共用】上一轮就是两张卡各判各的翻的车：领取卡挡住了 rewardFailed，
   * 预估卡只看 reward 与 settled，于是 /reward 单独 500 时，一个真分到钱的人会在
   * "奖励信息加载失败"正上方读到"实际到手 0.00 · 本次没有分到 LDC"。
   * 两张卡的分支顺序从此都是 reward → rewardUnknown → settled → 兜底，一致性是构造出来的。
   * <p>【为什么带 settled】结算前 /reward 本来就返回 null，它失败没有信息量，
   * 没必要在活动进行中因为一次抖动就把预估数字换成一个"说不准"。
   */
  const rewardUnknown = rewardFailed && !reward && settled;

  const statusBadge = notStarted
    ? { text: '未开始', variant: 'warning' as const }
    : settled
      ? { text: '已结算 · 可领取', variant: 'success' as const }
      : ended
        ? { text: '已结束 · 待结算', variant: 'secondary' as const }
        : { text: '进行中', variant: 'default' as const };

  const daysLeft = view
    ? Math.max(0, Math.ceil(((notStarted ? startMs : endMs) - now) / 86400_000))
    : 0;

  return (
    <div className="page-shell p-4 md:p-6 space-y-4">
      {/* ① 顶部提示：无条件显示。参与名单按"linux_do_id 必须是纯数字"筛，邀请码注册的账号
             根本不进名单 —— 不算分、不上榜、不分配，而系统没有"本地账号绑定 LinuxDo"的路径。
             他们玩两周才发现自己压根没参与，是最容易招骂的点，所以要提前讲明白。
             不要写成 {!view.me.claimable && ...}：名单已经筛过，claimable 恒 true，
             而没上榜的人拿到的是全零兜底对象，那上面也是 true —— 条件永不触发 */}
      <div className="pt-card rounded-lg p-3 flex items-start gap-2.5 text-xs">
        <TriangleAlert className="w-4 h-4 shrink-0 text-warning mt-px" />
        <div className="leading-relaxed">
          <span className="font-bold text-warning">本活动仅限 LinuxDo 登录用户参与。</span>
          <span className="text-muted-foreground ml-1">
            邀请码注册的账号不计积分、不上榜、不参与 LDC 分配（LDC 只能发到 LinuxDo 账号上，
            平台也没有"本地账号绑定 LinuxDo"的入口）。
          </span>
        </div>
      </div>

      {/* 骨架只在首屏出（loading && !view）。签到/投票之后走的是同一条刷新链路，
          在这儿只判 loading 的话，每天必点一次的签到会把整页连同滚动位置掀掉一遍 */}
      {loading && !view ? (
        <div className="space-y-4">
          <Skeleton className="h-24 w-full rounded-lg" />
          <Skeleton className="h-40 w-full rounded-lg" />
          <Skeleton className="h-64 w-full rounded-lg" />
        </div>
      ) : !view ? (
        // 拉失败与"确实没有活动"必须分开说：合成一句"当前没有进行中的活动"，
        // 活动期间后端抖一下就是对着用户否认活动的存在
        <Card>
          <CardContent className="p-0">
            {viewFailed ? (
              <>
                <EmptyState icon={<TriangleAlert />} text="活动数据加载失败，请刷新重试" />
                <div className="pb-8 flex justify-center">
                  <Button variant="outline" size="sm" onClick={reload} disabled={loading}>
                    {loading
                      ? <Loader2 className="w-3.5 h-3.5 animate-spin" />
                      : <RefreshCw className="w-3.5 h-3.5" />}
                    重试
                  </Button>
                </div>
              </>
            ) : (
              <EmptyState icon={<Gift />} text="当前没有进行中的活动，下一场开始后这里会自动亮起来" />
            )}
          </CardContent>
        </Card>
      ) : (
        <>
          {/* 手里有数据、但这一轮有请求没拉到：不清屏，明说页面可能不准。
              /current 挂掉也要报 —— 它喂着状态徽标与预估卡，首屏挂掉时页面是错的但看不出来 */}
          {(viewFailed || currentFailed) && (
            <div className="pt-card rounded-lg px-3 py-2 flex items-center gap-2 text-[11px]">
              <TriangleAlert className="w-3.5 h-3.5 shrink-0 text-warning" />
              <span className="text-muted-foreground">
                {viewFailed
                  ? '数据刷新失败，下面显示的是上一次的结果'
                  : '活动状态没拉到，结算状态与到手金额可能不是最新的'}
              </span>
              <Button variant="ghost" size="sm" className="ml-auto h-7" onClick={reload} disabled={loading}>
                {loading
                  ? <Loader2 className="w-3.5 h-3.5 animate-spin" />
                  : <RefreshCw className="w-3.5 h-3.5" />}
                重试
              </Button>
            </div>
          )}

          {/* ===== 活动头 ===== */}
          <Card>
            <CardContent className="pt-4 pb-4">
              <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
                <span className="p-1.5 rounded-xl bg-primary/10 text-primary"><Gift className="w-4 h-4" /></span>
                <h1 className="text-lg font-black tracking-tight">{view.campaignName}</h1>
                <Badge variant={statusBadge.variant}>{statusBadge.text}</Badge>
                <span className="num text-[11px] text-muted-foreground">
                  {fmtDateTime(view.startAt)} — {fmtDateTime(view.endAt)}
                </span>
              </div>
              <div className="mt-4 grid grid-cols-2 md:grid-cols-4 gap-4">
                <div>
                  <div className="microlabel font-semibold">奖池</div>
                  <div className="num text-lg font-bold mt-0.5">{fmtNum(view.prizePool)} <span className="text-xs text-muted-foreground">LDC</span></div>
                </div>
                <div>
                  <div className="microlabel font-semibold">参与人数</div>
                  <div className="num text-lg font-bold mt-0.5">{view.participants}</div>
                </div>
                <div>
                  <div className="microlabel font-semibold">全站有效总分</div>
                  <div className="num text-lg font-bold mt-0.5">{fmtScore(view.eligibleTotal)}</div>
                </div>
                <div>
                  <div className="microlabel font-semibold">{notStarted ? '距开始' : ended ? '已结束' : '剩余'}</div>
                  <div className="num text-lg font-bold mt-0.5">{ended ? '—' : `${daysLeft} 天`}</div>
                </div>
              </div>
            </CardContent>
          </Card>

          {/* ===== ② 我的积分 + 预估到手 ===== */}
          <div className="grid lg:grid-cols-[1.6fr_1fr] gap-4 items-stretch">
            <Card>
              <CardHeader className="pb-2"><CardTitle>我的积分</CardTitle></CardHeader>
              <CardContent className="pb-4">
                <div className="flex items-baseline gap-3">
                  <span className="num text-4xl font-bold tracking-tighter tabular-nums">
                    {fmtScore(view.me.finalScore)}
                  </span>
                  <span className="text-xs text-muted-foreground">
                    {view.rank > 0 ? `全站第 ${view.rank} 名` : '还没上榜'}
                  </span>
                </div>
                <div className="mt-4 pt-4 grid grid-cols-4 gap-3 border-t border-border/50">
                  <ScoreCell label="交易" value={view.me.tradeScore} />
                  <ScoreCell label="日常" value={view.me.dailyScore} />
                  <ScoreCell label="投票" value={view.me.voteScore} />
                  <ScoreCell label="罚分" value={view.me.penalty} />
                </div>
                {/* 活动进行中的分只是下限：临近结束的预测下注与最后一天的投票要隔天才结算得出 */}
                <p className="mt-3 text-[10px] text-muted-foreground leading-relaxed">
                  个人总分下限为 0，强平扣分不会把你扣成负数，也不影响别人的分配比例。
                  最后一天的投票与临近结束的预测下注要隔天结算才计入，活动期间看到的分只会偏低。
                </p>
              </CardContent>
            </Card>

            {/* 结算后一律以 /reward 为准：预估是除法，真实分配走最大余额法补零头，两者能差一分。
                标题、数字、说明三处必须同源同序（reward → rewardUnknown → settled → 兜底），
                各判各的就会拼出"实际到手 0.00"配"奖励信息加载失败"这种自相矛盾的一屏 */}
            <Card>
              <CardHeader className="pb-2">
                <CardTitle>
                  {reward ? '实际到手' : rewardUnknown ? '到手金额' : settled ? '实际到手' : '预估到手'}
                </CardTitle>
              </CardHeader>
              <CardContent className="pb-4">
                <div className="flex items-baseline gap-1.5">
                  <span className="num text-3xl font-bold tracking-tighter tabular-nums text-primary">
                    {/* 说不准的时候就给"—"。这一格宁可什么都不说，也不能报一个关于别人钱的数字 */}
                    {reward
                      ? fmtNum(reward.ldcAmount)
                      : rewardUnknown ? '—' : fmtNum(settled ? 0 : view.estimatedLdc)}
                  </span>
                  <span className="text-sm font-bold text-muted-foreground">LDC</span>
                </div>
                <p className="mt-2 text-[10px] text-muted-foreground leading-relaxed">
                  {reward
                    ? '结算已完成，这是按最大余额法分到你名下的实发金额。'
                    : rewardUnknown
                      ? '结算已完成，但奖励信息没拉到，暂时算不出你的到手金额，刷新后重试。'
                      : settled
                        ? '结算已完成，本次没有分到 LDC。'
                        : '随参与人数变动，以结算为准。'}
                </p>
                {/* 算式只在"还有得算"的时候给：没结算、没拿到奖励行、不在说不准的状态、且分母为正。
                    不判分母的话开赛第一天全站 0 分，这里会渲染成 500.00 × 0 ÷ 0 */}
                {!reward && !rewardUnknown && !settled && view.eligibleTotal > 0 && (
                  <p className="mt-2 num text-[10px] text-muted-foreground">
                    {fmtNum(view.prizePool)} × {fmtScore(view.me.finalScore)} ÷ {fmtScore(view.eligibleTotal)}
                  </p>
                )}
              </CardContent>
            </Card>
          </div>

          {/* ===== ⑤ 领取 ===== */}
          <Card>
            <CardHeader className="pb-2"><CardTitle>LDC 领取</CardTitle></CardHeader>
            <CardContent className="pb-4">
              {reward ? (
                reward.status === 'SUCCESS' ? (
                  <div className="space-y-1">
                    <div className="text-sm font-bold text-gain">
                      已到账 {fmtNum(reward.ldcAmount)} LDC
                    </div>
                    {reward.externalRef && (
                      <div className="num text-[10px] text-muted-foreground">流水号 {reward.externalRef}</div>
                    )}
                  </div>
                ) : reward.status === 'CLAIMED' ? (
                  <div className="space-y-1">
                    <div className="text-sm font-bold">发放中…</div>
                    <p className="text-[11px] text-muted-foreground leading-relaxed">
                      服务端最坏要等约 2 分钟。稍后刷新本页查看结果；若长时间停在这里，请联系管理员。
                    </p>
                  </div>
                ) : (
                  <div className="space-y-3">
                    {reward.status === 'FAILED' && (
                      <div className="text-[11px] text-loss leading-relaxed">
                        上次发放失败{reward.errorMsg ? `：${reward.errorMsg}` : ''}。可以直接重领，
                        商户单号不变，不会重复发放。
                      </div>
                    )}
                    {/* 显示收款账号让人先确认：授权错号 = 把钱发给陌生人。
                        后端还会拿授权回来的 linux_do_id 跟当前账号核对一次，不符直接拒 */}
                    <div className="text-sm">
                      即将发放给：<strong className="font-bold">{user?.username ?? '—'}</strong>
                      <span className="text-[11px] text-muted-foreground ml-2">（当前登录账号）</span>
                    </div>
                    <p className="text-[11px] text-muted-foreground leading-relaxed">
                      点击后会跳转 LinuxDo 授权一次（平台存的用户名可能是旧的，分发接口要用最新的）。
                      请确认授权的是与本账号绑定的那个 LinuxDo 账号，授权别人的会被拒绝。
                    </p>
                    <Button onClick={startClaim} disabled={redirecting}>
                      {redirecting ? <Loader2 className="w-4 h-4 animate-spin" /> : <Gift className="w-4 h-4" />}
                      {redirecting ? '跳转授权中…' : `领取 ${fmtNum(reward.ldcAmount)} LDC`}
                    </Button>
                  </div>
                )
              ) : rewardUnknown ? (
                // 没拉到奖励行 ≠ 没分到钱。这一支要是漏了，一次超时就会告诉一个真有钱的人他没分到。
                // 判据与上面预估卡同一个 rewardUnknown，两张卡不会各说各的
                <div className="space-y-1">
                  <div className="text-sm font-bold">奖励信息加载失败</div>
                  <p className="text-[11px] text-muted-foreground leading-relaxed">
                    这不代表你没有奖励，刷新页面重试即可。
                  </p>
                </div>
              ) : settled ? (
                // 0.00 不落行（见 CampaignSettleService），所以真参与了也可能查不到奖励行 ——
                // 这跟"你不在名单里"是两回事，别写成后者
                <div className="space-y-1">
                  <div className="text-sm font-bold">本次结算你没有分到 LDC</div>
                  <p className="text-[11px] text-muted-foreground leading-relaxed">
                    分配额不足 0.01 LDC 时不会生成发放记录。邀请码注册的账号不参与分配。
                  </p>
                </div>
              ) : (
                <p className="text-[11px] text-muted-foreground leading-relaxed">
                  活动结束并完成结算后，你的到手金额会显示在这里，届时点一次 LinuxDo 授权即可领取。
                </p>
              )}
            </CardContent>
          </Card>

          {/* ===== ③ 明日投票 + 签到 ===== */}
          <div className="grid md:grid-cols-3 gap-4">
            <Card>
              <CardHeader className="pb-2"><CardTitle>每日签到</CardTitle></CardHeader>
              <CardContent className="pb-4 space-y-3">
                {/* disabled 里带 loading：页面不再清屏了，刷新那零点几秒里 checkedToday 还是旧的 false，
                    不锁的话手快的人能再点一次，换回一句"今天已经签到过了" */}
                <Button
                  className="w-full"
                  variant={view.checkedToday ? 'outline' : 'default'}
                  disabled={view.checkedToday || acting || loading || !canAct}
                  onClick={handleCheckin}
                >
                  {acting ? <Loader2 className="w-4 h-4 animate-spin" /> : <CalendarCheck className="w-4 h-4" />}
                  {view.checkedToday ? '今日已签到' : '签到 +1'}
                </Button>
                <p className="text-[10px] text-muted-foreground leading-relaxed">
                  每天 +1，连签 3 / 7 / 14 天再累进拿 +5 / +15 / +40。断签重新计连续天数。
                </p>
              </CardContent>
            </Card>

            {view.voteBoard.map(v => (
              <Card key={v.symbol}>
                <CardHeader className="pb-2">
                  {/* 目标日必须摆在标题上：投的是明天，不写出来是哪天，用户只会当成今天 */}
                  <CardTitle className="flex items-baseline justify-between gap-2">
                    <span>明日多空 · {v.label}</span>
                    <span className="num text-[10px] tracking-normal normal-case tabular-nums">
                      UTC {voteDay}
                    </span>
                  </CardTitle>
                </CardHeader>
                <CardContent className="pb-4 space-y-2">
                  <div className="grid grid-cols-2 gap-2">
                    <VoteSide
                      dir="UP"
                      count={v.upCount}
                      mine={v.myDirection}
                      disabled={acting || loading || !canAct || voteLocked || v.myDirection !== null}
                      onClick={() => handleVote(v.symbol, 'UP')}
                    />
                    <VoteSide
                      dir="DOWN"
                      count={v.downCount}
                      mine={v.myDirection}
                      disabled={acting || loading || !canAct || voteLocked || v.myDirection !== null}
                      onClick={() => handleVote(v.symbol, 'DOWN')}
                    />
                  </div>
                  {/* 锁盘那句排在最前：按钮灰着的时候，人第一件想知道的事是"为什么点不了" */}
                  <p className="text-[10px] text-muted-foreground leading-relaxed">
                    {voteLocked
                      ? 'UTC 23:55-00:05 结算锁盘：日线在切换、上一日的票在结算，这 10 分钟不收票。'
                      : v.myDirection
                        ? `UTC ${voteDay} 的${v.myDirection === 'UP' ? '看涨' : '看跌'}已投，多空二选一，明天再来。`
                        : `投的是 UTC ${voteDay} 这一天，收票到 UTC 23:55 止（那时这天还没开始）。按该日日线收盘 vs 前日收盘结算，平盘顺延到次日奖池。`}
                  </p>
                </CardContent>
              </Card>
            ))}
          </div>

          {/* ===== ④ 任务清单 ===== */}
          <Card className="overflow-hidden">
            <CardHeader className="pb-3">
              <CardTitle>任务清单</CardTitle>
            </CardHeader>
            <CardContent className="p-0">
              {grouped.map(g => (
                <div key={g.group}>
                  <div className="px-4 py-1.5 bg-card-2 border-y border-border/30">
                    <span className={cn('microlabel font-bold', g.group === '罚分' && 'text-loss')}>
                      {g.group}
                    </span>
                  </div>
                  {g.rows.map(r => <TaskRow key={r.def.code} def={r.def} item={r.item} />)}
                </div>
              ))}
            </CardContent>
          </Card>

          {/* ===== 积分榜 ===== */}
          <Card className="overflow-hidden">
            <CardHeader className="pb-3">
              <CardTitle className="flex items-center gap-2">
                <Trophy className="w-3.5 h-3.5" />积分榜
              </CardTitle>
            </CardHeader>
            <CardContent className="p-0">
              {board.length === 0 ? (
                <EmptyState
                  icon={boardFailed ? <TriangleAlert /> : <Trophy />}
                  text={boardFailed ? '榜单加载失败，请刷新重试' : '还没有人上榜，先去挣第一分'}
                />
              ) : (
                board.slice(0, 20).map((s, i) => (
                  <div
                    key={s.userId}
                    className={cn(
                      'flex items-center gap-3 px-4 py-2.5 border-b border-border/25 last:border-b-0',
                      s.userId === user?.id && 'bg-primary/8',
                    )}
                  >
                    <span className="num text-[11px] text-muted-foreground w-6 shrink-0 tabular-nums">
                      {i + 1}
                    </span>
                    <span className="text-[13px] font-semibold truncate flex-1 min-w-0">
                      {s.username || `#${s.userId}`}
                    </span>
                    <span className="num text-[13px] font-bold tabular-nums shrink-0">
                      {fmtScore(s.finalScore)}
                    </span>
                  </div>
                ))
              )}
            </CardContent>
          </Card>
        </>
      )}
    </div>
  );
}
