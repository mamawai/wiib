import { Activity } from 'lucide-react';
import { Card, CardContent, CardHeader, CardTitle } from './ui/card';
import { Skeleton } from './ui/skeleton';

export interface TradeItem {
  id: string;
  orderSide: string;
  name: string;
  quantity: number | string;
  unit: string;
  filledAmount?: number;
  createdAt: string;
}

interface Props {
  trades: TradeItem[];
  loading: boolean;
}

function formatTime(dateStr: string) {
  const d = new Date(dateStr);
  return `${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
}

function formatAmount(n?: number) {
  if (!n) return '-';
  return n >= 10000 ? `${(n / 10000).toFixed(2)}万` : n.toFixed(2);
}

export function LatestTradesCard({ trades, loading }: Props) {
  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="flex items-center gap-2 text-base">
          <div className="p-1.5 rounded-lg bg-blue-500/10">
            <Activity className="w-4 h-4 text-blue-500" />
          </div>
          最新成交
        </CardTitle>
      </CardHeader>
      <CardContent className="p-0">
        {loading ? (
          <div className="space-y-0">
            {Array.from({ length: 5 }).map((_, i) => (
              <div key={i} className="flex justify-between items-center p-3 border-b border-border last:border-b-0">
                <Skeleton className="h-4 w-24" />
                <Skeleton className="h-4 w-16" />
              </div>
            ))}
          </div>
        ) : trades.length > 0 ? (
          <div className="max-h-80 overflow-y-auto">
            {trades.map((t) => (
              <div key={t.id} className="flex items-center justify-between px-4 py-2.5 border-b border-border last:border-b-0 text-sm">
                <div className="flex items-center gap-2 min-w-0">
                  <span className={`text-xs font-medium px-1.5 py-0.5 rounded ${t.orderSide === 'BUY' ? 'bg-red-500/10 text-red-500' : 'bg-green-500/10 text-green-500'}`}>
                    {t.orderSide === 'BUY' ? '买' : '卖'}
                  </span>
                  <span className="font-medium truncate">{t.name}</span>
                  <span className="text-muted-foreground text-xs">{t.quantity}{t.unit}</span>
                </div>
                <div className="flex items-center gap-3 shrink-0">
                  <span className="text-muted-foreground text-xs">{formatAmount(t.filledAmount)}</span>
                  <span className="text-muted-foreground text-xs w-14 text-right">{formatTime(t.createdAt)}</span>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <div className="p-8 text-center text-muted-foreground">暂无成交</div>
        )}
      </CardContent>
    </Card>
  );
}
