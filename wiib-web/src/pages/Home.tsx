import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { stockApi, buffApi } from '../api';
import { StockCard } from '../components/StockCard';
import { DailyBuffCard } from '../components/DailyBuffCard';
import { Card, CardContent, CardHeader, CardTitle } from '../components/ui/card';
import { Button } from '../components/ui/button';
import { Skeleton } from '../components/ui/skeleton';
import { Dialog, DialogHeader, DialogContent, DialogFooter } from '../components/ui/dialog';
import { useToast } from '../components/ui/use-toast';
import { TrendingUp, TrendingDown, LineChart, RefreshCcw, Bell, Gift, Settings } from 'lucide-react';
import type { Stock, BuffStatus } from '../types';
import { useDedupedEffect } from '../hooks/useDedupedEffect';
import { useUserStore } from '../stores/userStore';

const HIDE_NOTICE_KEY = 'wiib-notice-hide-date';

function shouldShowNotice(): boolean {
  const hideDate = localStorage.getItem(HIDE_NOTICE_KEY);
  if (!hideDate) return true;
  const today = new Date().toDateString();
  return hideDate !== today;
}

function hideNoticeToday() {
  localStorage.setItem(HIDE_NOTICE_KEY, new Date().toDateString());
}

function StockCardSkeleton() {
  return (
    <div className="flex justify-between items-center p-4 border-b border-border last:border-b-0">
      <div className="flex flex-col gap-2">
        <Skeleton className="h-4 w-20" />
        <Skeleton className="h-3 w-16" />
      </div>
      <div className="flex flex-col items-end gap-2">
        <Skeleton className="h-5 w-16" />
        <Skeleton className="h-3 w-24" />
      </div>
    </div>
  );
}

export function Home() {
  const navigate = useNavigate();
  const { toast } = useToast();
  const { user, token } = useUserStore();
  const isLoggedIn = !!token && !!user;
  const [gainers, setGainers] = useState<Stock[]>([]);
  const [losers, setLosers] = useState<Stock[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshNonce, setRefreshNonce] = useState(0);
  const [noticeOpen, setNoticeOpen] = useState(false);
  const [buffStatus, setBuffStatus] = useState<BuffStatus | null>(null);
  const requestKey = `home:gainers-losers:limit=5:refresh=${refreshNonce}`;

  // 首次加载时检查是否需要显示公告
  useEffect(() => {
    if (shouldShowNotice()) {
      setNoticeOpen(true);
    }
  }, []);

  // 加载Buff状态
  useEffect(() => {
    if (isLoggedIn) {
      buffApi.status().then(setBuffStatus).catch(() => {});
    } else {
      setBuffStatus(null);
    }
  }, [isLoggedIn, refreshNonce]);

  const handleHideToday = () => {
    hideNoticeToday();
    setNoticeOpen(false);
  };

  useDedupedEffect(
    requestKey,
    () => {
      let cancelled = false;

      Promise.all([stockApi.gainers(5), stockApi.losers(5)])
        .then(([g, l]) => {
          if (cancelled) return;
          setGainers(g || []);
          setLosers(l || []);
        })
        .catch(() => {
          if (cancelled) return;
          setGainers([]);
          setLosers([]);
          toast('获取行情失败', 'error', { description: '请稍后重试' });
        })
        .finally(() => {
          if (cancelled) return;
          setLoading(false);
        });

      return () => {
        cancelled = true;
      };
    },
    [requestKey],
  );

  return (
    <div className="max-w-4xl mx-auto p-4 space-y-6">
      {/* Welcome Banner */}
      <Card className="bg-gradient-to-br from-primary/10 via-primary/5 to-transparent border-primary/20">
        <CardContent className="p-6">
          <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
            <div className="flex items-center gap-3">
              <div className="p-3 rounded-xl bg-primary/20">
                <LineChart className="w-6 h-6 text-primary" />
              </div>
              <div>
                <h2 className="text-lg font-semibold">模拟股票交易</h2>
                <p className="text-sm text-muted-foreground">看看如果当初买了会怎样</p>
              </div>
            </div>

            <div className="flex flex-wrap gap-2">
              {user?.id === 1 && (
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={() => navigate('/admin')}
                  className="text-muted-foreground hover:text-foreground"
                >
                  <Settings className="w-4 h-4" />
                </Button>
              )}
              <Button
                variant="ghost"
                size="sm"
                onClick={() => setNoticeOpen(true)}
                className="text-amber-500 hover:text-amber-400 hover:bg-amber-500/10"
              >
                <Bell className="w-4 h-4" />
              </Button>
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  setRefreshNonce((n) => n + 1);
                  toast('已刷新', 'info');
                }}
              >
                <RefreshCcw className="w-4 h-4" />
                刷新
              </Button>
              {isLoggedIn && (
                <Button
                  size="sm"
                  variant={buffStatus?.canDraw ? 'default' : 'secondary'}
                  onClick={() => {
                    const el = document.getElementById('daily-buff-card');
                    el?.scrollIntoView({ behavior: 'smooth' });
                  }}
                >
                  <Gift className="w-4 h-4" />
                  {buffStatus?.canDraw ? '每日抽奖' : '今日已抽'}
                </Button>
              )}
            </div>
          </div>
        </CardContent>
      </Card>

      {/* 每日福利 */}
      {isLoggedIn && (
        <DailyBuffCard
          status={buffStatus}
          onDrawn={() => buffApi.status().then(setBuffStatus)}
        />
      )}

      {/* Gainers */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-base">
            <div className="p-1.5 rounded-lg bg-red-500/10">
              <TrendingUp className="w-4 h-4 text-red-500" />
            </div>
            涨幅榜
          </CardTitle>
        </CardHeader>
        <CardContent className="p-0">
          {loading ? (
            <>
              <StockCardSkeleton />
              <StockCardSkeleton />
              <StockCardSkeleton />
            </>
          ) : gainers.length > 0 ? (
            gainers.map((stock) => (
              <StockCard
                key={stock.id}
                stock={stock}
                onClick={() => navigate(`/stock/${stock.id}`)}
              />
            ))
          ) : (
            <div className="p-8 text-center text-muted-foreground">
              暂无数据
            </div>
          )}
        </CardContent>
      </Card>

      {/* Losers */}
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="flex items-center gap-2 text-base">
            <div className="p-1.5 rounded-lg bg-green-500/10">
              <TrendingDown className="w-4 h-4 text-green-500" />
            </div>
            跌幅榜
          </CardTitle>
        </CardHeader>
        <CardContent className="p-0">
          {loading ? (
            <>
              <StockCardSkeleton />
              <StockCardSkeleton />
              <StockCardSkeleton />
            </>
          ) : losers.length > 0 ? (
            losers.map((stock) => (
              <StockCard
                key={stock.id}
                stock={stock}
                onClick={() => navigate(`/stock/${stock.id}`)}
              />
            ))
          ) : (
            <div className="p-8 text-center text-muted-foreground">
              暂无数据
            </div>
          )}
        </CardContent>
      </Card>

      {/* 公告弹窗 */}
      <Dialog open={noticeOpen} onClose={() => setNoticeOpen(false)} className="max-w-xl">
        <DialogHeader>
          <h2 className="text-xl font-bold flex items-center gap-2">
            <Bell className="w-5 h-5 text-amber-500" />
            玩法说明
          </h2>
        </DialogHeader>
        <DialogContent>
          <div className="space-y-4 text-sm leading-relaxed">
            <section>
              <h3 className="font-semibold text-base mb-2 text-primary">欢迎</h3>
              <p className="text-muted-foreground">
                虚拟股票交易模拟器，体验"如果当初买了会怎样"。所有数据均为模拟。
              </p>
            </section>

            <section className="bg-amber-500/10 p-3 rounded-lg border border-amber-500/20">
              <p className="text-amber-600 dark:text-amber-400 text-xs">
                  ⚠️ 仅供娱乐，不构成投资建议。杠杆有风险，可能爆仓。
              </p>
            </section>

            <section>
              <h3 className="font-semibold text-base mb-2 text-primary">交易时间</h3>
              <p className="text-muted-foreground">周一至周五 9:30-11:30、13:00-15:00，每10秒更新行情</p>
            </section>

            <section>
              <h3 className="font-semibold text-base mb-2 text-primary">股票交易</h3>
              <ul className="list-disc list-inside text-muted-foreground space-y-1">
                <li><strong>市价单</strong>：当前价立即成交</li>
                <li><strong>限价单</strong>：设定价格，触发后成交（当日有效）</li>
                <li><strong>手续费</strong>：0.05%，最低5元</li>
                <li><strong>T+1结算</strong>：卖出后资金24小时到账</li>
                <li><strong>初始资金</strong>：100,000元</li>
              </ul>
            </section>

            <section>
              <h3 className="font-semibold text-base mb-2 text-primary">杠杆交易</h3>
              <ul className="list-disc list-inside text-muted-foreground space-y-1">
                <li>市价买入可选1-10倍杠杆</li>
                <li>借款按日计息0.05%</li>
                <li>爆仓：资产低于借款时自动清仓，次日9:00恢复</li>
              </ul>
            </section>

            <section>
              <h3 className="font-semibold text-base mb-2 text-primary">期权交易</h3>
              <ul className="list-disc list-inside text-muted-foreground space-y-1">
                <li><strong>CALL看涨</strong>：涨得越多赚得越多</li>
                <li><strong>PUT看跌</strong>：跌得越多赚得越多</li>
                <li>仅支持买入开仓，到期前可平仓或等待结算</li>
                <li>现金结算，不涉及股票交割</li>
              </ul>
            </section>

            <section>
              <h3 className="font-semibold text-base mb-2 text-primary">每日福利</h3>
              <p className="text-muted-foreground">每天可抽一次，有机会获得红包、股票或交易折扣券</p>
            </section>
          </div>
        </DialogContent>
        <DialogFooter>
          <Button variant="ghost" size="sm" onClick={handleHideToday}>
            今日不再显示
          </Button>
          <Button size="sm" onClick={() => setNoticeOpen(false)}>
            我知道了
          </Button>
        </DialogFooter>
      </Dialog>
    </div>
  );
}
