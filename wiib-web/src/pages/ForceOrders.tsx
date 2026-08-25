import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { futuresApi } from '../api';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Badge } from '../components/ui/badge';
import { Button } from '../components/ui/button';
import { Skeleton } from '../components/ui/skeleton';
import { cn, fmtDateTime, fmtNum } from '../lib/utils';
import { AlertTriangle, ChevronLeft, ChevronRight, Flame, RefreshCw } from 'lucide-react';
import type { ForceOrder, PageResult } from '../types';
import { formatCoinPrice } from '../lib/coinConfig';

const SYMBOLS = ['BTCUSDT', 'ETHUSDT', 'DOGEUSDT'] as const;
const PAGE_SIZE = 20;

/** 币安口径：强平单方向是被吃掉那侧的反向，SELL 单意味着多头被平掉 */
function sideLabelKey(side: string): string {
  return side === 'SELL' ? 'force.longLiquidated' : 'force.shortLiquidated';
}

export function ForceOrders() {
  const { t } = useTranslation(['portfolio', 'common']);
  const [symbol, setSymbol] = useState<string>('BTCUSDT');
  const [page, setPage] = useState(1);
  const [refreshNonce, setRefreshNonce] = useState(0);
  const [result, setResult] = useState<PageResult<ForceOrder>>({
    records: [],
    total: 0,
    size: PAGE_SIZE,
    current: 1,
    pages: 0,
  });
  // loading 由"已加载 key 是否追上请求 key"派生，不在 effect 里同步 setState
  const requestKey = `${symbol}:${page}:${refreshNonce}`;
  const [loadedKey, setLoadedKey] = useState<string | null>(null);
  const loading = loadedKey !== requestKey;

  useEffect(() => {
    let cancelled = false;
    futuresApi.forceOrders(symbol, page, PAGE_SIZE)
      .then(res => { if (!cancelled) setResult(res); })
      .catch(() => { if (!cancelled) setResult({
        records: [],
        total: 0,
        size: PAGE_SIZE,
        current: page,
        pages: 0,
      }); })
      .finally(() => { if (!cancelled) setLoadedKey(requestKey); });
    return () => { cancelled = true; };
  }, [requestKey, symbol, page]);

  const records = result.records;

  const stats = useMemo(() => {
    const longLiquidations = records.filter(o => o.side === 'SELL').length;
    const shortLiquidations = records.filter(o => o.side !== 'SELL').length;
    const totalAmount = records.reduce((sum, o) => sum + o.amount, 0);
    return { longLiquidations, shortLiquidations, totalAmount };
  }, [records]);

  return (
    <div className="page-shell p-4 md:p-6 space-y-5">
      <Card className="relative overflow-hidden">
        <div className="absolute -top-16 -right-12 w-48 h-48 rounded-full bg-loss/10 blur-3xl" />
        <CardHeader className="relative space-y-4">
          <div className="flex flex-col gap-3 md:flex-row md:items-start md:justify-between">
            <div className="space-y-3">
              <CardTitle className="flex items-center gap-3 text-lg sm:text-xl md:text-2xl font-black tracking-tight">
                <span className="p-1.5 sm:p-2 rounded-xl sm:rounded-2xl bg-loss/10 text-loss">
                  <Flame className="w-4 h-4 sm:w-5 sm:h-5" />
                </span>
                {t('force.title')}
              </CardTitle>
              <div className="text-xs sm:text-sm leading-5 sm:leading-6 text-muted-foreground">
                <span className="hidden sm:inline">{t('force.desc')}</span>
                <span className="sm:hidden">{t('force.descShort')}</span>
              </div>
            </div>
            <Button variant="outline" size="sm" className="h-9 w-fit gap-2 shrink-0" onClick={() => setRefreshNonce(n => n + 1)}>
              <RefreshCw className={cn('w-4 h-4', loading && 'animate-spin')} />
              {t('common:refresh')}
            </Button>
          </div>

          <div className="flex flex-wrap gap-2">
            {SYMBOLS.map(item => (
              <button
                key={item}
                onClick={() => {
                  setSymbol(item);
                  setPage(1);
                }}
                className={cn(
                  'px-3.5 py-2 rounded-xl text-xs font-black border transition-colors',
                  symbol === item
                    ? 'bg-foreground text-background border-foreground'
                    : 'bg-card border border-border text-foreground hover:bg-surface-hover',
                )}
              >
                {item.replace('USDT', '')}
              </button>
            ))}
          </div>
        </CardHeader>
      </Card>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
        <Card>
          <CardContent className="p-4 space-y-1">
            <div className="text-xs font-bold text-muted-foreground">{t('force.statLong')}</div>
            <div className="text-xl sm:text-2xl font-black text-loss tabular-nums">{stats.longLiquidations}</div>
            <div className="text-xs text-muted-foreground">{t('force.statLongHint')}</div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-4 space-y-1">
            <div className="text-xs font-bold text-muted-foreground">{t('force.statShort')}</div>
            <div className="text-xl sm:text-2xl font-black text-gain tabular-nums">{stats.shortLiquidations}</div>
            <div className="text-xs text-muted-foreground">{t('force.statShortHint')}</div>
          </CardContent>
        </Card>
        <Card>
          <CardContent className="p-4 space-y-1">
            <div className="text-xs font-bold text-muted-foreground">{t('force.statAmount')}</div>
            <div className="text-xl sm:text-2xl font-black tabular-nums">${fmtNum(stats.totalAmount, 0)}</div>
            <div className="text-xs text-muted-foreground">{t('force.statAmountHint', { count: records.length })}</div>
          </CardContent>
        </Card>
      </div>

      <Card>
        <CardHeader className="pb-3">
          <div className="flex flex-col gap-2 md:flex-row md:items-center md:justify-between">
            <div className="space-y-1">
              <CardTitle className="text-base font-black">{t('force.listTitle', { symbol })}</CardTitle>
              <div className="text-xs text-muted-foreground">
                {t('force.listDesc', { size: PAGE_SIZE, total: result.total })}
              </div>
            </div>
            <div className="hidden sm:flex items-center gap-2 text-xs text-muted-foreground">
              <AlertTriangle className="w-3.5 h-3.5 text-loss shrink-0" />
              {t('force.warn')}
            </div>
          </div>
        </CardHeader>
        <CardContent className="p-0">
          {loading ? (
            <div className="p-4 space-y-3">
              {[...Array(8)].map((_, idx) => <Skeleton key={idx} className="h-16 w-full rounded-xl" />)}
            </div>
          ) : records.length === 0 ? (
            <div className="py-16 text-center text-sm text-muted-foreground">{t('force.empty')}</div>
          ) : (
            <>
              <div className="hidden md:block overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-border/50 text-muted-foreground">
                      <th className="px-5 py-3 text-left font-bold">{t('field.time')}</th>
                      <th className="px-4 py-3 text-left font-bold">{t('field.side')}</th>
                      <th className="px-4 py-3 text-right font-bold">{t('field.price')}</th>
                      <th className="px-4 py-3 text-right font-bold">{t('field.avgPrice')}</th>
                      <th className="px-4 py-3 text-right font-bold">{t('field.qty')}</th>
                      <th className="px-5 py-3 text-right font-bold">{t('field.notional')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {records.map(order => (
                      <tr key={order.id} className="border-b border-border/30 hover:bg-accent/30 transition-colors">
                        <td className="px-5 py-3 text-xs font-mono text-muted-foreground whitespace-nowrap">{fmtDateTime(order.tradeTime, true)}</td>
                        <td className="px-4 py-3">
                          <Badge
                            className={cn(
                              'font-black text-xs',
                              order.side === 'SELL'
                                ? 'bg-loss/10 text-loss border-loss/20'
                                : 'bg-gain/10 text-gain border-gain/20',
                            )}
                          >
                            {t(sideLabelKey(order.side))}
                          </Badge>
                        </td>
                        <td className="px-4 py-3 text-right font-mono font-bold">{formatCoinPrice(order.symbol, order.price)}</td>
                        <td className="px-4 py-3 text-right font-mono text-muted-foreground">{formatCoinPrice(order.symbol, order.avgPrice)}</td>
                        <td className="px-4 py-3 text-right font-mono">{fmtNum(order.quantity, 4)}</td>
                        <td className="px-5 py-3 text-right font-mono font-bold">${fmtNum(order.amount, 0)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              <div className="md:hidden p-2.5 sm:p-3 space-y-2.5 sm:space-y-3">
                {records.map(order => (
                  <div key={order.id} className="rounded-lg border border-border bg-card p-3 sm:p-4 space-y-2.5 sm:space-y-3">
                    <div className="flex items-start justify-between gap-3">
                      <div className="space-y-1">
                        <div className="text-xs text-muted-foreground font-mono">{fmtDateTime(order.tradeTime, true)}</div>
                        <div className="text-sm font-black">{symbol.replace('USDT', '')}</div>
                      </div>
                      <Badge
                        className={cn(
                          'font-black text-xs',
                          order.side === 'SELL'
                            ? 'bg-loss/10 text-loss border-loss/20'
                            : 'bg-gain/10 text-gain border-gain/20',
                        )}
                      >
                        {t(sideLabelKey(order.side))}
                      </Badge>
                    </div>
                    <div className="grid grid-cols-2 gap-3 text-xs">
                      <div className="space-y-1">
                        <div className="text-muted-foreground">{t('field.price')}</div>
                        <div className="font-mono font-bold">{formatCoinPrice(order.symbol, order.price)}</div>
                      </div>
                      <div className="space-y-1">
                        <div className="text-muted-foreground">{t('field.avgPrice')}</div>
                        <div className="font-mono">{formatCoinPrice(order.symbol, order.avgPrice)}</div>
                      </div>
                      <div className="space-y-1">
                        <div className="text-muted-foreground">{t('field.qty')}</div>
                        <div className="font-mono">{fmtNum(order.quantity, 4)}</div>
                      </div>
                      <div className="space-y-1">
                        <div className="text-muted-foreground">{t('field.notional')}</div>
                        <div className="font-mono font-bold">${fmtNum(order.amount, 0)}</div>
                      </div>
                    </div>
                  </div>
                ))}
              </div>

              <div className="flex items-center justify-between px-4 py-3 border-t border-border/30">
                <span className="text-xs text-muted-foreground">
                  {t('pager.page', { page: result.current, pages: Math.max(result.pages, 1) })}
                </span>
                <div className="flex items-center gap-1">
                  <Button
                    variant="ghost"
                    size="sm"
                    className="h-8 w-8 p-0"
                    disabled={page <= 1}
                    onClick={() => setPage(prev => prev - 1)}
                  >
                    <ChevronLeft className="w-4 h-4" />
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="h-8 w-8 p-0"
                    disabled={page >= result.pages}
                    onClick={() => setPage(prev => prev + 1)}
                  >
                    <ChevronRight className="w-4 h-4" />
                  </Button>
                </div>
              </div>
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
