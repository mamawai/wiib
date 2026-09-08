import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Skeleton } from './ui/skeleton';
import { quantApi, type EconCalendarEvent, type EconCalendarView } from '../api';
import { cn, fmtDateTime } from '../lib/utils';

/** 数据 4 小时才同步一次，轮询只是为了让"已公布/即将公布"的分界跟着时间走 */
const POLL_MS = 300_000;

/** 影响级别方块：High 红、Medium 琥珀，其余灰 */
const IMPACT_CLS: Record<string, string> = { High: 'bg-loss', Medium: 'bg-warning' };

/**
 * 首页财经日历：ForexFactory 本周快照，左栏已公布、右栏即将公布，各最多 6 条。
 * 筛选口径与 trader 唤醒注入同一条规则（High / Medium 全留 + USD 讲话类）。
 * 标题是 feed 英文原文，两种语言都照原样显示；免费源没有实际值，只有预测与前值。
 */
export function EconCalendarCard() {
  const { t } = useTranslation('home');
  const [data, setData] = useState<EconCalendarView | null>(null);

  useEffect(() => {
    let alive = true;
    const load = () => quantApi.econCalendar()
      .then(v => { if (alive) setData(v); })
      .catch(() => { if (alive) setData(prev => prev ?? { past: [], upcoming: [] }); });
    load();
    const timer = setInterval(load, POLL_MS);
    return () => { alive = false; clearInterval(timer); };
  }, []);

  return (
    <>
      <div className="sec-h">
        <h2>{t('calendar.title')}<small>{t('calendar.note')}</small></h2>
      </div>
      <div className="g12">
        <Column title={t('calendar.published')} rows={data?.past} empty={t('calendar.emptyPast')} />
        {/* 即将公布的头一条是最近要撞上的，加粗 */}
        <Column title={t('calendar.upcoming')} rows={data?.upcoming} empty={t('calendar.emptyUpcoming')} boldFirst />
      </div>
    </>
  );
}

function Column({ title, rows, empty, boldFirst }: {
  title: string;
  /** undefined=还没拉回来 */
  rows?: EconCalendarEvent[];
  empty: string;
  boldFirst?: boolean;
}) {
  const { t } = useTranslation('home');
  return (
    <div className="col-span-12 xl:col-span-6">
      <div className="microlabel uppercase pb-2 border-b border-foreground">{title}</div>
      {rows == null ? (
        <div className="space-y-3 pt-3">
          {Array.from({ length: 3 }).map((_, i) => <Skeleton key={i} className="h-9" />)}
        </div>
      ) : rows.length === 0 ? (
        <div className="py-8 text-center text-sm text-muted-foreground">{empty}</div>
      ) : rows.map((r, i) => (
        <div key={`${r.eventTime}-${r.currency}-${r.title}`}
             className={cn('grid grid-cols-[92px_1fr_auto] gap-3 items-baseline py-2.5 border-b border-border text-[14px]',
               boldFirst && i === 0 && 'font-semibold')}>
          <span className="num text-[13px] text-muted-foreground">{fmtDateTime(r.eventTime)}</span>
          <span className="min-w-0 flex items-baseline gap-2">
            <i className={cn('shrink-0 self-center w-[7px] h-[7px]', IMPACT_CLS[r.impact] ?? 'bg-muted-foreground/50')} title={r.impact} />
            <b className="shrink-0 text-[12px] font-bold">{r.currency}</b>
            <span className="truncate">{r.title}</span>
          </span>
          {(r.forecast || r.previous) && (
            <span className="num whitespace-nowrap text-[12px] text-muted-foreground">
              {r.forecast && `${t('calendar.forecast')} ${r.forecast}`}
              {r.forecast && r.previous && ' · '}
              {r.previous && `${t('calendar.previous')} ${r.previous}`}
            </span>
          )}
        </div>
      ))}
    </div>
  );
}
