import { Activity, Bot, ChevronRight } from 'lucide-react';
import { Link } from 'react-router-dom';
import { Card, CardContent, CardHeader, CardTitle } from './ui/card';
import { Skeleton } from './ui/skeleton';
import { fmtDateTime } from '../lib/utils';

export interface TradeItem {
  id: string;
  orderSide: string;
  sideLabel?: string;
  sideTone?: 'buy' | 'sell';
  name: string;
  quantity: number | string;
  unit: string;
  filledAmount?: number;
  createdAt: string;
  isAi?: boolean;
}

interface Props {
  trades: TradeItem[];
  loading: boolean;
}

export function LatestTradesCard({ trades, loading }: Props) {
  return (
    <Card>
      <CardHeader className="pb-2">
        <div className="flex items-center justify-between gap-2">
          <CardTitle className="flex items-center gap-2 text-sm">
            <div className="p-1 rounded-md bg-primary/10">
              <Activity className="w-3.5 h-3.5 text-primary" />
            </div>
            最新成交
          </CardTitle>
          {/* 这里只给最新 20 条，全量分页在 /trades（交易者匿名） */}
          <Link
            to="/trades"
            className="inline-flex items-center gap-0.5 text-xs font-medium text-muted-foreground hover:text-primary transition-colors"
          >
            更多
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
            {trades.map((t) => {
              const tone = t.sideTone ?? (t.orderSide === 'BUY' ? 'buy' : 'sell');
              const sideLabel = t.sideLabel ?? (tone === 'buy' ? '买' : '卖');
              return (
                <div key={t.id} className="flex items-center justify-between gap-2 px-3 py-2.5 border-b border-border/20 last:border-b-0 text-sm">
                  <div className="flex items-center gap-2 min-w-0">
                    <span className={`text-xs font-medium px-1.5 py-0.5 rounded shrink-0 ${tone === 'buy' ? 'bg-gain/10 text-gain' : 'bg-loss/10 text-loss'}`}>
                      {sideLabel}
                    </span>
                    {t.isAi && (
                      <span title="AI交易员" className="inline-flex items-center justify-center w-5 h-5 rounded-md bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 shrink-0">
                        <Bot className="w-3 h-3" />
                      </span>
                    )}
                    <span className="font-medium truncate">{t.name}</span>
                    <span className="text-muted-foreground text-xs truncate">{t.quantity}{t.unit}</span>
                  </div>
                  <span className="text-muted-foreground text-xs shrink-0">{fmtDateTime(t.createdAt)}</span>
                </div>
              );
            })}
          </div>
        ) : (
          <div className="p-8 text-center text-muted-foreground">暂无成交</div>
        )}
      </CardContent>
    </Card>
  );
}
