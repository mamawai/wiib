import { useState } from 'react';
import { KeyRound, Loader2, PlugZap, ScanSearch } from 'lucide-react';
import { cn } from '../lib/utils';
import { useToast } from './ui/use-toast';

/** 一条端点的表单值（与后端 LlmEndpointSaveRequest 同形） */
export interface LlmEndpointValue {
  /** 用户给的名字，下拉框里认它 */
  name: string;
  apiProtocol: string;
  baseUrl: string;
  model: string;
  /** 思考档位，空串=不传给上游走模型默认 */
  reasoningEffort: string;
  apiKey: string;
}

export interface LlmEndpointFormProps {
  value: LlmEndpointValue;
  onChange: (patch: Partial<LlmEndpointValue>) => void;
  /** 编辑已有端点：决定 key 是否必填、是否显示"留空=不换"。独立于 keyTail，见组件注释 */
  exists?: boolean;
  /** 已存在时显示的 key 尾号（纯展示） */
  keyTail?: string;
  /** 拉模型清单 */
  onDetect: () => Promise<string[]>;
  /** 连通性探测 */
  onTest: () => Promise<void>;
}

/** 空串=不传，与后端 normalizeEffort 的"留空一律 null"对齐 */
const EFFORT_OPTIONS: { value: string; label: string }[] = [
  { value: '', label: '默认' },
  { value: 'none', label: 'none' },
  { value: 'low', label: 'low' },
  { value: 'medium', label: 'medium' },
  { value: 'high', label: 'high' },
];

/**
 * LLM 端点表单（BYOK 端点库里的一条：名称 + 协议 + Base URL + 模型 + 思考档位 + key）。
 * detectModels 那段有个踩过的坑（见下）。
 *
 * exists 是独立 prop 而不是从 keyTail 推导：调用方常写
 * keyTail={exists ? mine?.apiKeyTail : undefined}，一旦 apiKeyTail 恰好是 undefined，
 * 推导出的 exists 就翻成 false，key 突然变必填、"留空=不换"提示消失。
 */
export function LlmEndpointForm({ value, onChange, exists, keyTail, onDetect, onTest }: LlmEndpointFormProps) {
  const { toast } = useToast();
  const [models, setModels] = useState<string[]>([]);
  const [detecting, setDetecting] = useState(false);
  const [testing, setTesting] = useState(false);

  // 不走调用方的通用 run() 包装：检测不能触发页面重载，
  // 否则会用库里旧配置冲掉表单里未保存的 baseUrl/key
  const detect = async () => {
    setDetecting(true);
    try {
      const list = await onDetect();
      setModels(list);
      toast(list.length ? `检测到 ${list.length} 个模型` : '端点未返回模型，可直接手输',
            list.length ? 'success' : 'error');
    } catch (e) {
      toast((e as Error).message || '检测失败', 'error');
    } finally {
      setDetecting(false);
    }
  };

  // 连通性探测独立成按钮：保存时不再顺带发一次真实 LLM 请求，
  // 想验通不通就点这里，不想验就直接存
  const test = async () => {
    setTesting(true);
    try {
      await onTest();
      toast('连接正常', 'success');
    } catch (e) {
      toast((e as Error).message || '连接失败', 'error');
    } finally {
      setTesting(false);
    }
  };

  return (
    <div className="space-y-3">
      <div className="grid sm:grid-cols-3 gap-3">
        <label className="space-y-1 text-xs">
          <span className="text-muted-foreground font-bold">名称</span>
          <input value={value.name} onChange={e => onChange({ name: e.target.value })}
                 placeholder="例：DeepSeek 主力 / Grok 轻量" maxLength={32}
                 className="w-full h-9 rounded-lg border border-border bg-card-2 px-3 text-xs" />
        </label>
        <label className="space-y-1 text-xs">
          <span className="text-muted-foreground font-bold">协议</span>
          <div className="flex gap-1.5">
            {['openai', 'responses'].map(p => (
              <button key={p} type="button" onClick={() => onChange({ apiProtocol: p })}
                      className={cn('flex-1 h-9 rounded-lg border text-xs font-bold',
                        value.apiProtocol === p
                          ? 'border-primary/60 bg-card-2 text-primary'
                          : 'border-border text-muted-foreground hover:text-foreground')}>
                {p}
              </button>
            ))}
          </div>
        </label>
        <label className="space-y-1 text-xs">
          <span className="text-muted-foreground font-bold">Base URL（不含 /v1 后缀）</span>
          <input value={value.baseUrl} onChange={e => onChange({ baseUrl: e.target.value })}
                 placeholder="https://api.deepseek.com"
                 className="w-full h-9 rounded-lg border border-border bg-card-2 px-3 text-xs num" />
        </label>
      </div>

      <div className="grid sm:grid-cols-2 gap-3">
        <label className="space-y-1 text-xs">
          <span className="text-muted-foreground font-bold">模型名</span>
          <div className="flex gap-1.5">
            <input value={value.model} onChange={e => onChange({ model: e.target.value })}
                   placeholder="deepseek-chat"
                   className="flex-1 min-w-0 h-9 rounded-lg border border-border bg-card-2 px-3 text-xs num" />
            <button type="button" onClick={() => void detect()}
                    disabled={detecting || !value.baseUrl.trim() || (!exists && !value.apiKey.trim())}
                    title="拉取该端点可用的模型清单（需先填 Base URL 和 Key）"
                    className="shrink-0 border border-border hover:bg-surface-hover rounded-lg px-2.5 h-9 text-xs font-bold text-primary flex items-center gap-1 disabled:opacity-50">
              {detecting ? <Loader2 className="w-3.5 h-3.5 animate-spin" />
                         : <ScanSearch className="w-3.5 h-3.5" />}
              检测
            </button>
          </div>
        </label>
        <label className="space-y-1 text-xs">
          <span className="text-muted-foreground font-bold flex items-center gap-1">
            <KeyRound className="w-3 h-3" /> API Key
            {exists && <span className="text-muted-foreground/70 font-normal">（当前尾号 {keyTail}，留空=不换）</span>}
          </span>
          <input value={value.apiKey} onChange={e => onChange({ apiKey: e.target.value })}
                 type="password"
                 placeholder={exists ? '留空保持不变' : 'sk-…（加密存储，只在服务端出网）'}
                 className="w-full h-9 rounded-lg border border-border bg-card-2 px-3 text-xs num" />
        </label>
      </div>

      <div className="space-y-1 text-xs">
        <span className="text-muted-foreground font-bold">思考档位</span>
        <div className="flex gap-1.5">
          {EFFORT_OPTIONS.map(o => (
            <button key={o.value} type="button" onClick={() => onChange({ reasoningEffort: o.value })}
                    className={cn('flex-1 h-9 rounded-lg border text-xs font-bold',
                      (value.reasoningEffort ?? '') === o.value
                        ? 'border-primary/60 bg-card-2 text-primary'
                        : 'border-border text-muted-foreground hover:text-foreground')}>
              {o.label}
            </button>
          ))}
        </div>
        {/* 模型支不支持这个参数查不到：协议的 /v1/models 只回 id/object/created/owned_by。
            传给不支持的模型各家表现不一致（有的忽略，OpenAI 官方直接 400），所以只能让用户自己试 */}
        <span className="text-[10px] text-muted-foreground/70 block">
          「默认」＝不传这个参数，用模型自己的默认行为。不是所有模型都认这个参数——
          认不认没法提前查出来，填完点「测试连通性」试一下最稳。
        </span>
      </div>

      <button type="button" onClick={() => void test()}
              disabled={testing || !value.baseUrl.trim() || !value.model.trim()
                        || (!exists && !value.apiKey.trim())}
              title="用当前填的配置真发一次请求，确认端点和模型可用"
              className="border border-border hover:bg-surface-hover rounded-lg px-2.5 h-9 text-xs font-bold text-primary flex items-center gap-1 disabled:opacity-50">
        {testing ? <Loader2 className="w-3.5 h-3.5 animate-spin" />
                 : <PlugZap className="w-3.5 h-3.5" />}
        测试连通性
      </button>

      {/* 检测到的模型清单：模型名输入即过滤，点击填入；网关不支持 /models 时照常手输 */}
      {models.length > 0 && (() => {
        const kw = value.model.trim().toLowerCase();
        const hits = models.filter(m => m.toLowerCase().includes(kw));
        return (
          <div className="max-h-28 overflow-y-auto flex flex-wrap gap-1.5 content-start rounded-lg border border-border bg-card-2/50 p-2">
            {hits.map(m => (
              <button key={m} type="button" onClick={() => onChange({ model: m })}
                      className={cn('px-2 h-7 rounded-md border text-[11px] num',
                        value.model === m
                          ? 'border-primary/60 bg-card-2 text-primary font-bold'
                          : 'border-border text-muted-foreground hover:text-foreground')}>
                {m}
              </button>
            ))}
            {hits.length === 0 && (
              <span className="text-[11px] text-muted-foreground px-1 py-1">
                清单里无匹配「{value.model.trim()}」，可直接手输
              </span>
            )}
          </div>
        );
      })()}
    </div>
  );
}
