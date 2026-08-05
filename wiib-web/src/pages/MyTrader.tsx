import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Bot, ChevronLeft, KeyRound, Loader2, Pause, Play, RotateCcw, Save } from 'lucide-react';
import { traderApi } from '../api';
import { useToast } from '../components/ui/use-toast';
import { cn } from '../lib/utils';
import type { TraderOwnerView, TraderUpsertRequest } from '../types';

const SYMBOL_OPTIONS = ['BTCUSDT', 'ETHUSDT', 'SOLUSDT', 'DOGEUSDT', 'XRPUSDT'];
const INTERVAL_OPTIONS = ['15m', '1h', '4h', '1d'];

const EMPTY_FORM: TraderUpsertRequest = {
  name: '', symbols: 'BTCUSDT', intervalCode: '1h', customPrompt: '',
  apiProtocol: 'openai', baseUrl: '', model: '', apiKey: '',
};

/**
 * 我的 Trader：创建/配置（BYOK key 只写不读，回显尾4位）+ 启停/重置。
 * 提示词存库即热生效——改完下一根K线自然按新提示词决策。
 */
export function MyTrader() {
  const { toast } = useToast();
  const [mine, setMine] = useState<TraderOwnerView | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [form, setForm] = useState<TraderUpsertRequest>(EMPTY_FORM);
  const [busy, setBusy] = useState<string | null>(null);

  const load = useCallback(() => {
    traderApi.mine().then(v => {
      setMine(v);
      if (v) {
        setForm({
          name: v.pub.name, symbols: v.pub.symbols, intervalCode: v.pub.intervalCode,
          customPrompt: v.customPrompt ?? '', apiProtocol: v.apiProtocol,
          baseUrl: v.baseUrl, model: v.pub.model, apiKey: '',
        });
      }
    }).finally(() => setLoaded(true));
  }, []);

  useEffect(() => { load(); }, [load]);

  const run = useCallback(async (name: string, action: () => Promise<unknown>, okMsg: string) => {
    setBusy(name);
    try {
      await action();
      toast(okMsg, 'success');
      load();
    } catch (e) {
      toast((e as Error).message || '操作失败', 'error');
    } finally {
      setBusy(null);
    }
  }, [toast, load]);

  const set = (patch: Partial<TraderUpsertRequest>) => setForm(f => ({ ...f, ...patch }));
  const toggleSymbol = (s: string) => {
    const cur = new Set(form.symbols.split(',').filter(Boolean));
    if (cur.has(s)) {
      cur.delete(s);
    } else {
      cur.add(s);
    }
    set({ symbols: [...cur].join(',') });
  };
  const selected = new Set(form.symbols.split(',').filter(Boolean));
  const exists = mine != null;
  const status = mine?.pub.status;

  if (!loaded) {
    return <div className="page-shell p-6 flex justify-center"><Loader2 className="w-5 h-5 animate-spin text-muted-foreground" /></div>;
  }

  return (
    <div className="page-shell p-4 md:p-6 space-y-4 max-w-3xl">
      <div className="flex items-center gap-2.5">
        <Link to="/arena" className="border border-border hover:bg-surface-hover w-8 h-8 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary" aria-label="返回竞技场">
          <ChevronLeft className="w-4 h-4" />
        </Link>
        <Bot className="w-5 h-5 text-primary" />
        <h1 className="text-lg font-black">我的 Trader</h1>
        {exists && (
          <span className={cn('text-[10px] font-bold px-1.5 py-0.5 rounded',
            status === 'RUNNING' ? 'bg-gain/15 text-gain' : status === 'LIQUIDATED' ? 'bg-loss/15 text-loss' : 'bg-amber-500/15 text-amber-600')}>
            {status === 'RUNNING' ? '运行中' : status === 'LIQUIDATED' ? '已爆仓' : '已暂停'}
          </span>
        )}
      </div>

      {mine?.pub.pausedReason && (
        <div className="rounded-lg border border-amber-500/40 bg-amber-500/10 px-4 py-2.5 text-xs text-amber-600 font-bold">
          {mine.pub.pausedReason}
        </div>
      )}

      {/* 运行控制 */}
      {exists && (
        <div className="rounded-lg pt-card p-4 flex items-center gap-2 flex-wrap">
          <span className="microlabel mr-2">运行控制</span>
          {status !== 'RUNNING' && status !== 'LIQUIDATED' && (
            <button onClick={() => void run('start', traderApi.start, '已启动，下一根K线开始唤醒')} disabled={busy != null}
                    className="border border-border hover:bg-surface-hover rounded-lg px-3 py-1.5 text-xs font-bold text-gain flex items-center gap-1.5 disabled:opacity-50">
              {busy === 'start' ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Play className="w-3.5 h-3.5" />} 启动
            </button>
          )}
          {status === 'RUNNING' && (
            <button onClick={() => void run('pause', traderApi.pause, '已暂停')} disabled={busy != null}
                    className="border border-border hover:bg-surface-hover rounded-lg px-3 py-1.5 text-xs font-bold text-amber-600 flex items-center gap-1.5 disabled:opacity-50">
              {busy === 'pause' ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Pause className="w-3.5 h-3.5" />} 暂停
            </button>
          )}
          <button
            onClick={() => { if (window.confirm(`重置将开新一局（R${(mine?.pub.roundNo ?? 1) + 1}）：新账户重新注资10000，历史战绩留档。确定？`)) void run('reset', traderApi.reset, '已重置开新一局'); }}
            disabled={busy != null}
            className="border border-border hover:bg-surface-hover rounded-lg px-3 py-1.5 text-xs font-bold text-muted-foreground flex items-center gap-1.5 disabled:opacity-50">
            {busy === 'reset' ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RotateCcw className="w-3.5 h-3.5" />} 重置开新局
          </button>
          {mine && (
            <Link to={`/arena/${mine.pub.id}`} className="ml-auto text-xs font-bold text-primary hover:underline">
              看它的决策时间线 →
            </Link>
          )}
        </div>
      )}

      {/* 配置表单 */}
      <div className="rounded-lg pt-card p-4 space-y-4">
        <span className="microlabel">{exists ? '配置（提示词改完下一根K线生效）' : '创建（连通性校验通过后开户注资 10000U）'}</span>

        <div className="grid sm:grid-cols-2 gap-3">
          <label className="space-y-1 text-xs">
            <span className="text-muted-foreground font-bold">名字</span>
            <input value={form.name} onChange={e => set({ name: e.target.value })} maxLength={32} placeholder="给你的 AI 起个名"
                   className="w-full h-9 rounded-lg border border-border bg-card-2 px-3 text-xs" />
          </label>
          <label className="space-y-1 text-xs">
            <span className="text-muted-foreground font-bold">唤醒K线级别</span>
            <div className="flex gap-1.5">
              {INTERVAL_OPTIONS.map(iv => (
                <button key={iv} type="button" onClick={() => set({ intervalCode: iv })}
                        className={cn('flex-1 h-9 rounded-lg border text-xs font-bold',
                          form.intervalCode === iv ? 'border-primary/60 bg-card-2 text-primary' : 'border-border text-muted-foreground hover:text-foreground')}>
                  {iv}
                </button>
              ))}
            </div>
          </label>
        </div>

        <div className="space-y-1 text-xs">
          <span className="text-muted-foreground font-bold">交易币种</span>
          <div className="flex gap-1.5 flex-wrap">
            {SYMBOL_OPTIONS.map(s => (
              <button key={s} type="button" onClick={() => toggleSymbol(s)}
                      className={cn('px-3 h-9 rounded-lg border text-xs font-bold',
                        selected.has(s) ? 'border-primary/60 bg-card-2 text-primary' : 'border-border text-muted-foreground hover:text-foreground')}>
                {s.replace('USDT', '')}
              </button>
            ))}
          </div>
        </div>

        <div className="grid sm:grid-cols-3 gap-3">
          <label className="space-y-1 text-xs">
            <span className="text-muted-foreground font-bold">协议</span>
            <div className="flex gap-1.5">
              {['openai', 'responses'].map(p => (
                <button key={p} type="button" onClick={() => set({ apiProtocol: p })}
                        className={cn('flex-1 h-9 rounded-lg border text-xs font-bold',
                          form.apiProtocol === p ? 'border-primary/60 bg-card-2 text-primary' : 'border-border text-muted-foreground hover:text-foreground')}>
                  {p}
                </button>
              ))}
            </div>
          </label>
          <label className="space-y-1 text-xs sm:col-span-2">
            <span className="text-muted-foreground font-bold">Base URL（不含 /v1 后缀）</span>
            <input value={form.baseUrl} onChange={e => set({ baseUrl: e.target.value })} placeholder="https://api.deepseek.com"
                   className="w-full h-9 rounded-lg border border-border bg-card-2 px-3 text-xs num" />
          </label>
        </div>

        <div className="grid sm:grid-cols-2 gap-3">
          <label className="space-y-1 text-xs">
            <span className="text-muted-foreground font-bold">模型名</span>
            <input value={form.model} onChange={e => set({ model: e.target.value })} placeholder="deepseek-chat"
                   className="w-full h-9 rounded-lg border border-border bg-card-2 px-3 text-xs num" />
          </label>
          <label className="space-y-1 text-xs">
            <span className="text-muted-foreground font-bold flex items-center gap-1">
              <KeyRound className="w-3 h-3" /> API Key
              {exists && <span className="text-muted-foreground/70 font-normal">（当前尾号 {mine?.apiKeyTail}，留空=不换）</span>}
            </span>
            <input value={form.apiKey} onChange={e => set({ apiKey: e.target.value })} type="password"
                   placeholder={exists ? '留空保持不变' : 'sk-…（加密存储，只在服务端出网）'}
                   className="w-full h-9 rounded-lg border border-border bg-card-2 px-3 text-xs num" />
          </label>
        </div>

        <label className="space-y-1 text-xs block">
          <span className="text-muted-foreground font-bold">自定义提示词（追加在平台系统提示词之后）</span>
          <textarea value={form.customPrompt ?? ''} onChange={e => set({ customPrompt: e.target.value })} rows={6} maxLength={4000}
                    placeholder="例：只做趋势突破，不抄底不摸顶；单笔风险不超过权益2%；连亏两笔后本日只观望。"
                    className="w-full rounded-lg border border-border bg-card-2 px-3 py-2 text-xs leading-relaxed" />
        </label>

        <button
          onClick={() => void run('save',
            () => exists ? traderApi.updateConfig(form) : traderApi.create(form),
            exists ? '配置已保存' : '创建成功！去启动它吧')}
          disabled={busy != null}
          className="border border-border hover:bg-surface-hover rounded-lg px-4 py-2 text-xs font-black text-primary flex items-center gap-1.5 disabled:opacity-50">
          {busy === 'save' ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Save className="w-3.5 h-3.5" />}
          {exists ? '保存配置' : '创建 Trader'}
        </button>

        <p className="text-[10px] leading-relaxed text-muted-foreground border-l-2 border-border pl-2.5">
          规则：每根 K 线唤醒一次，你的 key 你的 token 钱；硬护栏平台管（杠杆≤20、单笔保证金≤权益50%、强制止损）；
          模拟盘不涉真实资金，竞技场决策日志公开可见。别指望它赚钱——看它怎么想才是重点。
        </p>
      </div>
    </div>
  );
}
