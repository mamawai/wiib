import { useState, useEffect } from 'react';
import { rankingApi } from '../api';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Badge } from '../components/ui/badge';
import { Skeleton } from '../components/ui/skeleton';
import { cn } from '../lib/utils';
import { Trophy, Medal, TrendingUp, TrendingDown, Clock } from 'lucide-react';
import type { RankingItem } from '../types';

export function Ranking() {
  const [ranking, setRanking] = useState<RankingItem[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    rankingApi.list()
      .then(setRanking)
      .catch(() => setRanking([]))
      .finally(() => setLoading(false));
  }, []);

  if (loading) {
    return (
      <div className="max-w-2xl mx-auto p-4 space-y-4">
        <Skeleton className="h-8 w-32" />
        {[...Array(10)].map((_, i) => (
          <Skeleton key={i} className="h-16 w-full" />
        ))}
      </div>
    );
  }

  return (
    <div className="max-w-2xl mx-auto p-4 space-y-4">
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2">
            <div className="p-1.5 rounded-lg bg-amber-500/10">
              <Trophy className="w-5 h-5 text-amber-500" />
            </div>
            资产排行榜
          </CardTitle>
          <div className="flex items-center gap-1 text-xs text-muted-foreground">
            <Clock className="w-3 h-3" />
            交易时段每10分钟更新
          </div>
        </CardHeader>
        <CardContent>
          {ranking.length === 0 ? (
            <div className="py-8 text-center text-muted-foreground">
              暂无排行数据
            </div>
          ) : (
            <div className="space-y-2">
              {ranking.map((item) => (
                <RankingRow key={item.userId} item={item} />
              ))}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}

function RankingRow({ item }: { item: RankingItem }) {
  const isProfit = item.profitPct >= 0;

  const rankIcon = item.rank === 1 ? (
    <div className="w-8 h-8 rounded-full bg-amber-500 flex items-center justify-center">
      <Trophy className="w-4 h-4 text-white" />
    </div>
  ) : item.rank === 2 ? (
    <div className="w-8 h-8 rounded-full bg-slate-400 flex items-center justify-center">
      <Medal className="w-4 h-4 text-white" />
    </div>
  ) : item.rank === 3 ? (
    <div className="w-8 h-8 rounded-full bg-amber-700 flex items-center justify-center">
      <Medal className="w-4 h-4 text-white" />
    </div>
  ) : (
    <div className="w-8 h-8 rounded-full bg-muted flex items-center justify-center text-sm font-medium text-muted-foreground">
      {item.rank}
    </div>
  );

  return (
    <div className="flex items-center gap-3 p-3 rounded-lg bg-muted/30 hover:bg-muted/50 transition-colors">
      {rankIcon}

      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          {item.avatar ? (
            <img src={item.avatar} alt="" className="w-6 h-6 rounded-full" />
          ) : (
            <div className="w-6 h-6 rounded-full bg-primary/10 flex items-center justify-center text-xs font-medium">
              {item.username.charAt(0).toUpperCase()}
            </div>
          )}
          <span className="font-medium truncate">{item.username}</span>
        </div>
      </div>

      <div className="text-right">
        <div className="text-sm font-medium tabular-nums">
          {item.totalAssets.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
        </div>
        <Badge
          variant="secondary"
          className={cn(
            "text-xs gap-0.5",
            isProfit ? "text-red-500" : "text-green-500"
          )}
        >
          {isProfit ? <TrendingUp className="w-3 h-3" /> : <TrendingDown className="w-3 h-3" />}
          {isProfit ? '+' : ''}{item.profitPct.toFixed(2)}%
        </Badge>
      </div>
    </div>
  );
}
