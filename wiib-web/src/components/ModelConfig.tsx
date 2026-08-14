import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Bot, Loader2, MessagesSquare, Rocket } from 'lucide-react';
import { llmConfigApi, traderApi } from '../api';
import { LlmEndpointForm, type LlmEndpointValue } from './LlmEndpointForm';
import { Button } from './ui/button';
import { useToast } from './ui/use-toast';
import { cn } from '../lib/utils';
import type { LlmConfigView, TraderOwnerView, TraderUpsertRequest } from '../types';

const EMPTY: LlmEndpointValue = {
  apiProtocol: 'openai', baseUrl: '', model: '', lightModel: '', reasoningEffort: '', apiKey: '',
};

/** 配置状态徽标：已配置亮尾号，未配置挂警示灯 */
function StatusChip({ keyTail }: { keyTail?: string }) {
  return keyTail ? (
    <span className="text-[10px] font-bold px-2 py-0.5 rounded-full bg-primary/10 text-primary num">
      已配置 · 尾号 {keyTail}
    </span>
  ) : (
    <span className="text-[10px] font-bold px-2 py-0.5 rounded-full bg-warning/10 text-warning flex items-center gap-1.5">
      <span className="led led-warn" /> 未配置
    </span>
  );
}

/**
 * 模型配置（BYOK）：对话 agent 与交易员 agent 两份端点各一张卡。
 * 行为分析不在这里——它走平台管理员配的模型，不烧用户的 key。
 */
export function ModelConfig() {
  const { toast } = useToast();

  // ===== 对话 agent =====
  const [chatCfg, setChatCfg] = useState<LlmConfigView | null>(null);
  const [chatLoaded, setChatLoaded] = useState(false);
  const [chatForm, setChatForm] = useState<LlmEndpointValue>(EMPTY);
  const [chatSaving, setChatSaving] = useState(false);

  const loadChat = useCallback(() => {
    llmConfigApi.mine()
      .then(c => {
        setChatCfg(c);
        setChatForm({
          apiProtocol: c?.apiProtocol ?? 'openai',
          baseUrl: c?.baseUrl ?? '',
          model: c?.model ?? '',
          lightModel: c?.lightModel ?? '',
          reasoningEffort: c?.reasoningEffort ?? '',
          apiKey: '',   // 明文 key 不出服务端，改 key 才填
        });
      })
      .catch(() => setChatCfg(null))
      .finally(() => setChatLoaded(true));
  }, []);

  // ===== 交易员 agent =====
  const [trader, setTrader] = useState<TraderOwnerView | null>(null);
  const [traderLoaded, setTraderLoaded] = useState(false);
  const [traderForm, setTraderForm] = useState<LlmEndpointValue>(EMPTY);
  const [traderSaving, setTraderSaving] = useState(false);

  const loadTrader = useCallback(() => {
    traderApi.mine()
      .then(t => {
        setTrader(t);
        if (t) {
          setTraderForm({
            apiProtocol: t.apiProtocol, baseUrl: t.baseUrl, model: t.pub.model, apiKey: '',
          });
        }
      })
      .catch(() => setTrader(null))
      .finally(() => setTraderLoaded(true));
  }, []);

  useEffect(() => { loadChat(); loadTrader(); }, [loadChat, loadTrader]);

  const saveChat = async () => {
    setChatSaving(true);
    try {
      await llmConfigApi.save(chatForm);
      toast('对话模型配置已保存', 'success');
      loadChat();
    } catch (e) {
      toast((e as Error).message || '保存失败', 'error');
    } finally {
      setChatSaving(false);
    }
  };

  /** trader 没有独立的 LLM 保存接口：现拉一份最新全量配置，只换端点四项后整体 PUT，
      避免用进页面时的旧快照冲掉用户刚在「我的交易员」里改的其他字段 */
  const saveTrader = async () => {
    setTraderSaving(true);
    try {
      const t = await traderApi.mine();
      if (!t) throw new Error('交易员不存在，请先创建');
      const req: TraderUpsertRequest = {
        name: t.pub.name, symbols: t.pub.symbols, intervalCode: t.pub.intervalCode,
        customPrompt: t.customPrompt ?? '', useDefaultPrompt: t.useDefaultPrompt, spec: t.spec,
        alertEnabled: t.alertEnabled, alertThresholdMult: t.alertThresholdMult,
        reviewEnabled: t.reviewEnabled, learningEnabled: t.learningEnabled,
        apiProtocol: traderForm.apiProtocol, baseUrl: traderForm.baseUrl,
        model: traderForm.model, apiKey: traderForm.apiKey,
      };
      await traderApi.updateConfig(req);
      toast('交易员模型配置已保存', 'success');
      loadTrader();
    } catch (e) {
      toast((e as Error).message || '保存失败', 'error');
    } finally {
      setTraderSaving(false);
    }
  };

  const chatExists = chatCfg != null;

  return (
    <div className="space-y-4">
      {/* 对话 agent */}
      <div className="rounded-lg pt-card p-4 sm:p-5 space-y-3">
        <div className="flex items-center gap-2 flex-wrap">
          <MessagesSquare className="w-4 h-4 text-primary" />
          <span className="text-sm font-black">对话 agent</span>
          {chatLoaded && <StatusChip keyTail={chatCfg?.apiKeyTail} />}
        </div>
        <p className="text-[11px] text-muted-foreground">
          研判对话与对话里触发的深研判走这份配置，费用记在你自己的账上，平台不经手。
        </p>
        {!chatLoaded ? (
          <div className="flex items-center gap-2 py-8 justify-center text-xs text-muted-foreground">
            <Loader2 className="w-4 h-4 animate-spin" /> 加载配置...
          </div>
        ) : (
          <>
            <LlmEndpointForm
              value={chatForm}
              onChange={patch => setChatForm(f => ({ ...f, ...patch }))}
              exists={chatExists}
              keyTail={chatCfg?.apiKeyTail}
              withLightModel
              withReasoningEffort
              onDetect={() => llmConfigApi.listModels(chatForm)}
              onTest={() => llmConfigApi.test(chatForm)}
            />
            <div className="flex justify-end">
              <Button size="sm" disabled={chatSaving} onClick={() => void saveChat()}>
                {chatSaving && <Loader2 className="w-3.5 h-3.5 animate-spin mr-1" />}
                保存
              </Button>
            </div>
          </>
        )}
      </div>

      {/* 交易员 agent */}
      <div className="rounded-lg pt-card p-4 sm:p-5 space-y-3">
        <div className="flex items-center gap-2 flex-wrap">
          <Rocket className="w-4 h-4 text-primary" />
          <span className="text-sm font-black">交易员 agent</span>
          {traderLoaded && trader && <StatusChip keyTail={trader.apiKeyTail} />}
        </div>
        {!traderLoaded ? (
          <div className="flex items-center gap-2 py-8 justify-center text-xs text-muted-foreground">
            <Loader2 className="w-4 h-4 animate-spin" /> 加载配置...
          </div>
        ) : trader ? (
          <>
            <p className="text-[11px] text-muted-foreground">
              「{trader.pub.name}」每次唤醒和每日复盘烧这份 key。唤醒频率、币种、仓位规格等在
              <Link to="/my-trader" className="text-primary font-bold hover:underline mx-0.5">我的交易员</Link>
              里改。
            </p>
            <LlmEndpointForm
              value={traderForm}
              onChange={patch => setTraderForm(f => ({ ...f, ...patch }))}
              exists
              keyTail={trader.apiKeyTail}
              onDetect={() => traderApi.listModels({
                apiProtocol: traderForm.apiProtocol, baseUrl: traderForm.baseUrl, apiKey: traderForm.apiKey,
              })}
            />
            <div className="flex justify-end">
              <Button size="sm" disabled={traderSaving} onClick={() => void saveTrader()}>
                {traderSaving && <Loader2 className="w-3.5 h-3.5 animate-spin mr-1" />}
                保存
              </Button>
            </div>
          </>
        ) : (
          <div className={cn('rounded-lg border border-border bg-card-2 py-8 px-4',
            'flex flex-col items-center gap-2.5 text-center')}>
            <Bot className="w-8 h-8 text-muted-foreground/50" />
            <p className="text-xs text-muted-foreground">还没有 AI 交易员——创建时一并配置它的模型端点</p>
            <Link
              to="/my-trader"
              className="border border-border rounded-lg px-4 py-2 text-xs font-bold text-primary hover:bg-surface-hover"
            >
              去创建交易员
            </Link>
          </div>
        )}
      </div>

      <p className="text-[10px] text-muted-foreground/70 px-1">
        行为分析由平台模型提供，不使用你的 key。
      </p>
    </div>
  );
}
