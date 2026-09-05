import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Newspaper, ExternalLink } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from './ui/card';
import { Skeleton } from './ui/skeleton';
import { quantApi, type NewsEventItem } from '../api';
import { currentLang } from '../i18n';
import { fmtDateTime } from '../lib/utils';

/**
 * 实时快讯卡（首页，与最新成交并列）：读 news_event 存档（采集轨定时打标+翻译后落库），前端 60s 轻轮询。
 * <p>中英两套一起到，切语言不重拉。英文界面只展示标题正文都译好的那些，没译完的不展示，不拿中文凑。
 */
export function NewsFlashCard() {
  const { t } = useTranslation('home');
  const en = currentLang() === 'en';
  const [items, setItems] = useState<NewsEventItem[] | null>(null);

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
    <Card className="flex flex-col">
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2">
          <Newspaper className="w-3.5 h-3.5 text-primary" />
          {t('news.title')}
          <span className="led ml-1" />
        </CardTitle>
      </CardHeader>
      <CardContent className="pt-0 flex-1 overflow-hidden">
        {shown == null ? (
          <div className="space-y-2.5 pt-1">
            {Array.from({ length: 5 }).map((_, i) => <Skeleton key={i} className="h-8" />)}
          </div>
        ) : shown.length === 0 ? (
          <div className="py-10 text-center text-sm text-muted-foreground">{t('news.empty')}</div>
        ) : (
          // overflow-x-hidden + break-words：正文全文展示后，长链接/无空格长串不能把卡顶出横向滚动条
          <div className="max-h-96 overflow-y-auto overflow-x-hidden -mx-1 px-1">
            {shown.map(n => {
              const content = en ? n.contentEn : n.content;
              return (
                <a
                  key={n.id}
                  href={n.url || undefined}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="group flex gap-2.5 py-2 border-b border-border/60 last:border-0 hover:bg-surface-hover -mx-2 px-2 rounded-md transition-colors"
                >
                  <span className="num text-[10px] text-muted-foreground shrink-0 pt-0.5">
                    {fmtDateTime(n.publishedAt)}
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block text-xs font-semibold leading-snug break-words group-hover:text-primary transition-colors">
                      {en ? n.titleEn : n.title}
                      {n.url && <ExternalLink className="inline w-2.5 h-2.5 ml-1 opacity-40" />}
                    </span>
                    {/* 全文不截断：2/3 宽度是给全文腾的，截两行就白拿这个宽度了 */}
                    {content && (
                      <span className="block text-[11px] text-muted-foreground leading-relaxed mt-0.5 break-words">
                        {content}
                      </span>
                    )}
                  </span>
                </a>
              );
            })}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
