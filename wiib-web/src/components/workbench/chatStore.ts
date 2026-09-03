import { ApiError, workbenchApi } from '../../api';
import type { BehaviorAnalysisReport, ChatIntent, SearchSource, TraderFormKind, TurnMeta, WorkbenchChatMessage, WorkbenchEvent } from '../../types';

/** 与后端 ErrorCode 对齐：2200 段是研判工作台（1600 段是 Crypto，别复用） */
export const CHAT_ERROR = {
  CONFIG_MISSING: 2201,
  CONFIG_INVALID: 2202,
  ALREADY_RUNNING: 2203,
  CAPACITY_FULL: 2204,
  /** 这条回答回不去（末尾不是答案／补答行／本轮触发过历史压缩），重新生成被拒 */
  REGENERATE_UNAVAILABLE: 2206,
  /** 发起补答轮时会话已不欠账（别的标签页接走了，或后端重启丢了队列） */
  NOTHING_DEFERRED: 2208,
} as const;

/**
 * 工作台对话 store（模块级单例）：状态与 SSE 消费脱离组件生命周期——
 * 切页只是 ChatPanel 卸载，流在这里继续收、状态继续涨，切回来订阅即续显；
 * 整页刷新后 store 归零，靠 status 轮询发现"AI 还在后台跑"，结束拉历史补答案。
 * <p>
 * 让位后欠下的补答由<b>前端发起</b>（{@link runDeferred}）：后端只排队不偷跑。
 * 时机只有一条规则——本地没有轮在跑、排队消息也发完了（用户消息永远优先），见 {@link settle}。
 */

export type ChatItem =
  // queued=后端不让位（正在出答案）时先上屏排队，本轮结束自动真发；
  // 专家取数期间的新消息会触发后端让位直接插话，不走排队
  // at=这条消息的时刻（历史回放取库里的 createdAt，实时取本地时钟），面板上要显示。
  // queuedId=这条消息的身份，气泡与队列条目共用——靠下标对应的话，
  // 排队期间流式往 items 里插条目、回放整体重建、重生成砍尾巴，任一处都会让两边错位
  | { kind: 'user'; content: string; at: number; queued?: boolean; queuedId?: number }
  // meta=这一轮的读数（端点/耗时/token），流式结束时随 done 事件到；历史回放从库里带。
  // deferred=这条是补答（对应提问不在会话末尾，回退会误伤中间轮次），不给重新生成；
  // 实时流里的答案永远在末尾，只有历史回放才可能是补答行
  // sources=这一轮联网搜索的来源，随 done 到（历史回放从库里带），答案底部展示
  | { kind: 'assistant'; content: string; streaming: boolean; at: number; meta?: TurnMeta | null; deferred?: boolean; sources?: SearchSource[] | null }
  // 专家过程流（不落历史）：视图层收进"工作过程"轨，折叠状态归视图管。
  // rid=轨内条目的自增号，视图拿轨首那条的 rid 当折叠状态的键——用下标做键的话，
  // 让位说明行往中间一插、重新生成把尾巴一砍，键就整体错位，收着的轨会自己弹开
  | { kind: 'expert'; rid: number; agent: string; content: string; streaming: boolean }
  | { kind: 'agent'; rid: number; node: string; agent: string }
  // 长工具阶段进度（深研判等）：最新一条亮着转圈，后续事件到达即熄灭。
  // keyed=true 时 text 存的是 ai 词表的 key（前端自己立的说明行），由视图层渲染时现翻——
  // store 是纯 ts 模块、条目一存就是一整场会话，存翻好的字面量切了语言会僵在旧语言里。
  // 后端下发的阶段文案 keyed 为假，原样显示（那是后端数据，不进词表）
  | { kind: 'progress'; rid: number; text: string; keyed?: boolean; active: boolean }
  // 汇总者的一次联网搜索：active=还在搜（亮着转圈），搜完回填 sources 熄灭；query 开搜时可能还没有
  | { kind: 'search'; rid: number; query: string | null; sources: SearchSource[]; active: boolean }
  // requestId 存在 item 上：hitlDecide 按它取回本条再原样回传，服务端据此确认"点的是哪张卡"
  | { kind: 'hitl'; symbol: string; reason: string; requestId: string; resumeMessage: string; status: 'pending' | 'approved' | 'rejected' }
  // trader 动作表单卡：模型只有弹卡的权，执行权归用户点击。纯前端态不落历史，id 本地发
  | { kind: 'form'; id: string; form: TraderFormKind; prefill?: Record<string, unknown>; status: 'pending' | 'done'; result?: string }
  // 行为分析报告卡：模型调 analyze_my_behavior 后随 SSE 到。同样不落历史——
  // 报告在服务端缓存 30 分钟，刷新后想再看一眼再问一句就是了，不值得为它开一张表
  | { kind: 'behavior'; report: BehaviorAnalysisReport }
  // keyed 同 progress：前端自己的兜底报错存 key，后端/异常带回来的 message 原样显示
  | { kind: 'error'; message: string; keyed?: boolean };

export interface ChatState {
  items: ChatItem[];
  loading: boolean;
  /** true=刷新后发现会话还在后台跑（无 token 流，轮询等结果） */
  background: boolean;
  /** true=后端说没配 LLM 或配置不可用，面板显示"去配置"引导条而不是干显示一行红字 */
  needsConfig: boolean;
  sessionId: string | null;
}

const SESSION_KEY = 'wiib-workbench-session';
const POLL_MS = 3000;
/**
 * 让位后立在原问题过程轨里的说明行（也当"这个问题已有交代"的标记，防重复插）。
 * 存的是词表 key 不是文案：既躲开切语言僵住的坑，标记比对也不会随语言变。
 */
const DEFERRED_NOTE = 'rail.deferredNote';
/** 续跑指令在过程轨里的措辞（词表 key）：与 HITL 卡自己的状态行错开，别同一句话连着显示两遍 */
const HITL_RESUME_NOTE = 'rail.hitlResume';

let state: ChatState = {
  items: [],
  loading: false,
  background: false,
  needsConfig: false,
  sessionId: sessionStorage.getItem(SESSION_KEY),
};
const listeners = new Set<() => void>();
let abortCtrl: AbortController | null = null;
let pollTimer: number | null = null;
let initialized = false;
/**
 * 后端不让位（正在出答案）时排队的待发消息。
 * bubbled=屏幕上有没有对应的排队气泡——HITL 续跑那种自动补发的指令没有气泡，
 * 续发时不能跟着去解别人气泡的排队标记。
 */
// intent 跟着排队条目走：功能按钮那一发被后端占线拒掉后，续发时意图不能丢
type QueuedMessage = { id: number; text: string; bubbled: boolean; intent?: ChatIntent };
let sendQueue: QueuedMessage[] = [];
let queueSeq = 0;
/**
 * 会话还欠着补答（让位交出去的专家批次等着被接回）。跟着后端口径走：每个 done 事件与 status 都带 pending，
 * 前端只转抄不推断。为真且本地空闲时由 settle() 发起补答轮
 */
let deferredPending = false;
/** 表单卡只活在本地 items 里（不落历史），自增序号足够把几张卡区分开 */
let formSeq = 0;
function nextFormId() {
  return `form-${++formSeq}`;
}
/**
 * 输入框草稿。关面板会把 ChatPanel 整棵卸载，草稿放在这儿才不会跟着没。
 * <b>刻意不进 state、不发通知</b>：它只在面板重新挂载时被读一次，
 * 跟着 items 一起触发重渲染纯属浪费——流式期间那是每帧一次。
 */
let draft = '';

/** 过程条目的自增号：视图用轨首那条的 rid 记折叠状态，条目挪位置也认得回来 */
let railSeq = 0;
function nextRid() {
  return ++railSeq;
}

function set(patch: Partial<ChatState>) {
  state = { ...state, ...patch };
  listeners.forEach(l => l());
}

function updateItems(updater: (prev: ChatItem[]) => ChatItem[]) {
  set({ items: updater(state.items) });
}

function setSession(id: string | null) {
  if (id) sessionStorage.setItem(SESSION_KEY, id);
  else sessionStorage.removeItem(SESSION_KEY);
  set({ sessionId: id });
}

/**
 * 后端历史 → 对话项（专家过程/进度不落库，只回放 user/assistant）。
 * 特殊行按后端给的码分流：续跑指令不是用户打的字，还原成过程轨行，
 * 否则历史里会多出一句用户从没说过的话。
 */
function toItems(messages: WorkbenchChatMessage[]): ChatItem[] {
  return messages.map(m => {
    if (m.role !== 'user') {
      return {
        kind: 'assistant' as const, content: m.content, streaming: false,
        at: m.createdAt, meta: m.meta, deferred: m.kind === 'deferred', sources: m.sources,
      };
    }
    return m.kind === 'hitlResume'
      ? { kind: 'progress' as const, rid: nextRid(), text: HITL_RESUME_NOTE, keyed: true, active: false }
      : { kind: 'user' as const, content: m.content, at: m.createdAt };
  });
}

/** 没填完的表单卡：历史回放整体重建 items 时得接回尾部，否则用户填一半的内容无声消失 */
function pendingForms(): ChatItem[] {
  return state.items.filter(it => it.kind === 'form' && it.status === 'pending');
}

/** 进度行熄灭：token/新进度到达说明上个阶段已过去 */
function deactivateProgress(items: ChatItem[]): ChatItem[] {
  return items.some(it => it.kind === 'progress' && it.active)
    ? items.map(it => (it.kind === 'progress' && it.active ? { ...it, active: false } : it))
    : items;
}

/** 来源按 url 去重合并（同一个网站可能被几次搜索重复命中） */
function mergeSources(a: SearchSource[], b: SearchSource[]): SearchSource[] {
  const seen = new Set(a.map(s => s.url));
  return [...a, ...b.filter(s => !seen.has(s.url) && seen.add(s.url))];
}

/** 还亮着的搜索条目熄灭：done 到了说明搜索早已过去（searched 没配上对的那种） */
function settleSearches(items: ChatItem[]): ChatItem[] {
  return items.some(it => it.kind === 'search' && it.active)
    ? items.map(it => (it.kind === 'search' && it.active ? { ...it, active: false } : it))
    : items;
}

/**
 * 搜完事件回填到哪条：先找亮着且 query 相同（或开搜时没给 query）的，
 * 再找同 query 已熄灭的（Gemini 分块补来源），都没有就新建一条
 */
function fillSearched(items: ChatItem[], query: string | null, sources: SearchSource[]): ChatItem[] {
  const next = [...items];
  for (let j = next.length - 1; j >= 0; j--) {
    const it = next[j];
    if (it.kind !== 'search') continue;
    const hit = it.active ? (it.query === query || !it.query) : (it.query !== null && it.query === query);
    if (hit) {
      next[j] = { ...it, query: it.query ?? query, sources: mergeSources(it.sources, sources), active: false };
      return next;
    }
  }
  next.push({ kind: 'search', rid: nextRid(), query, sources, active: false });
  return next;
}

/**
 * 让位说明行插到被让位的那个问题名下：按后端给的原问题找到那条 user 项（同文多问取第一条还没交代的），
 * 在下一个 user 项之前插入——被让位的问题在新消息上屏之后、done(deferred) 到达之前，
 * 直接 push 到末尾会挂错到新消息名下。
 * 它已经有回答或说明行（补答轮再被让位时原问题早就立过牌子）就不再插，找不到（刷新后历史里没有这条）也不插。
 */
function insertDeferredNote(items: ChatItem[], question: string | undefined): ChatItem[] {
  if (!question) return items;
  for (let i = 0; i < items.length; i++) {
    const item = items[i];
    if (item.kind !== 'user' || item.queued || item.content !== question) continue;
    let end = items.length;
    for (let j = i + 1; j < items.length; j++) {
      if (items[j].kind === 'user') { end = j; break; }
    }
    const settled = items.slice(i + 1, end).some(it =>
      it.kind === 'assistant' || (it.kind === 'progress' && it.text === DEFERRED_NOTE));
    if (settled) continue;
    return [
      ...items.slice(0, end),
      { kind: 'progress', rid: nextRid(), text: DEFERRED_NOTE, keyed: true, active: false },
      ...items.slice(end),
    ];
  }
  return items;
}

/** 补答轮的实时答案气泡：对应提问不在会话末尾，与历史回放的补答行一样不给重新生成 */
function markLastAnswerDeferred(items: ChatItem[]): ChatItem[] {
  for (let i = items.length - 1; i >= 0; i--) {
    const it = items[i];
    if (it.kind === 'assistant') {
      return [...items.slice(0, i), { ...it, deferred: true }, ...items.slice(i + 1)];
    }
  }
  return items;
}

function handleEvent(e: WorkbenchEvent) {
  switch (e.type) {
    case 'session':
      setSession(e.sessionId);
      // 流能建起来就说明端点是通的（配置类错误在准入期就拒了，根本到不了这里），
      // 引导条自己撤掉——挂着不动会让刚配好的用户以为还没生效
      if (state.needsConfig) set({ needsConfig: false });
      break;
    case 'agent_start':
      updateItems(prev => [...prev, { kind: 'agent', rid: nextRid(), node: e.node, agent: e.agent }]);
      break;
    case 'progress':
      updateItems(prev => [...deactivateProgress(prev), { kind: 'progress', rid: nextRid(), text: e.text, active: true }]);
      break;
    case 'token':
      updateItems(prev => {
        const base = deactivateProgress(prev);
        if (e.role === 'process') {
          // 并行派发时多专家 chunk 交错到达：从尾部找本专家的流式块追加，不能只看最后一项
          const next = [...base];
          for (let j = next.length - 1; j >= 0; j--) {
            const it = next[j];
            if (it.kind === 'expert' && it.agent === e.agent && it.streaming) {
              next[j] = { ...it, content: it.content + e.text };
              return next;
            }
          }
          next.push({ kind: 'expert', rid: nextRid(), agent: e.agent, content: e.text, streaming: true });
          return next;
        }
        // 答案流开始：专家过程停止流式（视图层据此把工作过程轨默认收起）
        const next = base.map(it =>
          it.kind === 'expert' && it.streaming ? { ...it, streaming: false } : it,
        );
        // 同样从尾部回扫本轮答案块：表单卡这类条目会插到尾部，只认最后一项会把一轮答案劈成两截
        for (let j = next.length - 1; j >= 0; j--) {
          const it = next[j];
          if (it.kind === 'assistant' && it.streaming) {
            next[j] = { ...it, content: it.content + e.text };
            return next;
          }
        }
        next.push({ kind: 'assistant', content: e.text, streaming: true, at: Date.now() });
        return next;
      });
      break;
    case 'hitl_request':
      updateItems(prev => [...prev, {
        kind: 'hitl', symbol: e.symbol, reason: e.reason, requestId: e.requestId,
        resumeMessage: e.resumeMessage, status: 'pending',
      }]);
      break;
    case 'form_request':
      // 模型只把卡推上屏就到头了，动作等用户在卡上点，后端不会自己往下走
      updateItems(prev => [...prev, {
        kind: 'form', id: nextFormId(), form: e.form, prefill: e.prefill, status: 'pending',
      }]);
      break;
    case 'behavior_report':
      // 报告卡先上屏，模型紧接着会就着它讲两句——卡是数据、答案是解读，两者互补不重复
      updateItems(prev => [...deactivateProgress(prev), { kind: 'behavior', report: e.report }]);
      break;
    case 'search':
      // 引用只并入后端攒的来源（随 done 回来），过程轨不画
      if (e.phase === 'cited') break;
      updateItems(prev => e.phase === 'searching'
        ? [...deactivateProgress(prev), { kind: 'search', rid: nextRid(), query: e.query ?? null, sources: [], active: true }]
        : fillSearched(deactivateProgress(prev), e.query ?? null, e.sources ?? []));
      break;
    case 'done':
      // 欠不欠补答以后端此刻的口径为准：补答轮跑完队列里可能还排着下一单，让位收尾则必然欠着
      deferredPending = e.pending === true;
      if (e.deferred) {
        // 让位收尾：答案欠着，这里只在被让位的问题后面立块牌子；补答轮由本地流收尾时的 settle() 发起
        updateItems(prev => insertDeferredNote(deactivateProgress(prev), e.question));
        break;
      }
      updateItems(prev => {
        const next = settleSearches(deactivateProgress(prev)).map(it => {
          // 读数与来源随 done 一起到：本轮不用等刷新就能显示端点/耗时/token 和答案底部的来源。
          // 中断的那条要整段用服务端定稿覆盖——"（已中断）"这个尾标只在服务端拼一次，
          // 前端复刻一份的话两处措辞迟早对不上，刷新前后看到的就不是同一段文字
          if (it.kind === 'assistant' && it.streaming) {
            return {
              ...it,
              content: e.cancelled ? e.answer : (it.content || e.answer),
              streaming: false,
              meta: e.meta,
              sources: e.sources,
            };
          }
          if (it.kind === 'expert' && it.streaming) return { ...it, streaming: false };
          return it;
        });
        // 答案流没出现过（如调用上限截停）：done 里的兜底答案补成气泡，不然这轮白问
        let lastUser = -1;
        for (let j = next.length - 1; j >= 0; j--) {
          if (next[j].kind === 'user') { lastUser = j; break; }
        }
        const hasAnswer = next.slice(lastUser + 1).some(it => it.kind === 'assistant');
        if (!hasAnswer && e.answer) {
          next.push({ kind: 'assistant', content: e.answer, streaming: false, at: Date.now(), meta: e.meta, sources: e.sources });
        }
        return next;
      });
      break;
    case 'error':
      updateItems(prev => [...prev, { kind: 'error', message: e.message }]);
      break;
    default:
      // 后端日后加新事件类型时不至于静默吞掉；不上屏，排查看控制台
      console.warn('[chat] 未识别的事件', e);
  }
}

function stopPolling() {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer);
    pollTimer = null;
  }
}

/**
 * 有轮在别处跑（刷新前的那轮 / 另一个标签页）：本地没有 SSE，轮询 status，
 * 结束即拉历史补出完整答案，再按 settle() 的规则续发排队消息或发起补答轮
 */
function startPolling(sid: string) {
  stopPolling();
  pollTimer = window.setInterval(() => {
    void (async () => {
      try {
        const status = await workbenchApi.sessionStatus(sid);
        // 会话已切换或用户已开新一轮流式对话（abortCtrl 在挂）：本轮poll作废
        if (state.sessionId !== sid || abortCtrl) { stopPolling(); return; }
        if (!status.running) {
          stopPolling();
          const msgs = await workbenchApi.sessionMessages(sid);
          if (abortCtrl) return;
          deferredPending = status.pending;
          // 历史回放会整体重建 items：排队气泡和没填完的表单卡都不在后端历史里，得补回尾部
          const queued = sendQueue.filter(q => q.bubbled)
            .map(q => ({ kind: 'user' as const, content: q.text, at: Date.now(), queued: true, queuedId: q.id }));
          set({ items: [...toItems(msgs), ...queued, ...pendingForms()], loading: false, background: false });
          settle();
        }
      } catch { /* 网络抖动下轮再试 */ }
    })();
  }, POLL_MS);
}

/**
 * 发一条消息。
 *
 * @param opts.noBubble   不上气泡：HITL 批准后自动补发的续跑指令（不是用户打的字），
 *                        以及气泡已在屏上的排队续发
 * @param opts.requeueAs  被拒时塞回队头用的原样条目（排队续发专用），不传就按新消息入队尾
 * @param opts.intent     功能按钮直发的意图：后端据此跳过专家派发，直奔对应工具
 */
async function send(message: string, opts?: { noBubble?: boolean; requeueAs?: QueuedMessage; intent?: ChatIntent }) {
  const msg = message.trim();
  if (!msg) return;
  // 有轮在跑也直接真发：后端专家等待期会让位（用户消息优先，专家结果转入补答队列）；
  // 不可让位（正在出答案）会拒 2203，届时再回落本地排队——排不排队由后端仲裁，前端不预判
  const prevAbort = abortCtrl;
  // 这条消息的身份：上屏时就发好，被拒时按它找回自己那只气泡。
  // 用下标的话，被拒之前流式往 items 里插过条目就会认错人
  const queuedId = ++queueSeq;
  stopPolling();
  if (!opts?.noBubble) {
    updateItems(prev => [...prev, { kind: 'user', content: msg, at: Date.now(), queuedId }]);
  }
  set({ loading: true, background: false });
  const abort = new AbortController();
  abortCtrl = abort;
  let fellBack = false;
  try {
    await workbenchApi.chat(state.sessionId, msg, handleEvent, abort.signal, opts?.intent);
  } catch (err) {
    if (!abort.signal.aborted) {
      const code = err instanceof ApiError ? err.code : 0;
      if (code === CHAT_ERROR.ALREADY_RUNNING) {
        // 后端不让位：本条转入排队。排队续发与无气泡的自动指令都塞回队头保序，
        // 新消息入队尾并把自己那只气泡标成排队中。
        // 本地有在跑的流就把主导权还给它；没有（刷新后后台轮在跑）就转后台轮询等那轮结束
        if (opts?.requeueAs) {
          sendQueue.unshift(opts.requeueAs);
        } else if (opts?.noBubble) {
          sendQueue.unshift({ id: queuedId, text: msg, bubbled: false, intent: opts?.intent });
        } else {
          sendQueue.push({ id: queuedId, text: msg, bubbled: true, intent: opts?.intent });
          updateItems(prev => prev.map(it =>
            it.kind === 'user' && it.queuedId === queuedId ? { ...it, queued: true } : it));
        }
        if (prevAbort && !prevAbort.signal.aborted) {
          if (abortCtrl === abort) abortCtrl = prevAbort;
          return;
        }
        fellBack = true;
        return;
      }
      // e.message 来自后端/网络异常，原样显示；只有兜底那半句是自家文案，存 key 交给视图现翻
      const errMsg = (err as Error).message;
      updateItems(prev => [...prev, errMsg
        ? { kind: 'error', message: errMsg }
        : { kind: 'error', message: 'err.disconnected', keyed: true }]);
      // 配置类错误光显一行红字没用，用户得知道去哪儿改——置标记让面板亮"去配置"引导条
      if (code === CHAT_ERROR.CONFIG_MISSING || code === CHAT_ERROR.CONFIG_INVALID) {
        set({ needsConfig: true });
      }
    }
  } finally {
    // 被 openSession/newSession 主动掐掉时它们各自接管状态，这里不抢；
    // 让位成功的插话把 abortCtrl 换成了自己，被让位那条流的收尾也走不进来
    if (abortCtrl === abort) {
      abortCtrl = null;
      if (fellBack && state.sessionId) {
        // 后端占线未让位且本地没有活跃流：回到"后台在跑"的等待姿态，轮询等那轮结束续发
        set({ loading: true, background: true });
        startPolling(state.sessionId);
      } else {
        set({ loading: false });
        settle();
      }
    }
  }
}

/**
 * 一轮在本地收尾后的下一步：排队消息先发（用户消息永远优先），一条都没有了再把欠的补答接回来。
 * 补答轮自己收尾也走这里——队列里可能还排着下一单。
 */
function settle() {
  if (sendQueue.length > 0) {
    drainQueue();
    return;
  }
  if (deferredPending && state.sessionId) void runDeferred(state.sessionId);
}

/**
 * 补答轮：让位时交出去的专家批次由这一轮接回，事件与普通轮同一套处理（标头是第一帧答案 token，
 * 专家进度照常上屏，结束后 done 照常定稿）。
 * 只由 settle() 在本地空闲时发起。后端占线（另一个标签页正跑着用户的轮）不让位、直接拒，
 * 这边转后台轮询等那轮结束再来；没欠账（被别的标签页接走 / 后端重启丢了队列）就清标记作罢。
 */
async function runDeferred(sid: string) {
  if (abortCtrl || state.sessionId !== sid) return;
  stopPolling();
  set({ loading: true, background: false });
  const abort = new AbortController();
  abortCtrl = abort;
  let busy = false;
  try {
    await workbenchApi.deferred(sid, e => {
      handleEvent(e);
      // 这一轮真出了答案（含中断的半截）：打上补答标，实时气泡与刷新后回放的补答行一个待遇
      if (e.type === 'done' && !e.deferred) updateItems(markLastAnswerDeferred);
    }, abort.signal);
  } catch (err) {
    if (!abort.signal.aborted) {
      const code = err instanceof ApiError ? err.code : 0;
      if (code === CHAT_ERROR.ALREADY_RUNNING) {
        busy = true;
      } else if (code === CHAT_ERROR.NOTHING_DEFERRED) {
        deferredPending = false;
      } else {
        // 失败的那一单已经出队（后端名额到手才出队），不会再来一遍；如实报错，用户重问即可
        deferredPending = false;
        const msg = (err as Error).message;
        updateItems(prev => [...prev, msg
          ? { kind: 'error', message: msg }
          : { kind: 'error', message: 'err.disconnected', keyed: true }]);
        if (code === CHAT_ERROR.CONFIG_MISSING || code === CHAT_ERROR.CONFIG_INVALID) {
          set({ needsConfig: true });
        }
      }
    }
  } finally {
    if (abortCtrl === abort) {
      abortCtrl = null;
      if (busy && state.sessionId) {
        set({ loading: true, background: true });
        startPolling(state.sessionId);
      } else {
        set({ loading: false });
        settle();
      }
    }
  }
}

/**
 * 重新生成最后一条回答。
 * <p>
 * 后端把模型侧上下文回退到那条提问之前、用原提问重跑，提问行留在库里不动——所以这里也只抹掉
 * 最后一次提问<b>之后</b>的内容（旧答案与那一轮的工作过程），提问气泡原样留着。
 * <p>
 * 抹除放在<b>第一个事件到达之后</b>：准入被拒（2206/占线/没配模型）时一个事件都不会来，
 * 屏幕上的旧答案就该原样留着——先抹再发的话，被拒的用户会平白丢掉一条好答案。
 */
async function regenerate() {
  const sid = state.sessionId;
  // background=有轮在别处跑、正靠轮询等它结束。这时候重生成会把轮询掐掉，那轮的答案再没人回放
  if (!sid || state.loading || state.background || abortCtrl) return;
  const cutAt = lastUserIndex(state.items);
  if (cutAt < 0) return;
  stopPolling();
  set({ loading: true });
  const abort = new AbortController();
  abortCtrl = abort;
  // 抹除前的快照：这一轮没跑出答案就原样接回去。后端是新答案落库之后才删旧答案的，
  // 库里那条一直还在，本地不该先一步空掉——那样用户看着像把好答案点没了
  let snapshot: ChatItem[] | null = null;
  let answered = false;
  try {
    await workbenchApi.regenerate(sid, e => {
      // 抹除等到第一个事件才做：准入被拒（2206/占线/没配模型）时一个事件都不会来，
      // 屏幕上的旧答案就该原样留着
      if (!snapshot) {
        snapshot = state.items;
        updateItems(prev => dropTurnOutput(prev, cutAt));
      }
      // 重生成轮在后端是不可让位的，所以 done 一到就是这一轮真出了答案
      if (e.type === 'done') answered = true;
      handleEvent(e);
    }, abort.signal);
  } catch (err) {
    if (!abort.signal.aborted) {
      const restore = snapshot;
      if (restore && !answered) updateItems(() => restore);
      const code = err instanceof ApiError ? err.code : 0;
      const msg = (err as Error).message;
      updateItems(prev => [...prev,
        code === CHAT_ERROR.REGENERATE_UNAVAILABLE
          ? { kind: 'error', message: 'err.regenUnavailable', keyed: true }
          : msg
            ? { kind: 'error', message: msg }
            : { kind: 'error', message: 'err.regenFailed', keyed: true },
      ]);
      if (code === CHAT_ERROR.CONFIG_MISSING || code === CHAT_ERROR.CONFIG_INVALID) {
        set({ needsConfig: true });
      }
    }
  } finally {
    if (abortCtrl === abort) {
      abortCtrl = null;
      set({ loading: false });
      settle();
    }
  }
}

/**
 * 请后端在下一个检查点收尾这一轮。
 * <p>
 * <b>不 abort 本地的流</b>：收尾的 done 事件还要靠它把半截答案定稿、把读数带回来。
 * 返回 false=后端说没有轮在跑（按钮点晚了，这一轮其实已经结束）。
 */
async function cancelRun(): Promise<boolean> {
  const sid = state.sessionId;
  if (!sid || !state.loading) return false;
  try {
    return await workbenchApi.cancel(sid);
  } catch {
    return false;   // 网络抖动或那轮刚好结束，都按"点晚了"处理
  }
}

/** 最后一条用户提问的下标；没有提问返回 -1 */
function lastUserIndex(items: ChatItem[]): number {
  for (let i = items.length - 1; i >= 0; i--) {
    if (items[i].kind === 'user') return i;
  }
  return -1;
}

/**
 * 砍掉 cutAt 那条提问之后的本轮产物（旧答案、工作过程、确认卡、报错）。
 * <p>
 * 切点在发起时就记死：等首个事件到达才抹，期间用户可能又发了一条（后端占线会让它排队），
 * 那条气泡留着。表单卡不论填没填完都留着——它是用户自己在卡上点出来的动作，
 * 已落地的那些（已唤醒／已复盘／已留言）钱都花掉了，而卡是纯前端态、抹了刷新也回不来。
 */
function dropTurnOutput(prev: ChatItem[], cutAt: number): ChatItem[] {
  return [
    ...prev.slice(0, cutAt + 1),
    ...prev.slice(cutAt + 1).filter(it => it.kind === 'user' || it.kind === 'form'),
  ];
}

/** 续发排队消息：气泡已上屏，去掉排队标记后不再重复上屏（失败各自报错，不阻塞后面的） */
function drainQueue() {
  const next = sendQueue.shift();
  if (next == null) return;
  // 按 id 解自己那只气泡：无气泡的自动指令没有 id 对应的条目，天然什么都不动
  if (next.bubbled) {
    updateItems(prev => prev.map(it =>
      it.kind === 'user' && it.queuedId === next.id ? { ...it, queued: false } : it));
  }
  void send(next.text, { noBubble: true, requeueAs: next, intent: next.intent });
}

/** 载入会话：消息回放 + 运行状态感知（还在跑→轮询等结果；欠着补答→立刻接回；新消息照常可排队） */
async function openSession(sid: string) {
  abortCtrl?.abort();
  abortCtrl = null;
  stopPolling();
  sendQueue = [];   // 排队的消息属于上一个会话语境，跟着带过去只会答非所问
  const [msgs, status] = await Promise.all([
    workbenchApi.sessionMessages(sid),
    workbenchApi.sessionStatus(sid).catch(() => ({ running: false, pending: false })),
  ]);
  // await 期间用户已发起新对话流：别用旧快照覆盖在途状态
  if (abortCtrl) return;
  deferredPending = status.pending;   // 欠账归会话：以后端口径为准
  // 表单卡是 trader 动作、不属于哪个会话（跟排队消息不同），换会话也带过去，别抹掉填一半的
  const forms = pendingForms();
  setSession(sid);
  set({ items: [...toItems(msgs), ...forms], loading: status.running, background: status.running });
  if (status.running) startPolling(sid);
  else settle();
}

/** HITL 决策：批准→登记授权→自动补发 resumeMessage 恢复执行；拒绝→仅登记。 */
async function hitlDecide(requestId: string, approved: boolean) {
  // 按 requestId 认卡而不是下标：让位说明行会 splice 到 items 中间，其后所有条目下标整体错位
  const item = state.items.find(it => it.kind === 'hitl' && it.requestId === requestId);
  if (item?.kind !== 'hitl' || !state.sessionId) return;
  await workbenchApi.approve(state.sessionId, approved, requestId);
  updateItems(prev => prev.map(it =>
    it.kind === 'hitl' && it.requestId === requestId ? { ...it, status: approved ? 'approved' : 'rejected' } : it,
  ));
  if (!approved) return;
  // 续跑指令是批准这个动作的一部分，不是用户打的字：立在工作过程轨里，
  // 用 noBubble 发出去不上气泡——否则历史里会多出一句用户从没说过的话
  updateItems(prev => [...prev, { kind: 'progress', rid: nextRid(), text: HITL_RESUME_NOTE, keyed: true, active: false }]);
  await send(item.resumeMessage, { noBubble: true });
}

function newSession() {
  abortCtrl?.abort();
  abortCtrl = null;
  stopPolling();
  sendQueue = [];
  deferredPending = false;
  setSession(null);
  set({ items: [], loading: false, background: false });
}

export const chatStore = {
  subscribe(listener: () => void) {
    listeners.add(listener);
    return () => { listeners.delete(listener); };
  },
  getSnapshot(): ChatState {
    return state;
  },
  /** ChatPanel 首挂时调：store 空且有会话号→回放历史并感知运行状态（只跑一次） */
  init() {
    if (initialized) return;
    initialized = true;
    const sid = state.sessionId;
    // 本地弹的表单卡不算"聊过了"：只数非表单项，否则先点了动作按钮就再也回放不到历史
    if (!sid || state.items.some(it => it.kind !== 'form') || state.loading) return;
    void openSession(sid).catch(() => {});
  },
  getDraft() {
    return draft;
  },
  setDraft(text: string) {
    draft = text;
  },
  send,
  regenerate,
  cancelRun,
  openSession,
  hitlDecide,
  newSession,
  /** 入口按钮直接弹卡：跟模型弹的卡走同一条 items 通路，不经后端 */
  openForm(form: TraderFormKind, prefill?: Record<string, unknown>) {
    updateItems(prev => [...prev, { kind: 'form', id: nextFormId(), form, prefill, status: 'pending' }]);
  },
  /** 撤掉一条还没发出去的排队消息：气泡与队列条目共用同一个 id，两边一起走 */
  cancelQueued(queuedId: number) {
    const at = sendQueue.findIndex(q => q.id === queuedId);
    if (at >= 0) sendQueue.splice(at, 1);
    updateItems(prev => prev.filter(it => !(it.kind === 'user' && it.queuedId === queuedId)));
  },
  /** 用户关掉卡：卡留在对话里当痕迹（无 result），只是不再可填 */
  closeForm(id: string) {
    updateItems(prev => prev.map(it =>
      it.kind === 'form' && it.id === id ? { ...it, status: 'done' as const } : it));
  },
  /** 卡执行完：result 是给用户看的一行回执 */
  settleForm(id: string, result: string) {
    updateItems(prev => prev.map(it =>
      it.kind === 'form' && it.id === id ? { ...it, status: 'done' as const, result } : it));
  },
  /** 删除的是当前会话时清空回到全新状态 */
  clearIfCurrent(sid: string) {
    if (state.sessionId === sid) newSession();
  },
  pushError(message: string) {
    updateItems(prev => [...prev, { kind: 'error', message }]);
  },
  /** 引导条点掉/点了去配置后清标记，否则一直挂着 */
  clearNeedsConfig() {
    set({ needsConfig: false });
  },
};
