import { useCallback, useEffect, useMemo, useState } from 'react';
import { Activity, KeyRound, RefreshCcw } from 'lucide-react';
import { llmConfigApi, quantApi } from '../../api';
import { cn } from '../../lib/utils';
import { ChatPanel } from './ChatPanel';
import { AnalysisCard } from './AnalysisCard';
import { LlmConfigBall } from './LlmConfigBall';
import { chatStore } from './chatStore';
import type { LlmEndpointValue } from '../LlmEndpointForm';
import type { LlmConfigView, QuantDeepAnalysisView } from '../../types';

// 只展示 quant 实际监控的标的（WATCH_SYMBOLS=BTC/ETH）
const SYMBOLS = ['BTCUSDT', 'ETHUSDT'] as const;
const SYM_LABEL: Record<string, string> = { BTCUSDT: 'BTC', ETHUSDT: 'ETH' };
const REFRESH_MS = 60_000;

const fmtTime = (t: number) =>
  new Date(t).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });

/**
 * 研判工作台：左对话（Supervisor 调度）右深研判（详情 + 历史时间线）。
 * 预测/脆弱度展示已随预测管线下线（2026-08）；深研判为 chat 按需触发，60s 轮询足够。
 */
export function Workbench() {
  const [symbol, setSymbol] = useState<string>('BTCUSDT');
  const [analyses, setAnalyses] = useState<QuantDeepAnalysisView[]>([]);
  const [selected, setSelected] = useState<QuantDeepAnalysisView | null>(null);
  const [loading, setLoading] = useState(true);
  // 对话轨已全量 BYOK：三态——加载中不闪引导卡（否则每次进页面都先闪一下"去配置"）；
  // 配了 → 正常对话；没配 → 引导卡
  const [config, setConfig] = useState<LlmConfigView | null>(null);
  const [configLoaded, setConfigLoaded] = useState(false);

  const loadConfig = useCallback(() => {
    llmConfigApi.mine()
      .then(setConfig)
      .catch(() => setConfig(null))
      .finally(() => setConfigLoaded(true));
  }, []);

  useEffect(() => { loadConfig(); }, [loadConfig]);

  const hasConfig = config != null;
  // useMemo 不是优化，是正确性：LlmConfigBall 用 [open, initial] 做 effect 依赖重置表单，
  // 每帧新建对象会让用户刚打的字被反复清掉
  const formValue: LlmEndpointValue = useMemo(() => ({
    apiProtocol: config?.apiProtocol ?? 'openai',
    baseUrl: config?.baseUrl ?? '',
    model: config?.model ?? '',
    lightModel: config?.lightModel ?? '',
    reasoningEffort: config?.reasoningEffort ?? '',
    apiKey: '',   // 永远空着：key 明文不出服务端，改 key 才填
  }), [config]);

  const load = useCallback((sym: string) => {
    quantApi.analysisList(sym, 30)
      .then(list => setAnalyses(list))
      .catch(() => setAnalyses([]))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    setLoading(true);
    setSelected(null);
    load(symbol);
    const timer = setInterval(() => load(symbol), REFRESH_MS);
    return () => clearInterval(timer);
  }, [symbol, load]);

  const displayed = selected ?? analyses[0] ?? null;

  return (
    <>
    <div className="space-y-4">
      {/* 对话区（面板或引导卡）总是渲染，不再只给管理员。
          配置没回来前还不知道该渲染哪个，对话位空着，所以先单列 */}
      <div className={cn('grid gap-4 items-start', configLoaded && 'lg:grid-cols-2')}>
        {configLoaded && (hasConfig ? <ChatPanel /> : (
          <div className="rounded-lg pt-card py-14 flex flex-col items-center gap-3 text-center">
            <div className="w-12 h-12 rounded-full border border-border bg-background flex items-center justify-center text-muted-foreground/70">
              <KeyRound className="w-6 h-6" />
            </div>
            <div className="text-sm font-bold text-muted-foreground">用你自己的 API Key 开始对话</div>
            <div className="text-[11px] text-muted-foreground/70">
              对话走你配置的模型端点，费用记在你自己的账上，平台不经手
            </div>
            {/* 引导卡必须自带按钮：只写"点右下角的钥匙"等于让用户自己去找 */}
            <button type="button" onClick={() => chatStore.markNeedsConfig()}
                    className="mt-1 border border-border rounded-lg px-4 py-2 text-xs font-bold text-primary hover:bg-surface-hover">
              去配置
            </button>
          </div>
        ))}

        <div className="space-y-4">
          <div className="rounded-lg pt-card p-4 space-y-3">
            <div className="flex items-center gap-2">
              <Activity className="w-4 h-4 text-primary" />
              <span className="text-sm font-black">深研判</span>
              <div className="ml-auto flex items-center gap-1">
                {SYMBOLS.map(s => (
                  <button
                    key={s}
                    onClick={() => setSymbol(s)}
                    className={cn(
                      'text-[11px] font-bold px-2.5 py-1.5 sm:px-2 sm:py-1 rounded-lg transition-all',
                      symbol === s ? 'border border-border bg-card-2 text-primary' : 'border border-border text-muted-foreground hover:text-foreground',
                    )}
                  >
                    {SYM_LABEL[s]}
                  </button>
                ))}
                <button
                  onClick={() => load(symbol)}
                  className="border border-border hover:bg-surface-hover w-8 h-8 sm:w-7 sm:h-7 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary"
                  aria-label="刷新"
                >
                  <RefreshCcw className={cn('w-3.5 h-3.5', loading && 'animate-spin')} />
                </button>
              </div>
            </div>

            <AnalysisCard analysis={displayed} />
          </div>

          {/* 历史研判时间线：点击切换详情 */}
          {analyses.length > 1 && (
            <div className="rounded-lg pt-card p-4 space-y-2">
              <span className="microlabel">历史研判</span>
              <div className="space-y-1 max-h-72 overflow-y-auto">
                {analyses.map(a => (
                  <button
                    key={a.id}
                    onClick={() => setSelected(a)}
                    className={cn(
                      'w-full text-left rounded-md border px-3 py-2 text-xs leading-relaxed transition-all',
                      displayed?.id === a.id
                        ? 'border-primary/50 bg-card-2'
                        : 'border-border hover:bg-surface-hover',
                    )}
                  >
                    <span className="num text-muted-foreground mr-2">{fmtTime(a.closeTime)}</span>
                    {a.noDirection && (
                      <span className="text-[10px] font-bold px-1.5 py-0.5 rounded bg-amber-500/15 text-amber-600 mr-1">看不清</span>
                    )}
                    <span className="text-foreground">{a.narrative?.slice(0, 60)}{(a.narrative?.length ?? 0) > 60 ? '…' : ''}</span>
                  </button>
                ))}
              </div>
            </div>
          )}
        </div>
      </div>

    </div>

    {/* 必须待在 space-y-4 外面：那条规则给"非最后一个子节点"加 margin-block-end，
        弹窗一开，球就不再是最后一个，凭空吃到 16px 下边距——fixed 元素的 margin 照样生效，球会上跳 */}
    {configLoaded && (
      <LlmConfigBall
        configured={hasConfig}
        keyTail={config?.apiKeyTail}
        initial={formValue}
        onSaved={loadConfig}
      />
    )}
    </>
  );
}
