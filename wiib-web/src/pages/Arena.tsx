import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Bot, Plus, RefreshCcw, Swords } from 'lucide-react';
import { traderApi } from '../api';
import { toolName } from '../components/arena/traderTools';
import { useArenaLive } from '../hooks/useTraderLive';
import { cn } from '../lib/utils';
import type { TraderLiveStatus, TraderPublicView } from '../types';

const REFRESH_MS = 60_000;

/** 实时芯片：脉冲点 + 在调的工具（带币种）；没在调工具就是第几次思考，还没开始思考就是唤醒中 */
function LiveChip({ st }: { st: TraderLiveStatus }) {
  const { t } = useTranslation('ai');
  const text = st.tool
    ? `${toolName(st.tool)}${st.symbol ? ` ${st.symbol.replace('USDT', '')}` : ''}`
    : st.call > 0 ? t('live.thinking', { n: st.call }) : t('live.running');
  return (
    <span className="inline-flex items-center gap-1 text-[10px] font-bold px-1.5 py-0.5 rounded bg-primary/10 text-primary whitespace-nowrap shrink-0">
      <span className="w-1.5 h-1.5 rounded-full bg-primary animate-pulse" />{text}
    </span>
  );
}

/**
 * trader 运行状态徽章（竞技场排行与详情页共用一份，别再各抄各的）。
 * 存的是词表 key 不是文案：模块级常量只算一次，存翻好的字面量切了语言也不会变。
 */
export const STATUS_META: Record<string, { labelKey: string; tone: string }> = {
  RUNNING: { labelKey: 'status.running', tone: 'bg-gain/15 text-gain' },
  PAUSED: { labelKey: 'status.paused', tone: 'bg-amber-500/15 text-amber-600' },
  LIQUIDATED: { labelKey: 'status.liquidated', tone: 'bg-loss/15 text-loss' },
};

/**
 * AI Trader 竞技场：全部 trader 按收益率排行。
 * 卖点是"看 AI 怎么想、怎么下注"——排行只是记分牌，点进详情看决策时间线才是正餐。
 */
export function Arena() {
  const { t } = useTranslation(['ai', 'common']);
  const [traders, setTraders] = useState<TraderPublicView[]>([]);
  const [loading, setLoading] = useState(true);
  // 谁在唤醒中：行内芯片；有人跑完（endedAt 变）排行立刻重拉
  const { statuses, endedAt } = useArenaLive();

  const load = useCallback(() => {
    traderApi.arena()
      .then(setTraders)
      .catch(() => setTraders([]))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => clearInterval(timer);
  }, [load, endedAt]);

  return (
    <div className="page-shell p-4 md:p-6 space-y-4">
      <div className="flex items-center gap-2.5">
        <Swords className="w-5 h-5 text-primary shrink-0" />
        <h1 className="text-lg font-black whitespace-nowrap">{t('arena.title')}</h1>
        {/* 副标题手机上藏：挤不下，信息也非必需 */}
        <span className="text-[11px] text-muted-foreground hidden md:inline">{t('arena.subtitle')}</span>
        <div className="ml-auto flex items-center gap-2 shrink-0">
          <button
            onClick={load}
            className="border border-border hover:bg-surface-hover w-8 h-8 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary"
            aria-label={t('common:refresh')}
          >
            <RefreshCcw className={cn('w-3.5 h-3.5', loading && 'animate-spin')} />
          </button>
          <Link
            to="/my-trader"
            className="border border-border hover:bg-surface-hover rounded-lg px-3 h-8 flex items-center gap-1.5 text-xs font-bold text-primary whitespace-nowrap"
          >
            <Plus className="w-3.5 h-3.5" /> {t('arena.myTrader')}
          </Link>
        </div>
      </div>

      {traders.length === 0 && !loading ? (
        <div className="rounded-lg pt-card py-14 flex flex-col items-center gap-3 text-center">
          <div className="w-12 h-12 rounded-full border border-border bg-background flex items-center justify-center text-muted-foreground/70">
            <Bot className="w-6 h-6" />
          </div>
          <div className="text-sm font-bold text-muted-foreground">{t('arena.empty')}</div>
          <div className="text-[11px] text-muted-foreground/70">{t('arena.emptyHint')}</div>
          <Link to="/my-trader" className="mt-1 border border-border rounded-lg px-4 py-2 text-xs font-bold text-primary hover:bg-surface-hover">
            {t('arena.create')}
          </Link>
        </div>
      ) : (
        <div className="rounded-lg pt-card overflow-hidden">
          {/* 手机端卡片列：8列表格在小屏只能横拖，改为每人一卡（名次/名字/收益率主视觉，其余次要行） */}
          <div className="md:hidden divide-y divide-border/60">
            {traders.map((tr, i) => {
              const st = STATUS_META[tr.status] ?? STATUS_META.PAUSED;
              const live = statuses.get(tr.id);
              return (
                <Link key={tr.id} to={`/arena/${tr.id}`} className="block px-3.5 py-3 hover:bg-surface-hover active:bg-surface-hover">
                  <div className="flex items-center gap-2">
                    <span className="num font-black text-muted-foreground w-5 shrink-0">{i + 1}</span>
                    <span className="font-black text-sm truncate">{tr.name}</span>
                    {tr.mine && <span className="text-[9px] font-bold px-1 py-0.5 rounded bg-primary/15 text-primary shrink-0">{t('arena.mine')}</span>}
                    <span className={cn('ml-auto num font-black text-sm shrink-0', tr.pnlPct >= 0 ? 'text-gain' : 'text-loss')}>
                      {tr.pnlPct >= 0 ? '+' : ''}{tr.pnlPct.toFixed(2)}%
                    </span>
                  </div>
                  <div className="mt-1 pl-7 flex items-center gap-2 text-[11px] text-muted-foreground flex-wrap">
                    <span className="truncate max-w-[45%]">{tr.model ?? t('term.noModel')}</span>
                    <span className="shrink-0">{tr.intervalCode}{tr.wakeWindow && ` · ${tr.wakeWindow}`} · {tr.symbols.split(',').map(s => s.replace('USDT', '')).join('/')}</span>
                    <span className="num shrink-0">{t('term.equity')} {tr.equity.toLocaleString()}</span>
                    <span className="num shrink-0">R{tr.roundNo}</span>
                    <span className={cn('text-[10px] font-bold px-1.5 py-0.5 rounded shrink-0', st.tone)}>{t(st.labelKey)}</span>
                    {live && <LiveChip st={live} />}
                  </div>
                </Link>
              );
            })}
          </div>

          <div className="hidden md:block overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="text-left text-[10px] uppercase text-muted-foreground border-b border-border">
                  <th className="px-4 py-2.5 w-10">#</th>
                  <th className="px-2 py-2.5">Trader</th>
                  <th className="px-2 py-2.5">{t('arena.model')}</th>
                  <th className="px-2 py-2.5">{t('arena.tempo')}</th>
                  <th className="px-2 py-2.5 text-right">{t('term.equity')}</th>
                  <th className="px-2 py-2.5 text-right">{t('arena.return')}</th>
                  <th className="px-2 py-2.5 text-center">{t('arena.round')}</th>
                  <th className="px-4 py-2.5 text-right">{t('arena.status')}</th>
                </tr>
              </thead>
              <tbody>
                {traders.map((tr, i) => {
                  const st = STATUS_META[tr.status] ?? STATUS_META.PAUSED;
                  const live = statuses.get(tr.id);
                  return (
                    <tr key={tr.id} className="border-b border-border/60 last:border-0 hover:bg-surface-hover">
                      <td className="px-4 py-2.5 num font-black text-muted-foreground">{i + 1}</td>
                      <td className="px-2 py-2.5">
                        <Link to={`/arena/${tr.id}`} className="font-black text-foreground hover:text-primary hover:underline">
                          {tr.name}
                        </Link>
                        {tr.mine && <span className="ml-1.5 text-[9px] font-bold px-1 py-0.5 rounded bg-primary/15 text-primary">{t('arena.mine')}</span>}
                      </td>
                      <td className="px-2 py-2.5 text-muted-foreground">{tr.model ?? t('term.noModel')}</td>
                      <td className="px-2 py-2.5 text-muted-foreground">{tr.intervalCode}{tr.wakeWindow && ` · ${tr.wakeWindow}`} · {tr.symbols.split(',').map(s => s.replace('USDT', '')).join('/')}</td>
                      <td className="px-2 py-2.5 text-right num font-bold">{tr.equity.toLocaleString()}</td>
                      <td className={cn('px-2 py-2.5 text-right num font-black', tr.pnlPct >= 0 ? 'text-gain' : 'text-loss')}>
                        {tr.pnlPct >= 0 ? '+' : ''}{tr.pnlPct.toFixed(2)}%
                      </td>
                      <td className="px-2 py-2.5 text-center num text-muted-foreground">R{tr.roundNo}</td>
                      <td className="px-4 py-2.5 text-right">
                        <span className="inline-flex items-center gap-1.5">
                          {live && <LiveChip st={live} />}
                          <span className={cn('text-[10px] font-bold px-1.5 py-0.5 rounded', st.tone)}>{t(st.labelKey)}</span>
                        </span>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
}
