import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { futuresApi } from '../api';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { PositionHistoryList } from '../components/PositionHistoryList';
import { cn } from '../lib/utils';
import { ArrowLeft, History, RefreshCw } from 'lucide-react';
import type { PageResult, PositionHistoryItem } from '../types';

const PAGE_SIZE = 20;

const EMPTY: PageResult<PositionHistoryItem> = {
  records: [], total: 0, size: PAGE_SIZE, current: 1, pages: 0,
};

/**
 * 我的合约仓位历史。一行是一笔完整的仓位（开仓到全部平掉），点开是这笔仓位的每一次成交。
 * <p>
 * 跟「成交记录」页的分工：那边一行一笔委托，看的是"什么时候下了什么单"；
 * 这边把同一仓位的开仓、加仓、分批平仓合成一条，看的是"这笔仓位最后赚没赚、回报率多少"。
 */
export function PositionHistory() {
  const navigate = useNavigate();
  const { t } = useTranslation(['portfolio', 'common']);
  const [data, setData] = useState<PageResult<PositionHistoryItem>>(EMPTY);
  const [page, setPage] = useState(1);
  const [refreshNonce, setRefreshNonce] = useState(0);

  // loading 由"已加载 key 是否追上请求 key"派生：在 effect 里同步 setLoading(true)
  // 会触发级联渲染，eslint 的 react-hooks/set-state-in-effect 直接判错（同 Ledger/ForceOrders）
  const requestKey = `${page}:${refreshNonce}`;
  const [loadedKey, setLoadedKey] = useState<string | null>(null);
  const loading = loadedKey !== requestKey;

  useEffect(() => {
    let cancelled = false;
    futuresApi.positionHistory(page, PAGE_SIZE)
      .then(res => { if (!cancelled) setData(res); })
      .catch(() => { if (!cancelled) setData(EMPTY); })
      .finally(() => { if (!cancelled) setLoadedKey(requestKey); });
    return () => { cancelled = true; };
  }, [requestKey, page]);

  return (
    <div className="page-shell p-4 md:p-6 space-y-4">
      <Button variant="ghost" size="sm" className="gap-1.5" onClick={() => navigate('/portfolio')}>
        <ArrowLeft className="w-4 h-4" />
        {t('history.back')}
      </Button>

      <Card>
        <CardHeader className="pb-3">
          <div className="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
            <div className="space-y-1.5">
              <CardTitle className="flex items-center gap-2.5 text-lg font-black tracking-tight">
                <span className="p-1.5 rounded-xl bg-primary/10 text-primary">
                  <History className="w-4 h-4" />
                </span>
                {t('history.title')}
                <span className="text-xs text-muted-foreground font-normal">{t('history.total', { count: data.total })}</span>
              </CardTitle>
              <p className="text-xs text-muted-foreground leading-relaxed">
                {t('history.desc')}
              </p>
              {/* 「我平了一半怎么这儿没有」是必然会有的疑问，与其等人来问，不如写在页面上 */}
              <p className="text-xs text-muted-foreground/80 leading-relaxed">
                {t('history.note')}
              </p>
            </div>
            <Button
              variant="outline"
              size="sm"
              className="h-9 gap-2 shrink-0"
              onClick={() => setRefreshNonce(n => n + 1)}
            >
              <RefreshCw className={cn('w-4 h-4', loading && 'animate-spin')} />
              {t('common:refresh')}
            </Button>
          </div>
        </CardHeader>
      </Card>

      <Card className="overflow-hidden">
        <CardContent className="p-0">
          <PositionHistoryList
            records={data.records}
            page={data.current}
            pages={data.pages}
            loading={loading}
            onPage={setPage}
          />
        </CardContent>
      </Card>
    </div>
  );
}
