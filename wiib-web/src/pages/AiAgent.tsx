import { useState, useCallback } from 'react';
import type { ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';
import { aiAgentApi } from '../api';
import { useToast } from '../components/ui/use-toast';
import { Button } from '../components/ui/button';
import { MarketPanel } from '../components/workbench/MarketPanel';
import { ModelConfig } from '../components/ModelConfig';
import { cn } from '../lib/utils';
import {
  BarChart3, Bomb, Brain, BrainCircuit, CheckCircle2, Coins, Dices, Gem,
  KeyRound, Rocket, ShieldAlert, Target, User, Zap,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { BehaviorAnalysisReport } from '../types';

const TABS = ['market', 'behavior', 'config'] as const;
type Tab = typeof TABS[number];

const RISK_TONE: Record<string, string> = {
  HIGH: 'bg-loss/15 text-loss',
  MEDIUM: 'bg-warning/15 text-warning',
  LOW: 'bg-gain/15 text-gain',
};

/** 拟物区块卡：浮起面板 + 主色图标标题，全页统一节奏。 */
function SectionCard({ icon: Icon, title, children }: { icon: LucideIcon; title: string; children: ReactNode }) {
  return (
    <div className="rounded-lg pt-card p-4 sm:p-5 space-y-3">
      <div className="flex items-center gap-2 text-sm font-black">
        <Icon className="w-4 h-4 text-primary" /> {title}
      </div>
      {children}
    </div>
  );
}

/** 类别块：边框分组 + 2×2 指标网格（数值在上、标签退后，替代"标签: 值"挤行）。 */
function CategoryBlock({ icon: Icon, title, children }: { icon: LucideIcon; title: string; children: ReactNode }) {
  return (
    <div className="rounded-md border border-border bg-card p-3">
      <div className="text-[11px] font-black text-muted-foreground flex items-center gap-1.5 mb-2.5">
        <Icon className="w-3.5 h-3.5" /> {title}
      </div>
      <div className="grid grid-cols-2 gap-x-3 gap-y-2.5">{children}</div>
    </div>
  );
}

function Metric({ label, value, tone }: { label: string; value: ReactNode; tone?: 'gain' | 'loss' }) {
  return (
    <div className="min-w-0">
      <div className={cn('text-[13px] font-bold tabular-nums truncate', tone === 'gain' && 'text-gain', tone === 'loss' && 'text-loss')}>
        {value}
      </div>
      <div className="text-[10px] text-muted-foreground mt-0.5">{label}</div>
    </div>
  );
}

/** 带符号盈亏文本 + 颜色 tone */
function pnl(v: number): { text: string; tone: 'gain' | 'loss' } {
  return { text: `${v >= 0 ? '+' : ''}${v.toFixed(2)}`, tone: v >= 0 ? 'gain' : 'loss' };
}

export function AiAgent() {
  const { toast } = useToast();
  // Tab 落 URL（?tab=config 供对话气泡的"去配置"直达）；非法值当默认 market
  const [searchParams, setSearchParams] = useSearchParams();
  const rawTab = searchParams.get('tab') as Tab | null;
  const tab: Tab = rawTab && TABS.includes(rawTab) ? rawTab : 'market';
  const setTab = useCallback((t: Tab) => {
    setSearchParams(t === 'market' ? {} : { tab: t }, { replace: true });
  }, [setSearchParams]);
  const [behaviorLoading, setBehaviorLoading] = useState(false);
  const [behaviorReport, setBehaviorReport] = useState<BehaviorAnalysisReport | null>(null);

  const handleAnalyzeBehavior = useCallback(async () => {
    setBehaviorLoading(true);
    try {
      const report = await aiAgentApi.analyzeBehavior() as unknown as BehaviorAnalysisReport;
      setBehaviorReport(report);
      toast('分析完成', 'success');
    } catch (e: unknown) {
      toast((e as Error).message || '分析失败', 'error');
    } finally {
      setBehaviorLoading(false);
    }
  }, [toast]);

  return (
    // 内容全是单列窄块（对话拆走后没有宽布局了），整页居中一个 3xl 列，不然全贴左边
    <div className="page-shell p-4 md:p-6">
      <div className="max-w-3xl mx-auto space-y-4">
      <div className="rounded-lg border border-border bg-card px-4 py-2.5 flex items-center gap-2.5 text-primary text-xs font-bold">
        <Zap className="w-4 h-4 shrink-0" />
        投资有风险，当前分析结果仅供参考不构成任何建议
      </div>

      {/* Tab：内凹滑槽 + 浮起选中块（拟物分段控件）。对话已拆去全站悬浮气泡（ChatDock） */}
      <div className="border border-border bg-card-2 rounded-lg p-1 flex">
        {([
          { key: 'market', icon: BrainCircuit, label: '市场研判' },
          { key: 'behavior', icon: User, label: '行为分析' },
          { key: 'config', icon: KeyRound, label: '模型配置' },
        ] as { key: Tab; icon: LucideIcon; label: string }[]).map(({ key, icon: Icon, label }) => (
          <button
            key={key}
            onClick={() => setTab(key)}
            className={cn(
              'flex-1 flex items-center justify-center gap-2 py-2 rounded-lg text-sm font-bold transition-all',
              tab === key ? 'bg-card border border-border bg-background text-primary' : 'text-muted-foreground hover:text-foreground',
            )}
          >
            <Icon className="w-4 h-4" />
            {label}
          </button>
        ))}
      </div>

      {/* 市场研判：最新深研判数据视图（对话在气泡里，历史时间线已删） */}
      {tab === 'market' && <MarketPanel />}

      {/* 模型配置（BYOK）：对话 agent + 交易员 agent 两份端点 */}
      {tab === 'config' && <ModelConfig />}

      {/* 行为分析（behavior agent） */}
      {tab === 'behavior' && (
        <div className="space-y-4">
          {!behaviorReport || !behaviorReport.overview ? (
            <div className="rounded-lg pt-card p-8 sm:p-12 text-center">
              <div className={cn(
                'w-16 h-16 rounded-2xl border border-border bg-primary/10 flex items-center justify-center mx-auto mb-5',
                behaviorLoading && 'animate-pulse',
              )}>
                <Brain className="w-8 h-8 text-primary" />
              </div>
              {!behaviorLoading ? (
                <>
                  <h2 className="text-lg font-black mb-2">用户行为分析</h2>
                  <p className="text-sm text-muted-foreground mb-6 max-w-md mx-auto leading-relaxed">
                    基于你的全部历史交易数据、游戏记录、风险偏好等维度，全面分析你的投资行为特征
                  </p>
                  <Button onClick={handleAnalyzeBehavior} size="lg">开始分析</Button>
                </>
              ) : (
                <h2 className="text-lg font-black">正在分析中...</h2>
              )}
            </div>
          ) : (
            <>
              {/* 概览 */}
              <SectionCard icon={BarChart3} title="资产概览">
                <div className="grid grid-cols-2 gap-2.5">
                  <div className="rounded-md border border-border bg-card-2 px-3.5 py-3">
                    <div className="text-xl sm:text-2xl font-black tabular-nums truncate leading-tight">
                      ${behaviorReport.overview.totalAssets.toLocaleString()}
                    </div>
                    <div className="text-[10px] text-muted-foreground mt-1">总资产</div>
                  </div>
                  <div className="rounded-md border border-border bg-card-2 px-3.5 py-3">
                    <div className={cn('text-xl sm:text-2xl font-black tabular-nums leading-tight',
                      behaviorReport.overview.totalProfitPct >= 0 ? 'text-gain' : 'text-loss')}>
                      {behaviorReport.overview.totalProfitPct >= 0 ? '+' : ''}{behaviorReport.overview.totalProfitPct.toFixed(2)}%
                    </div>
                    <div className="text-[10px] text-muted-foreground mt-1">总收益率</div>
                  </div>
                </div>

                {behaviorReport.overview.distribution.length > 0 && (
                  <div>
                    <div className="text-[10px] font-bold text-muted-foreground mb-2">资产分布</div>
                    <div className="flex flex-wrap gap-2">
                      {behaviorReport.overview.distribution.map((d, i) => (
                        <span key={i} className="border border-border rounded-full px-2.5 py-1 text-[11px] font-bold tabular-nums">
                          <span className="text-muted-foreground">{d.category}</span> ${d.value.toLocaleString()}
                        </span>
                      ))}
                    </div>
                  </div>
                )}
              </SectionCard>

              {/* 交易行为 */}
              <SectionCard icon={Coins} title="交易行为分析">
                <div className="grid sm:grid-cols-2 gap-2.5">
                  {behaviorReport.tradeBehavior.crypto.positionCount > 0 && (
                    <CategoryBlock icon={Coins} title="加密货币">
                      <Metric label="持仓" value={behaviorReport.tradeBehavior.crypto.positionCount} />
                      <Metric label="杠杆" value={behaviorReport.tradeBehavior.crypto.leverageUsage} />
                      <Metric label="买入" value={`$${behaviorReport.tradeBehavior.crypto.totalBuyAmount.toLocaleString()}`} />
                      <Metric label="卖出" value={`$${behaviorReport.tradeBehavior.crypto.totalSellAmount.toLocaleString()}`} />
                    </CategoryBlock>
                  )}
                  {behaviorReport.tradeBehavior.bstock.positionCount > 0 && (
                    <CategoryBlock icon={BarChart3} title="美股 bStock">
                      <Metric label="持仓" value={behaviorReport.tradeBehavior.bstock.positionCount} />
                      <Metric label="买入" value={`$${behaviorReport.tradeBehavior.bstock.totalBuyAmount.toLocaleString()}`} />
                      <Metric label="卖出" value={`$${behaviorReport.tradeBehavior.bstock.totalSellAmount.toLocaleString()}`} />
                    </CategoryBlock>
                  )}
                  {behaviorReport.tradeBehavior.futures.orderCount > 0 && (
                    <CategoryBlock icon={Rocket} title="永续合约">
                      <Metric label="订单" value={behaviorReport.tradeBehavior.futures.orderCount} />
                      <Metric label="方向" value={behaviorReport.tradeBehavior.futures.direction} />
                      <Metric label="平仓盈亏" {...(() => { const p = pnl(behaviorReport.tradeBehavior.futures.realizedPnl); return { value: p.text, tone: p.tone }; })()} />
                      <Metric label="平均杠杆" value={`${behaviorReport.tradeBehavior.futures.avgLeverage}x`} />
                      {(['crypto', 'commodity', 'tradfi'] as const).map(cat => {
                        const c = behaviorReport.tradeBehavior.futures.byCategory?.[cat];
                        if (!c || c.orderCount <= 0) return null;
                        const p = pnl(c.realizedPnl);
                        const label = cat === 'crypto' ? '加密盈亏' : cat === 'commodity' ? '大宗盈亏' : '美股盈亏';
                        return <Metric key={cat} label={label} value={p.text} tone={p.tone} />;
                      })}
                    </CategoryBlock>
                  )}
                  {behaviorReport.tradeBehavior.prediction.frequency > 0 && (
                    <CategoryBlock icon={Target} title="预测交易">
                      <Metric label="频率" value={`${behaviorReport.tradeBehavior.prediction.frequency}次`} />
                      <Metric label="胜率" value={`${behaviorReport.tradeBehavior.prediction.winRate}%`} />
                      <Metric label="净盈亏" {...(() => { const p = pnl(behaviorReport.tradeBehavior.prediction.netProfit); return { value: p.text, tone: p.tone }; })()} />
                      <Metric label="偏好" value={behaviorReport.tradeBehavior.prediction.directionPreference} />
                    </CategoryBlock>
                  )}
                </div>
              </SectionCard>

              {/* 游戏行为 */}
              {behaviorReport.gameBehavior && (
                <SectionCard icon={Dices} title="游戏行为分析">
                  <div className="grid sm:grid-cols-2 gap-2.5">
                    {behaviorReport.gameBehavior.blackjack.totalHands > 0 && (
                      <CategoryBlock icon={Dices} title="Blackjack">
                        <Metric label="局数" value={behaviorReport.gameBehavior.blackjack.totalHands} />
                        <Metric label="最大赢" value={`$${behaviorReport.gameBehavior.blackjack.biggestWin}`} />
                        <Metric label="胜" value={behaviorReport.gameBehavior.blackjack.totalWon} tone="gain" />
                        <Metric label="负" value={behaviorReport.gameBehavior.blackjack.totalLost} tone="loss" />
                      </CategoryBlock>
                    )}
                    {behaviorReport.gameBehavior.mines.frequency > 0 && (
                      <CategoryBlock icon={Bomb} title="矿工游戏">
                        <Metric label="频率" value={`${behaviorReport.gameBehavior.mines.frequency}次`} />
                        <Metric label="净盈亏" {...(() => { const p = pnl(behaviorReport.gameBehavior.mines.netProfit); return { value: p.text, tone: p.tone }; })()} />
                      </CategoryBlock>
                    )}
                    {behaviorReport.gameBehavior.videoPoker.frequency > 0 && (
                      <CategoryBlock icon={Gem} title="视频扑克">
                        <Metric label="频率" value={`${behaviorReport.gameBehavior.videoPoker.frequency}次`} />
                        <Metric label="净盈亏" {...(() => { const p = pnl(behaviorReport.gameBehavior.videoPoker.netProfit); return { value: p.text, tone: p.tone }; })()} />
                      </CategoryBlock>
                    )}
                  </div>
                </SectionCard>
              )}

              {/* 风险画像 */}
              <SectionCard icon={ShieldAlert} title="风险画像">
                <div className="flex items-center gap-4 flex-wrap">
                  <span className={cn('text-xs font-black px-3 py-1 rounded-full',
                    RISK_TONE[behaviorReport.riskProfile.riskLevel] || RISK_TONE.LOW)}>
                    {behaviorReport.riskProfile.riskLevel}
                  </span>
                  <Metric label="爆仓次数" value={behaviorReport.riskProfile.bankruptCount} />
                  <Metric label="最大回撤" value={behaviorReport.riskProfile.maxDrawdown} />
                </div>
              </SectionCard>

              {/* 建议 */}
              {behaviorReport.suggestions.length > 0 && (
                <SectionCard icon={CheckCircle2} title="个性化建议">
                  <ul className="space-y-2">
                    {behaviorReport.suggestions.map((s, i) => (
                      <li key={i} className="flex items-start gap-2.5 rounded-md border border-border bg-card px-3 py-2.5 text-sm leading-relaxed">
                        <CheckCircle2 className="w-4 h-4 text-gain shrink-0 mt-0.5" />
                        {s}
                      </li>
                    ))}
                  </ul>
                </SectionCard>
              )}

              <Button variant="outline" onClick={() => setBehaviorReport(null)} className="mb-4">重新分析</Button>
            </>
          )}
        </div>
      )}
      </div>
    </div>
  );
}
