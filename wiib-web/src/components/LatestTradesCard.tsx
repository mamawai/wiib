import { Activity, Bot, ChevronRight } from 'lucide-react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Card, CardContent, CardHeader, CardTitle } from './ui/card';
import { Skeleton } from './ui/skeleton';
import { fmtDateTime } from '../lib/utils';
import { orderSideView } from '../lib/orderSide';

/**
 * 一条成交要的最少信息：只装后端原始值，方向标签和展示名一律留到渲染期算。
 * 存译好的字会把文案冻在拉数那一刻，之后切语言这批卡片就不跟着变了。
 */
export interface TradeItem {
  id: string;
  /** 后端原始方向枚举（BUY/SELL/OPEN_LONG/...），渲染时交给 orderSideView 现算标签与配色 */
  orderSide: string;
  /** 去掉 USDT 的标的符号，既当展示名也当数量单位 */
  base: string;
  /** 合约单：展示名要带「合约」后缀，现货不带 */
  isFutures?: boolean;
  quantity: number | string;
  filledAmount?: number;
  createdAt: string;
  isAi?: boolean;
}

interface Props {
  trades: TradeItem[];
  loading: boolean;
}

export function LatestTradesCard({ trades, loading }: Props) {
  // 「更多」全站通用，直接复用 common，不在本 ns 再写一份
  const { t } = useTranslation(['home', 'common']);

  return (
    <Card>
      <CardHeader className="pb-2">
        <div className="flex items-center justify-between gap-2">
          <CardTitle className="flex items-center gap-2 text-sm">
            <div className="p-1 rounded-md bg-primary/10">
              <Activity className="w-3.5 h-3.5 text-primary" />
            </div>
            {t('trades.title')}
          </CardTitle>
          {/* 这里只给最新 20 条，全量分页在 /trades（交易者匿名） */}
          <Link
            to="/trades"
            className="inline-flex items-center gap-0.5 text-xs font-medium text-muted-foreground hover:text-primary transition-colors"
          >
            {t('common:more')}
            <ChevronRight className="w-3.5 h-3.5" />
          </Link>
        </div>
      </CardHeader>
      <CardContent className="p-0">
        {loading ? (
          <div className="space-y-0">
            {Array.from({ length: 5 }).map((_, i) => (
              <div key={i} className="flex justify-between items-center p-3 border-b border-border/20 last:border-b-0">
                <Skeleton className="h-4 w-24" />
                <Skeleton className="h-4 w-16" />
              </div>
            ))}
          </div>
        ) : trades.length > 0 ? (
          // 紧凑列（首页 2:1 布局的 1/3 位）：方向 + 币种 + 数量 + 时间。
          // 金额这一列是窄卡里最放不下的，砍掉 —— 全量含金额去 /trades
          <div className="max-h-96 overflow-y-auto">
            {trades.map((item) => {
              // 方向标签渲染期现算：本组件订阅了 useTranslation，切语言会重渲染，这里就跟着重查一遍词表
              const { label: sideLabel, tone } = orderSideView(item.orderSide);
              const name = item.isFutures ? t('trades.futuresName', { base: item.base }) : item.base;
              return (
                <div key={item.id} className="flex items-center justify-between gap-2 px-3 py-2.5 border-b border-border/20 last:border-b-0 text-sm">
                  <div className="flex items-center gap-2 min-w-0">
                    <span className={`text-xs font-medium px-1.5 py-0.5 rounded shrink-0 ${tone === 'buy' ? 'bg-gain/10 text-gain' : 'bg-loss/10 text-loss'}`}>
                      {sideLabel}
                    </span>
                    {item.isAi && (
                      <span title={t('trades.aiTrader')} className="inline-flex items-center justify-center w-5 h-5 rounded-md bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 shrink-0">
                        <Bot className="w-3 h-3" />
                      </span>
                    )}
                    <span className="font-medium truncate">{name}</span>
                    <span className="text-muted-foreground text-xs truncate">{item.quantity}{item.base}</span>
                  </div>
                  <span className="text-muted-foreground text-xs shrink-0">{fmtDateTime(item.createdAt)}</span>
                </div>
              );
            })}
          </div>
        ) : (
          <div className="p-8 text-center text-muted-foreground">{t('trades.empty')}</div>
        )}
      </CardContent>
    </Card>
  );
}
