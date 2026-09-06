import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Skeleton } from './ui/skeleton';
import { useStagger } from '../hooks/useStagger';
import { quantApi, type NewsEventItem } from '../api';
import { currentLang } from '../i18n';
import { fmtTime } from '../lib/utils';

/**
 * 实时快讯（首页，与最新成交并列）：读 news_event 存档（采集轨定时打标+翻译后落库），前端 60s 轻轮询。
 * <p>中英两套一起到，切语言不重拉。英文界面只展示标题正文都译好的那些，没译完的不展示，不拿中文凑。
 */
export function NewsFlashCard() {
  const { t } = useTranslation('home');
  const en = currentLang() === 'en';
  const [items, setItems] = useState<NewsEventItem[] | null>(null);
  const listRef = useStagger<HTMLDivElement>();

  useEffect(() => {
    let alive = true;
    const load = () => quantApi.news()
      .then(list => { if (alive) setItems(list); })
      .catch(() => { if (alive) setItems(prev => prev ?? []); });
    load();
    const t = setInterval(load, 60_000);
    return () => { alive = false; clearInterval(t); };
  }, []);

  const shown = items == null ? null : en ? items.filter(n => n.titleEn && n.contentEn) : items;

  return (
    <>
      <div className="sec-h">
        <h2>{t('news.title')}</h2>
        <span>{t('news.sub')}</span>
      </div>
      {shown == null ? (
        <div className="space-y-4">
          {Array.from({ length: 3 }).map((_, i) => <Skeleton key={i} className="h-11" />)}
        </div>
      ) : shown.length === 0 ? (
        <div className="py-10 text-center text-sm text-muted-foreground">{t('news.empty')}</div>
      ) : (
        <div ref={listRef} className="max-h-[560px] overflow-y-auto">
          {shown.map(n => {
            const title = en ? n.titleEn : n.title;
            const content = en ? n.contentEn : n.content;
            return (
              <div key={n.id} className="grid grid-cols-[56px_1fr] gap-4 py-4 border-b border-border">
                <span className="num text-[13px] text-muted-foreground pt-[3px]">{fmtTime(n.publishedAt)}</span>
                <div className="min-w-0">
                  {n.url
                    ? <a href={n.url} target="_blank" rel="noopener noreferrer" className="block text-[18px] font-bold tracking-[-0.01em] leading-[1.35] break-words">{title}</a>
                    : <div className="text-[18px] font-bold tracking-[-0.01em] leading-[1.35] break-words">{title}</div>}
                  {/* 全文不截断：2/3 宽度是给全文腾的，截两行就白拿这个宽度了 */}
                  {content && (
                    <div className="mt-1 max-w-[72ch] text-sm text-muted-foreground leading-[1.45] break-words">{content}</div>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </>
  );
}
