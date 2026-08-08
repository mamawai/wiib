import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Bot, Check, ChevronLeft, GraduationCap, KeyRound, Loader2, Pause, Play, RotateCcw, Save, ScanSearch, X } from 'lucide-react';
import { traderApi } from '../api';
import { GuidedTour, type TourStep } from '../components/GuidedTour';
import { useCryptoStream } from '../hooks/useCryptoStream';
import { useToast } from '../components/ui/use-toast';
import { cn, fmtNum } from '../lib/utils';
import type { TraderOwnerView, TraderRequestView, TraderSpec, TraderUpsertRequest } from '../types';

const TOUR_SEEN_KEY = 'wiib-trader-tour-seen';

/** 配置引导：讲清楚"这是什么 + 配大配小会怎样 + 和别的字段怎么互相咬" */
const TOUR_STEPS: TourStep[] = [
  {
    target: 'name',
    title: '给它起个名字',
    body: '这个名字会公开显示在竞技场排行榜上，别人看到的就是它。最多 32 个字。',
  },
  {
    target: 'interval',
    title: '多久醒一次',
    body: '每根这个级别的 K 线收盘时，AI 被唤醒一次，看行情、做决定。\n'
      + '两次唤醒之间它是"睡着"的，但止损止盈单会照常自动触发。\n\n'
      + '选 15m 起步。5m 意味着一天叫醒它 288 次——你的 API key 在烧钱，双边手续费也在磨损本金。',
  },
  {
    target: 'symbols',
    title: '让它交易哪些币',
    body: '白名单。AI 只能在这几个币里做，开别的会被当场拒绝。\n\n'
      + '给得越多它越容易分心，也越容易同时开一堆仓。刚开始建议只给 1~2 个。',
  },
  {
    target: 'leverage',
    title: '杠杆区间：必须从中选一个',
    body: '这是区间不是上限——配 50~100，AI 想用 20 倍也会被拒，必须落在 50 以上。\n\n'
      + '它无权觉得"太高了"而自作主张调低。你定多少就是多少。\n\n'
      + '注意：交易所按仓位名义价值分档限制杠杆，仓开大了高杠杆会被拒。',
  },
  {
    target: 'margin',
    title: '每笔用多少钱开仓',
    body: '按当前权益的百分比算。权益 10000U、配 10%，那每笔新仓的保证金就是 1000U。\n\n'
      + '同样是区间，AI 必须落在里面。下面那行小字会实时算出名义价值，配之前先看一眼。\n\n'
      + '只管开新仓——加仓多大是另一回事，看后面两步。',
  },
  {
    target: 'position-rules',
    title: '能开几个仓 / 能不能对锁',
    body: '关掉「多仓位」＝全账户同时只能有一个仓，AI 自己挑哪个币。未成交的挂单也占坑，'
      + '否则它先挂三个单就绕过去了。\n\n'
      + '「多空双开」是同一个币能不能同时持多单和空单。关掉可以防它自己跟自己对冲、白交两遍手续费。'
      + '单仓模式下这项自动失效——双开本身就需要两个仓位。',
  },
  {
    target: 'self-manage',
    title: '加仓减仓要不要问你',
    body: '关掉某一项，AI 想做时不会直接成交，而是发一条请求给你，出现在本页顶部。\n\n'
      + '关键是它不会卡住——请求发完这轮就继续跑，等你有空再点同意或叉掉。卡片上会同时给'
      + '"它发起时的价格"和"现在的实时价格"，跑没跑掉你自己判断。\n\n'
      + '默认加仓放开、减仓要问。止损止盈单不受影响，永远自动执行，所以风险始终有保护。',
  },
  {
    target: 'byok',
    title: '你的模型，你的账单',
    body: '平台不提供模型。填你自己的 API 端点和 key——OpenAI 兼容或 Responses 协议都行。\n\n'
      + 'key 加密存库、只回显尾 4 位，改配置时留空表示不换。点「检测」可以拉出该端点支持的模型清单。\n\n'
      + '每次唤醒都在花你的 token，唤醒频率越高账单越厚。',
  },
  {
    target: 'prompt',
    title: '交易风格指令',
    body: '平台系统提示词已经写好了角色、纪律和硬性规则，可以展开看全文——和真正喂给 AI 的一字不差。\n\n'
      + '你写的自定义指令追加在它后面，用来定风格（"只做突破不抄底"这类）。'
      + '风格与策略上与平台默认冲突时，以你写的为准；仓位规格和硬性规则除外，那由系统强制执行。\n\n'
      + '存库即生效，下一根 K 线就按新的来，不用重启。',
  },
  {
    target: 'save',
    title: '保存，然后启动',
    body: '首次保存会做连通性校验，通过后开一个独立模拟账户注资 10000U。\n\n'
      + '保存只是存配置，还得回到上面点「启动」它才会开始被唤醒。爆仓或想重来时用「重置开新局」，'
      + '历史战绩会留档。',
  },
];

const SYMBOL_OPTIONS = ['BTCUSDT', 'ETHUSDT', 'SOLUSDT', 'DOGEUSDT', 'XRPUSDT'];
const INTERVAL_OPTIONS = ['5m', '15m', '1h', '4h'];
/** 波动哨兵每币基准阈值%（平台下限，只能经系数调高）——与后端 VolatilitySentinel 同一份数字 */
const ALERT_BASE: Record<string, number> = { BTCUSDT: 0.6, ETHUSDT: 0.8, XRPUSDT: 0.8, SOLUSDT: 0.9, DOGEUSDT: 1.0 };

const DEFAULT_SPEC: TraderSpec = {
  leverageMin: 3, leverageMax: 20, marginPctMin: 5, marginPctMax: 20,
  allowMultiPosition: true, allowHedge: false, allowSelfAdd: true, allowSelfReduce: false,
};

// 默认 15m 起步：5m 高频唤醒对"LLM+双边taker手续费"是绞肉机，保留仅为短期测试观察
const EMPTY_FORM: TraderUpsertRequest = {
  name: '', symbols: 'BTCUSDT', intervalCode: '15m', customPrompt: '',
  apiProtocol: 'openai', baseUrl: '', model: '', apiKey: '', useDefaultPrompt: true,
  spec: DEFAULT_SPEC, alertEnabled: true, alertThresholdMult: 1, reviewEnabled: true,
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
  const [models, setModels] = useState<string[]>([]);
  const [detecting, setDetecting] = useState(false);
  const [template, setTemplate] = useState('');
  const [requests, setRequests] = useState<TraderRequestView[]>([]);
  const [tour, setTour] = useState(false);

  const loadRequests = useCallback(() => {
    traderApi.requests().then(setRequests).catch(() => setRequests([]));
  }, []);

  const load = useCallback(() => {
    traderApi.mine().then(v => {
      setMine(v);
      if (v) {
        setForm({
          name: v.pub.name, symbols: v.pub.symbols, intervalCode: v.pub.intervalCode,
          customPrompt: v.customPrompt ?? '', apiProtocol: v.apiProtocol,
          baseUrl: v.baseUrl, model: v.pub.model, apiKey: '', useDefaultPrompt: v.useDefaultPrompt,
          spec: v.spec ?? DEFAULT_SPEC,
          alertEnabled: v.alertEnabled ?? true, alertThresholdMult: v.alertThresholdMult ?? 1,
          reviewEnabled: v.reviewEnabled ?? true,
        });
        loadRequests();
      }
    }).finally(() => setLoaded(true));
  }, [loadRequests]);

  useEffect(() => { load(); }, [load]);

  // 首次进入自动跑一遍引导；标记落 localStorage，之后靠标题栏按钮重放
  useEffect(() => {
    if (loaded && !localStorage.getItem(TOUR_SEEN_KEY)) {
      setTour(true);
    }
  }, [loaded]);

  const closeTour = useCallback(() => {
    localStorage.setItem(TOUR_SEEN_KEY, '1');
    setTour(false);
  }, []);

  // 平台提示词预览随级别/币种/仓位规格联动（与唤醒组装同一份文本，所见即所得）
  useEffect(() => {
    traderApi.promptTemplate(form.intervalCode, form.symbols || 'BTCUSDT', form.spec)
      .then(setTemplate).catch(() => setTemplate(''));
  }, [form.intervalCode, form.symbols, form.spec]);

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
  const setSpec = (patch: Partial<TraderSpec>) => setForm(f => {
    const spec = { ...f.spec, ...patch };
    // 单仓模式下双开无从谈起（双开本身要两个仓位），关多仓位时顺手把它归位
    if (!spec.allowMultiPosition) {
      spec.allowHedge = false;
    }
    return { ...f, spec };
  });

  const decide = async (id: number, approve: boolean) => {
    setBusy('req' + id);
    try {
      await (approve ? traderApi.approveRequest(id) : traderApi.rejectRequest(id));
      toast(approve ? '已同意，按市价执行' : '已拒绝', 'success');
      loadRequests();
    } catch (e) {
      toast((e as Error).message || '操作失败', 'error');
    } finally {
      setBusy(null);
    }
  };

  // 不走 run()：检测不能触发 load()，否则会用库里旧配置冲掉表单里未保存的 baseUrl/key
  const detectModels = async () => {
    setDetecting(true);
    try {
      const list = await traderApi.listModels({
        apiProtocol: form.apiProtocol, baseUrl: form.baseUrl, apiKey: form.apiKey,
      });
      setModels(list);
      toast(list.length ? `检测到 ${list.length} 个模型` : '端点未返回模型，可直接手输', list.length ? 'success' : 'error');
    } catch (e) {
      toast((e as Error).message || '检测失败', 'error');
    } finally {
      setDetecting(false);
    }
  };
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
        <button onClick={() => setTour(true)}
                className="ml-auto border border-border hover:bg-surface-hover rounded-lg px-2.5 py-1.5 text-[11px] font-bold text-muted-foreground hover:text-primary flex items-center gap-1.5">
          <GraduationCap className="w-3.5 h-3.5" /> 配置引导
        </button>
      </div>

      <GuidedTour steps={TOUR_STEPS} open={tour} onClose={closeTour} />

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

      {/* 待确认请求：自主加/减仓关掉时 AI 发来的申请，有待办才显示 */}
      {requests.length > 0 && (
        <div className="rounded-lg pt-card p-4 space-y-2.5 border-amber-500/40">
          <div className="flex items-baseline justify-between">
            <span className="microlabel text-amber-600">AI 请求确认 · {requests.length}</span>
            <span className="text-[10px] text-muted-foreground">同意后按当前市价立即执行</span>
          </div>
          {requests.map(r => (
            <RequestCard key={r.id} r={r} busy={busy === 'req' + r.id} disabled={busy != null}
                         onDecide={approve => void decide(r.id, approve)} />
          ))}
        </div>
      )}

      {/* 配置表单 */}
      <div className="rounded-lg pt-card p-4 space-y-4">
        <span className="microlabel">{exists ? '配置（提示词改完下一根K线生效）' : '创建（连通性校验通过后开户注资 10000U）'}</span>

        <div className="grid sm:grid-cols-2 gap-3">
          <label className="space-y-1 text-xs" data-tour="name">
            <span className="text-muted-foreground font-bold">名字</span>
            <input value={form.name} onChange={e => set({ name: e.target.value })} maxLength={32} placeholder="给你的 AI 起个名"
                   className="w-full h-9 rounded-lg border border-border bg-card-2 px-3 text-xs" />
          </label>
          <label className="space-y-1 text-xs" data-tour="interval">
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
            {form.intervalCode === '5m' && (
              <p className="text-[10px] text-amber-600 leading-relaxed">
                5m 适合短期测试观察；实跑建议 15m 起——高频唤醒的双边手续费磨损极大
              </p>
            )}
          </label>
        </div>

        <div className="space-y-1 text-xs" data-tour="symbols">
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

        {/* 仓位规格：主人说了算的硬参数，模型无权评价，越界一律拒（不截断） */}
        <div className="space-y-2.5 rounded-lg border border-border/60 bg-card-2/40 p-3">
          <div className="flex items-baseline justify-between">
            <span className="microlabel">仓位规格（强制执行，AI 不得篡改）</span>
            <span className="text-[10px] text-muted-foreground">区间＝必须从中选，不是上限</span>
          </div>

          <div className="grid sm:grid-cols-2 gap-3">
            <label className="space-y-1 text-xs" data-tour="leverage">
              <span className="text-muted-foreground font-bold">杠杆区间（1~125 倍）</span>
              <div className="flex items-center gap-1.5">
                <input type="number" min={1} max={125} step={1} value={form.spec.leverageMin}
                       onChange={e => setSpec({ leverageMin: Number(e.target.value) })}
                       className="w-full h-9 rounded-lg border border-border bg-card-2 px-3 text-xs num" />
                <span className="text-muted-foreground">~</span>
                <input type="number" min={1} max={125} step={1} value={form.spec.leverageMax}
                       onChange={e => setSpec({ leverageMax: Number(e.target.value) })}
                       className="w-full h-9 rounded-lg border border-border bg-card-2 px-3 text-xs num" />
              </div>
            </label>
            <label className="space-y-1 text-xs" data-tour="margin">
              <span className="text-muted-foreground font-bold">每笔保证金占权益 %（0.1~100）</span>
              <div className="flex items-center gap-1.5">
                <input type="number" min={0.1} max={100} step={0.5} value={form.spec.marginPctMin}
                       onChange={e => setSpec({ marginPctMin: Number(e.target.value) })}
                       className="w-full h-9 rounded-lg border border-border bg-card-2 px-3 text-xs num" />
                <span className="text-muted-foreground">~</span>
                <input type="number" min={0.1} max={100} step={0.5} value={form.spec.marginPctMax}
                       onChange={e => setSpec({ marginPctMax: Number(e.target.value) })}
                       className="w-full h-9 rounded-lg border border-border bg-card-2 px-3 text-xs num" />
              </div>
            </label>
          </div>

          {/* 配置的后果一眼可见：按 10000U 权益换算成名义价值区间 */}
          <p className="text-[10px] leading-relaxed text-muted-foreground">
            以 10000U 权益计，每笔新仓保证金{' '}
            <span className="num text-foreground">{fmtNum(form.spec.marginPctMin * 100, 0)}</span>~
            <span className="num text-foreground">{fmtNum(form.spec.marginPctMax * 100, 0)}</span>U，
            名义价值{' '}
            <span className="num text-foreground">{fmtNum(form.spec.marginPctMin * 100 * form.spec.leverageMin, 0)}</span>~
            <span className="num text-foreground">{fmtNum(form.spec.marginPctMax * 100 * form.spec.leverageMax, 0)}</span>U。
            只约束开新仓，加仓量由 AI 自己斟酌。高杠杆下交易所按名义价值分档，超档会被拒。
          </p>

          <div className="grid sm:grid-cols-2 gap-x-4 gap-y-2 pt-1">
            <div className="space-y-2" data-tour="position-rules">
              <SpecToggle checked={form.spec.allowMultiPosition} onChange={v => setSpec({ allowMultiPosition: v })}
                          label="允许多仓位"
                          hint="关＝全账户至多一仓，AI 自己挑哪个币；未成交挂单也占坑" />
              <SpecToggle checked={form.spec.allowHedge} onChange={v => setSpec({ allowHedge: v })}
                          disabled={!form.spec.allowMultiPosition}
                          label="允许多空双开"
                          hint={form.spec.allowMultiPosition ? '同一个币可同时持多空' : '需先开启多仓位（双开要占两个仓位）'} />
            </div>
            <div className="space-y-2" data-tour="self-manage">
              <SpecToggle checked={form.spec.allowSelfAdd} onChange={v => setSpec({ allowSelfAdd: v })}
                          label="允许 AI 自主加仓"
                          hint="关＝AI 想加仓时发请求给你，等你点同意" />
              <SpecToggle checked={form.spec.allowSelfReduce} onChange={v => setSpec({ allowSelfReduce: v })}
                          label="允许 AI 自主减仓"
                          hint="关＝减仓/平仓需你确认；止损止盈仍自动触发，风险有保护" />
            </div>
          </div>
        </div>

        {/* 波动哨兵：极端行情临时唤醒（例行K线唤醒的补充）——只对 1h/4h 档生效 */}
        <div className="space-y-2 rounded-lg border border-border/60 bg-card-2/40 p-3">
          <div className="flex items-baseline justify-between">
            <span className="microlabel">波动警报（仅 1h/4h 档生效）</span>
            <span className="text-[10px] text-muted-foreground">5 分钟振幅超阈值且持有该币仓位/挂单时临时唤醒</span>
          </div>
          <div className="grid sm:grid-cols-2 gap-3 items-start">
            <SpecToggle checked={form.alertEnabled} onChange={v => set({ alertEnabled: v })}
                        label="启用波动警报"
                        hint="唤醒后 5 分钟冷静期；例行唤醒将至时警报自动让路" />
            <label className="space-y-1 text-xs">
              <span className="text-muted-foreground font-bold">灵敏度系数（≥1.0，越大警报越少）</span>
              <input type="number" min={1} step={0.1} value={form.alertThresholdMult}
                     onChange={e => set({ alertThresholdMult: Number(e.target.value) })}
                     disabled={!form.alertEnabled}
                     className="w-full h-9 rounded-lg border border-border bg-card-2 px-3 text-xs num disabled:opacity-45" />
            </label>
          </div>
          <p className="text-[10px] leading-relaxed text-muted-foreground">
            生效阈值 = 币基准 × 系数。平台基准（180 天历史校准，只能调高）：
            {SYMBOL_OPTIONS.map(s => `${s.replace('USDT', '')} ${ALERT_BASE[s]}%`).join(' · ')}。
            按当前系数 {form.alertThresholdMult >= 1 ? form.alertThresholdMult.toFixed(1) : '1.0'}：
            {SYMBOL_OPTIONS.filter(s => selected.has(s))
              .map(s => `${s.replace('USDT', '')} ${(ALERT_BASE[s] * Math.max(1, form.alertThresholdMult)).toFixed(2)}%`)
              .join(' · ') || '未选币种'}
          </p>
        </div>

        {/* 每日复盘：learning agent 在日线边界读全天交易痕迹，复盘上时间线、教训写进记忆笔记 */}
        <div className="space-y-2 rounded-lg border border-border/60 bg-card-2/40 p-3">
          <div className="flex items-baseline justify-between">
            <span className="microlabel">每日复盘</span>
            <span className="text-[10px] text-muted-foreground">复盘公开上时间线；记忆笔记之后每次唤醒自动注入</span>
          </div>
          <SpecToggle checked={form.reviewEnabled} onChange={v => set({ reviewEnabled: v })}
                      label="启用每日复盘"
                      hint="日线边界自动跑一次（烧你的 key，单次调用）；当天无交易自动跳过；关掉只停复盘，已有笔记照常注入" />
        </div>

        <div className="space-y-3" data-tour="byok">
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
            <div className="flex gap-1.5">
              <input value={form.model} onChange={e => set({ model: e.target.value })} placeholder="deepseek-chat"
                     className="flex-1 min-w-0 h-9 rounded-lg border border-border bg-card-2 px-3 text-xs num" />
              <button type="button" onClick={() => void detectModels()}
                      disabled={detecting || !form.baseUrl.trim() || (!exists && !form.apiKey.trim())}
                      title="拉取该端点可用的模型清单（需先填 Base URL 和 Key）"
                      className="shrink-0 border border-border hover:bg-surface-hover rounded-lg px-2.5 h-9 text-xs font-bold text-primary flex items-center gap-1 disabled:opacity-50">
                {detecting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ScanSearch className="w-3.5 h-3.5" />}
                检测
              </button>
            </div>
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
        </div>

        {/* 检测到的模型清单：模型名输入即过滤，点击填入；网关不支持 /models 时照常手输 */}
        {models.length > 0 && (() => {
          const kw = form.model.trim().toLowerCase();
          const hits = models.filter(m => m.toLowerCase().includes(kw));
          return (
            <div className="max-h-28 overflow-y-auto flex flex-wrap gap-1.5 content-start rounded-lg border border-border bg-card-2/50 p-2">
              {hits.map(m => (
                <button key={m} type="button" onClick={() => set({ model: m })}
                        className={cn('px-2 h-7 rounded-md border text-[11px] num',
                          form.model === m ? 'border-primary/60 bg-card-2 text-primary font-bold' : 'border-border text-muted-foreground hover:text-foreground')}>
                  {m}
                </button>
              ))}
              {hits.length === 0 && (
                <span className="text-[11px] text-muted-foreground px-1 py-1">清单里无匹配「{form.model.trim()}」，可直接手输</span>
              )}
            </div>
          );
        })()}

        {/* 平台系统提示词：默认勾选使用；取消后自定义成为唯一指令来源（TradeGuard 护栏仍硬校验） */}
        <div className="space-y-4" data-tour="prompt">
        <div className="space-y-1.5 text-xs">
          <label className="flex items-center gap-2 cursor-pointer select-none">
            <input type="checkbox" checked={form.useDefaultPrompt}
                   onChange={e => set({ useDefaultPrompt: e.target.checked })}
                   className="w-3.5 h-3.5 accent-[var(--primary,#6366f1)]" />
            <span className="text-muted-foreground font-bold">使用平台系统提示词（推荐）</span>
          </label>
          {form.useDefaultPrompt ? (
            <details className="rounded-lg border border-border bg-card-2/50">
              <summary className="px-3 py-2 text-[11px] font-bold text-muted-foreground cursor-pointer">
                查看平台系统提示词（随级别/币种联动，自定义追加在它之后）
              </summary>
              <pre className="max-h-56 overflow-y-auto px-3 pb-3 text-[11px] leading-relaxed text-muted-foreground whitespace-pre-wrap font-sans">{template}</pre>
            </details>
          ) : (
            <p className="rounded-lg border border-amber-500/40 bg-amber-500/10 px-3 py-2 text-[11px] text-amber-600 font-bold">
              已关闭平台提示词：下方自定义将成为唯一指令来源（必填）。上面的仓位规格仍由护栏强制执行——
              杠杆 {form.spec.leverageMin}~{form.spec.leverageMax} 倍、每笔新仓保证金占权益{' '}
              {form.spec.marginPctMin}%~{form.spec.marginPctMax}%
              {!form.spec.allowMultiPosition && '、只许一个仓位'}
              {form.spec.allowMultiPosition && !form.spec.allowHedge && '、同币不得多空双开'}
              {!form.spec.allowSelfAdd && '、加仓需你确认'}
              {!form.spec.allowSelfReduce && '、减仓需你确认'}
              、开仓必须带止损/论点/失效条件、止损只许收紧、止盈只许远离入场，违规动作会被拒绝并告知原因。
            </p>
          )}
        </div>

        <label className="space-y-1 text-xs block">
          <span className="text-muted-foreground font-bold">
            {form.useDefaultPrompt ? '自定义提示词（定风格与策略，与平台默认冲突时以你的为准）' : '自定义提示词（唯一指令来源，必填）'}
          </span>
          <textarea value={form.customPrompt ?? ''} onChange={e => set({ customPrompt: e.target.value })} rows={6} maxLength={4000}
                    placeholder="例：只做趋势突破，不抄底不摸顶；单笔风险不超过权益2%；连亏两笔后本日只观望。"
                    className="w-full rounded-lg border border-border bg-card-2 px-3 py-2 text-xs leading-relaxed" />
        </label>
        </div>

        <button
          data-tour="save"
          onClick={() => void run('save',
            () => exists ? traderApi.updateConfig(form) : traderApi.create(form),
            exists ? '配置已保存' : '创建成功！去启动它吧')}
          disabled={busy != null}
          className="border border-border hover:bg-surface-hover rounded-lg px-4 py-2 text-xs font-black text-primary flex items-center gap-1.5 disabled:opacity-50">
          {busy === 'save' ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Save className="w-3.5 h-3.5" />}
          {exists ? '保存配置' : '创建 Trader'}
        </button>

        <p className="text-[10px] leading-relaxed text-muted-foreground border-l-2 border-border pl-2.5">
          规则：每根 K 线唤醒一次，你的 key 你的 token 钱；仓位规格你说了算（杠杆/保证金区间、仓位数、双开、
          能否自主加减仓），AI 只能遵守不得评价，越界的调用当场被拒；强制止损+失效条件、止损只许收紧。
          开仓即立交易计划，每次唤醒原样喂回，退出只认止损/止盈/失效条件三条路。
          模拟盘不涉真实资金，竞技场决策日志与计划修订历史公开可见。别指望它赚钱——看它怎么想才是重点。
        </p>
      </div>
    </div>
  );
}

/** 规格开关：一行一个，说明写在标签下方——配置的后果得当场看得懂 */
function SpecToggle({ checked, onChange, label, hint, disabled }: {
  checked: boolean; onChange: (v: boolean) => void; label: string; hint: string; disabled?: boolean;
}) {
  return (
    <label className={cn('flex items-start gap-2 text-xs select-none',
      disabled ? 'opacity-45 cursor-not-allowed' : 'cursor-pointer')}>
      <input type="checkbox" checked={checked} disabled={disabled}
             onChange={e => onChange(e.target.checked)} className="mt-0.5" />
      <span className="space-y-0.5">
        <span className="font-bold block">{label}</span>
        <span className="text-[10px] text-muted-foreground leading-relaxed block">{hint}</span>
      </span>
    </label>
  );
}

/**
 * 待确认请求卡：请求时价与实时价并排，价格跑没跑掉由主人自己判断——不设过期，不替他决定。
 */
function RequestCard({ r, onDecide, busy, disabled }: {
  r: TraderRequestView; onDecide: (approve: boolean) => void; busy: boolean; disabled: boolean;
}) {
  const tick = useCryptoStream(r.symbol, 'futures');
  const live = tick?.price ?? null;
  const drift = live != null && r.requestPrice > 0 ? (live - r.requestPrice) / r.requestPrice * 100 : null;
  const isAdd = r.type === 'ADD';

  return (
    <div className="rounded-lg border border-border bg-card-2/60 p-3 space-y-2">
      <div className="flex items-center gap-2 flex-wrap text-xs">
        <span className={cn('px-1.5 py-0.5 rounded font-bold text-[10px]',
          isAdd ? 'bg-gain/15 text-gain' : 'bg-loss/15 text-loss')}>
          {isAdd ? '加仓' : '减仓'}
        </span>
        <span className="font-bold">{r.symbol.replace('USDT', '')}</span>
        <span className="text-muted-foreground">{r.side === 'LONG' ? '多' : '空'}</span>
        <span className="num">{r.quantity}</span>
        {r.leverage != null && <span className="text-muted-foreground num">{r.leverage}x</span>}
        <span className="ml-auto text-[10px] text-muted-foreground">{relTime(r.createdAt)}</span>
      </div>

      <div className="flex items-center gap-4 text-[11px]">
        <span className="text-muted-foreground">
          请求时 <span className="num text-foreground">{fmtNum(r.requestPrice, 2)}</span>
        </span>
        <span className="text-muted-foreground">
          现价 <span className="num text-foreground">{live == null ? '—' : fmtNum(live, 2)}</span>
        </span>
        {drift != null && (
          <span className={cn('num font-bold', drift >= 0 ? 'text-gain' : 'text-loss')}>
            {drift >= 0 ? '+' : ''}{drift.toFixed(2)}%
          </span>
        )}
      </div>

      <p className="text-[11px] text-muted-foreground leading-relaxed">{r.reason}</p>

      <div className="flex gap-2">
        <button onClick={() => onDecide(true)} disabled={disabled}
                className="border border-border hover:bg-surface-hover rounded-lg px-3 py-1.5 text-xs font-bold text-gain flex items-center gap-1.5 disabled:opacity-50">
          {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Check className="w-3.5 h-3.5" />} 同意
        </button>
        <button onClick={() => onDecide(false)} disabled={disabled}
                className="border border-border hover:bg-surface-hover rounded-lg px-2.5 py-1.5 text-xs font-bold text-muted-foreground disabled:opacity-50"
                aria-label="拒绝">
          <X className="w-3.5 h-3.5" />
        </button>
      </div>
    </div>
  );
}

function relTime(ts: number): string {
  const min = Math.max(0, Math.floor((Date.now() - ts) / 60000));
  if (min < 1) {
    return '刚刚';
  }
  return min < 60 ? `${min} 分钟前` : `${Math.floor(min / 60)} 小时前`;
}
