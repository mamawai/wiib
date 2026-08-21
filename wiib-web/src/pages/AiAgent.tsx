import { useState, useCallback } from 'react';
import type { ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { aiAgentApi } from '../api';
import { useToast } from '../components/ui/use-toast';
import { Button } from '../components/ui/button';
import { ModelConfig } from '../components/ModelConfig';
import { cn } from '../lib/utils';
import {
  BarChart3, Bomb, Brain, CheckCircle2, Coins, Dices, Gem,
  KeyRound, Rocket, ShieldAlert, Target, User, Zap,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { BehaviorAnalysisReport } from '../types';

const TABS = ['behavior', 'config'] as const;
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
  const { t } = useTranslation('ai');
  // Tab 落 URL；默认落在模型配置（BYOK 总配置在这儿，进来先看到它）；?tab=behavior 看行为分析。非法值（含已下线的 market）当默认
  const [searchParams, setSearchParams] = useSearchParams();
  const rawTab = searchParams.get('tab') as Tab | null;
  const tab: Tab = rawTab && TABS.includes(rawTab) ? rawTab : 'config';
  const setTab = useCallback((t: Tab) => {
    setSearchParams(t === 'config' ? {} : { tab: t }, { replace: true });
  }, [setSearchParams]);
  const [behaviorLoading, setBehaviorLoading] = useState(false);
  const [behaviorReport, setBehaviorReport] = useState<BehaviorAnalysisReport | null>(null);

  const handleAnalyzeBehavior = useCallback(async () => {
    setBehaviorLoading(true);
    try {
      const report = await aiAgentApi.analyzeBehavior() as unknown as BehaviorAnalysisReport;
      setBehaviorReport(report);
      toast(t('toast.analyzeDone'), 'success');
    } catch (e: unknown) {
      toast((e as Error).message || t('toast.analyzeFailed'), 'error');
    } finally {
      setBehaviorLoading(false);
    }
  }, [toast, t]);

  return (
    // 内容全是单列窄块（对话拆走后没有宽布局了），整页居中一个 3xl 列，不然全贴左边
    <div className="page-shell p-4 md:p-6">
      <div className="max-w-3xl mx-auto space-y-4">
      <div className="rounded-lg border border-border bg-card px-4 py-2.5 flex items-center gap-2.5 text-primary text-xs font-bold">
        <Zap className="w-4 h-4 shrink-0" />
        {t('disclaimer')}
      </div>

      {/* Tab：内凹滑槽 + 浮起选中块（拟物分段控件）。对话已拆去全站悬浮气泡（ChatDock），
          市场研判 tab 已下线（研判只在对话里触发时看，全站共享旧数据的展示没有价值） */}
      <div className="border border-border bg-card-2 rounded-lg p-1 flex">
        {([
          { key: 'config', icon: KeyRound, label: t('tab.config') },
          { key: 'behavior', icon: User, label: t('tab.behavior') },
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
                  <h2 className="text-lg font-black mb-2">{t('behavior.title')}</h2>
                  <p className="text-sm text-muted-foreground mb-6 max-w-md mx-auto leading-relaxed">
                    {t('behavior.intro')}
                  </p>
                  <Button onClick={handleAnalyzeBehavior} size="lg">{t('behavior.start')}</Button>
                </>
              ) : (
                <h2 className="text-lg font-black">{t('behavior.analyzing')}</h2>
              )}
            </div>
          ) : (
            <>
              {/* 概览 */}
              <SectionCard icon={BarChart3} title={t('behavior.overview')}>
                <div className="grid grid-cols-2 gap-2.5">
                  <div className="rounded-md border border-border bg-card-2 px-3.5 py-3">
                    <div className="text-xl sm:text-2xl font-black tabular-nums truncate leading-tight">
                      ${behaviorReport.overview.totalAssets.toLocaleString()}
                    </div>
                    <div className="text-[10px] text-muted-foreground mt-1">{t('behavior.totalAssets')}</div>
                  </div>
                  <div className="rounded-md border border-border bg-card-2 px-3.5 py-3">
                    <div className={cn('text-xl sm:text-2xl font-black tabular-nums leading-tight',
                      behaviorReport.overview.totalProfitPct >= 0 ? 'text-gain' : 'text-loss')}>
                      {behaviorReport.overview.totalProfitPct >= 0 ? '+' : ''}{behaviorReport.overview.totalProfitPct.toFixed(2)}%
                    </div>
                    <div className="text-[10px] text-muted-foreground mt-1">{t('behavior.totalReturn')}</div>
                  </div>
                </div>

                {behaviorReport.overview.distribution.length > 0 && (
                  <div>
                    <div className="text-[10px] font-bold text-muted-foreground mb-2">{t('behavior.distribution')}</div>
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
              <SectionCard icon={Coins} title={t('behavior.tradeTitle')}>
                <div className="grid sm:grid-cols-2 gap-2.5">
                  {behaviorReport.tradeBehavior.crypto.positionCount > 0 && (
                    <CategoryBlock icon={Coins} title={t('behavior.crypto')}>
                      <Metric label={t('behavior.positions')} value={behaviorReport.tradeBehavior.crypto.positionCount} />
                      <Metric label={t('behavior.leverage')} value={behaviorReport.tradeBehavior.crypto.leverageUsage} />
                      <Metric label={t('behavior.buy')} value={`$${behaviorReport.tradeBehavior.crypto.totalBuyAmount.toLocaleString()}`} />
                      <Metric label={t('behavior.sell')} value={`$${behaviorReport.tradeBehavior.crypto.totalSellAmount.toLocaleString()}`} />
                    </CategoryBlock>
                  )}
                  {behaviorReport.tradeBehavior.bstock.positionCount > 0 && (
                    <CategoryBlock icon={BarChart3} title={t('behavior.bstock')}>
                      <Metric label={t('behavior.positions')} value={behaviorReport.tradeBehavior.bstock.positionCount} />
                      <Metric label={t('behavior.buy')} value={`$${behaviorReport.tradeBehavior.bstock.totalBuyAmount.toLocaleString()}`} />
                      <Metric label={t('behavior.sell')} value={`$${behaviorReport.tradeBehavior.bstock.totalSellAmount.toLocaleString()}`} />
                    </CategoryBlock>
                  )}
                  {behaviorReport.tradeBehavior.futures.orderCount > 0 && (
                    <CategoryBlock icon={Rocket} title={t('behavior.futures')}>
                      <Metric label={t('behavior.orders')} value={behaviorReport.tradeBehavior.futures.orderCount} />
                      <Metric label={t('behavior.direction')} value={behaviorReport.tradeBehavior.futures.direction} />
                      <Metric label={t('behavior.realizedPnl')} {...(() => { const p = pnl(behaviorReport.tradeBehavior.futures.realizedPnl); return { value: p.text, tone: p.tone }; })()} />
                      <Metric label={t('behavior.avgLeverage')} value={`${behaviorReport.tradeBehavior.futures.avgLeverage}x`} />
                      {(['crypto', 'commodity', 'tradfi'] as const).map(cat => {
                        const c = behaviorReport.tradeBehavior.futures.byCategory?.[cat];
                        if (!c || c.orderCount <= 0) return null;
                        const p = pnl(c.realizedPnl);
                        const label = cat === 'crypto' ? t('behavior.cryptoPnl')
                          : cat === 'commodity' ? t('behavior.commodityPnl') : t('behavior.stockPnl');
                        return <Metric key={cat} label={label} value={p.text} tone={p.tone} />;
                      })}
                    </CategoryBlock>
                  )}
                  {behaviorReport.tradeBehavior.prediction.frequency > 0 && (
                    <CategoryBlock icon={Target} title={t('behavior.prediction')}>
                      <Metric label={t('behavior.frequency')} value={t('behavior.times', { count: behaviorReport.tradeBehavior.prediction.frequency })} />
                      <Metric label={t('behavior.winRate')} value={`${behaviorReport.tradeBehavior.prediction.winRate}%`} />
                      <Metric label={t('behavior.netPnl')} {...(() => { const p = pnl(behaviorReport.tradeBehavior.prediction.netProfit); return { value: p.text, tone: p.tone }; })()} />
                      <Metric label={t('behavior.preference')} value={behaviorReport.tradeBehavior.prediction.directionPreference} />
                    </CategoryBlock>
                  )}
                </div>
              </SectionCard>

              {/* 游戏行为 */}
              {behaviorReport.gameBehavior && (
                <SectionCard icon={Dices} title={t('behavior.gameTitle')}>
                  <div className="grid sm:grid-cols-2 gap-2.5">
                    {behaviorReport.gameBehavior.blackjack.totalHands > 0 && (
                      <CategoryBlock icon={Dices} title="Blackjack">
                        <Metric label={t('behavior.hands')} value={behaviorReport.gameBehavior.blackjack.totalHands} />
                        <Metric label={t('behavior.biggestWin')} value={`$${behaviorReport.gameBehavior.blackjack.biggestWin}`} />
                        <Metric label={t('behavior.won')} value={behaviorReport.gameBehavior.blackjack.totalWon} tone="gain" />
                        <Metric label={t('behavior.lost')} value={behaviorReport.gameBehavior.blackjack.totalLost} tone="loss" />
                      </CategoryBlock>
                    )}
                    {behaviorReport.gameBehavior.mines.frequency > 0 && (
                      <CategoryBlock icon={Bomb} title={t('behavior.mines')}>
                        <Metric label={t('behavior.frequency')} value={t('behavior.times', { count: behaviorReport.gameBehavior.mines.frequency })} />
                        <Metric label={t('behavior.netPnl')} {...(() => { const p = pnl(behaviorReport.gameBehavior.mines.netProfit); return { value: p.text, tone: p.tone }; })()} />
                      </CategoryBlock>
                    )}
                    {behaviorReport.gameBehavior.videoPoker.frequency > 0 && (
                      <CategoryBlock icon={Gem} title={t('behavior.videoPoker')}>
                        <Metric label={t('behavior.frequency')} value={t('behavior.times', { count: behaviorReport.gameBehavior.videoPoker.frequency })} />
                        <Metric label={t('behavior.netPnl')} {...(() => { const p = pnl(behaviorReport.gameBehavior.videoPoker.netProfit); return { value: p.text, tone: p.tone }; })()} />
                      </CategoryBlock>
                    )}
                  </div>
                </SectionCard>
              )}

              {/* 风险画像 */}
              <SectionCard icon={ShieldAlert} title={t('behavior.riskProfile')}>
                <div className="flex items-center gap-4 flex-wrap">
                  <span className={cn('text-xs font-black px-3 py-1 rounded-full',
                    RISK_TONE[behaviorReport.riskProfile.riskLevel] || RISK_TONE.LOW)}>
                    {behaviorReport.riskProfile.riskLevel}
                  </span>
                  <Metric label={t('behavior.bankruptCount')} value={behaviorReport.riskProfile.bankruptCount} />
                  <Metric label={t('behavior.maxDrawdown')} value={behaviorReport.riskProfile.maxDrawdown} />
                </div>
              </SectionCard>

              {/* 建议 */}
              {behaviorReport.suggestions.length > 0 && (
                <SectionCard icon={CheckCircle2} title={t('behavior.suggestions')}>
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

              <Button variant="outline" onClick={() => setBehaviorReport(null)} className="mb-4">{t('behavior.reanalyze')}</Button>
            </>
          )}
        </div>
      )}
      </div>
    </div>
  );
}
