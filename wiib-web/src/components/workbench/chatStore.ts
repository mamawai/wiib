import { ApiError, workbenchApi } from '../../api';
import type { TraderFormKind, WorkbenchChatMessage, WorkbenchEvent } from '../../types';

/** 与后端 ErrorCode 对齐：2200 段是研判工作台（1600 段是 Crypto，别复用） */
export const CHAT_ERROR = {
  CONFIG_MISSING: 2201,
  CONFIG_INVALID: 2202,
  ALREADY_RUNNING: 2203,
  CAPACITY_FULL: 2204,
} as const;

/**
 * 工作台对话 store（模块级单例）：状态与 SSE 消费脱离组件生命周期——
 * 切页只是 ChatPanel 卸载，流在这里继续收、状态继续涨，切回来订阅即续显；
 * 整页刷新后 store 归零，靠 status 轮询发现"AI 还在后台跑"，结束拉历史补答案。
 */

/** trader 动作表单卡的三种类型（与后端 form_request 的 formType 一一对应） */
export type FormKind = TraderFormKind;

export type ChatItem =
  // queued=后端不让位（正在出答案）时先上屏排队，本轮结束自动真发；
  // 专家取数期间的新消息会触发后端让位直接插话，不走排队
  | { kind: 'user'; content: string; queued?: boolean }
  | { kind: 'assistant'; content: string; streaming: boolean }
  // 专家过程流（不落历史）：视图层收进"工作过程"轨，折叠状态归视图管
  | { kind: 'expert'; agent: string; content: string; streaming: boolean }
  | { kind: 'agent'; node: string; agent: string }
  // 长工具阶段进度（深研判等）：最新一条亮着转圈，后续事件到达即熄灭
  | { kind: 'progress'; text: string; active: boolean }
  // requestId 存在 item 上：hitlDecide 按它取回本条再原样回传，服务端据此确认"点的是哪张卡"
  | { kind: 'hitl'; symbol: string; reason: string; requestId: string; resumeMessage: string; status: 'pending' | 'approved' | 'rejected' }
  // trader 动作表单卡：模型只有弹卡的权，执行权归用户点击。纯前端态不落历史，id 本地发
  | { kind: 'form'; id: string; form: FormKind; prefill?: Record<string, unknown>; status: 'pending' | 'done'; result?: string }
  | { kind: 'error'; message: string };

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
/** 让位后立在原问题过程轨里的说明行（也当"这个问题已有交代"的标记，防重复插） */
const DEFERRED_NOTE = '专家仍在取数，这个问题的答案稍后自动补上';

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
/** 后端不让位（正在出答案）时排队的待发消息（气泡已上屏，只差真发） */
let sendQueue: string[] = [];
/** 有被让位的问题还没补答：流一收尾就转后台轮询，等补答落历史后整体回放补显 */
let deferredPending = false;
/** 表单卡只活在本地 items 里（不落历史），自增序号足够把几张卡区分开 */
let formSeq = 0;
function nextFormId() {
  return `form-${++formSeq}`;
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

/** 后端历史 → 对话项（专家过程/进度不落库，只回放 user/assistant） */
function toItems(messages: WorkbenchChatMessage[]): ChatItem[] {
  return messages.map(m => m.role === 'user'
    ? { kind: 'user' as const, content: m.content }
    : { kind: 'assistant' as const, content: m.content, streaming: false });
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

/**
 * 让位说明行插到被让位的那个问题名下：找第一个"其后既没有回答也没立过牌子"的 user 项，
 * 在下一个 user 项之前插入——被让位的问题在新消息上屏之后、done(deferred) 到达之前，
 * 所以直接 push 到末尾会挂错到新消息名下。
 */
function insertDeferredNote(items: ChatItem[]): ChatItem[] {
  for (let i = 0; i < items.length; i++) {
    const item = items[i];
    if (item.kind !== 'user' || item.queued) continue;
    let end = items.length;
    for (let j = i + 1; j < items.length; j++) {
      if (items[j].kind === 'user') { end = j; break; }
    }
    const settled = items.slice(i + 1, end).some(it =>
      it.kind === 'assistant' || (it.kind === 'progress' && it.text === DEFERRED_NOTE));
    if (!settled) {
      return [
        ...items.slice(0, end),
        { kind: 'progress', text: DEFERRED_NOTE, active: false },
        ...items.slice(end),
      ];
    }
  }
  return items;
}

function handleEvent(e: WorkbenchEvent) {
  switch (e.type) {
    case 'session':
      setSession(e.sessionId);
      break;
    case 'agent_start':
      updateItems(prev => [...prev, { kind: 'agent', node: e.node, agent: e.agent }]);
      break;
    case 'progress':
      updateItems(prev => [...deactivateProgress(prev), { kind: 'progress', text: e.text, active: true }]);
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
          next.push({ kind: 'expert', agent: e.agent, content: e.text, streaming: true });
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
        next.push({ kind: 'assistant', content: e.text, streaming: true });
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
    case 'done':
      if (e.deferred) {
        // 让位收尾：真答案由后端补答轮落历史，这里只在被让位的问题后面立块牌子，
        // 并标记 deferredPending——当前活跃流收尾时据此转入后台轮询等补答
        deferredPending = true;
        updateItems(prev => insertDeferredNote(deactivateProgress(prev)));
        break;
      }
      updateItems(prev => {
        const next = deactivateProgress(prev).map(it => {
          if (it.kind === 'assistant' && it.streaming) return { ...it, content: it.content || e.answer, streaming: false };
          if (it.kind === 'expert' && it.streaming) return { ...it, streaming: false };
          return it;
        });
        // 答案流没出现过（如调用上限截停）：done 里的兜底答案补成气泡，不然这轮白问
        let lastUser = -1;
        for (let j = next.length - 1; j >= 0; j--) {
          if (next[j].kind === 'user') { lastUser = j; break; }
        }
        const hasAnswer = next.slice(lastUser + 1).some(it => it.kind === 'assistant');
        if (!hasAnswer && e.answer) next.push({ kind: 'assistant', content: e.answer, streaming: false });
        return next;
      });
      break;
    case 'error':
      updateItems(prev => [...prev, { kind: 'error', message: e.message }]);
      break;
  }
}

function stopPolling() {
  if (pollTimer !== null) {
    window.clearInterval(pollTimer);
    pollTimer = null;
  }
}

/** 刷新后 AI 还在后台跑：SSE 已丢，轮询 status，结束即拉历史补出完整答案 */
function startPolling(sid: string) {
  stopPolling();
  pollTimer = window.setInterval(() => {
    void (async () => {
      try {
        const running = await workbenchApi.sessionStatus(sid);
        // 会话已切换或用户已开新一轮流式对话（abortCtrl 在挂）：本轮poll作废
        if (state.sessionId !== sid || abortCtrl) { stopPolling(); return; }
        if (!running) {
          stopPolling();
          const msgs = await workbenchApi.sessionMessages(sid);
          if (abortCtrl) return;
          // 后端说没有在跑也不欠补答了（status 口径含补答队列）：欠的账都已在历史里
          deferredPending = false;
          // 历史回放会整体重建 items：排队气泡和没填完的表单卡都不在后端历史里，得补回尾部
          const queued = sendQueue.map(m => ({ kind: 'user' as const, content: m, queued: true }));
          set({ items: [...toItems(msgs), ...queued, ...pendingForms()], loading: false, background: false });
          drainQueue();
        }
      } catch { /* 网络抖动下轮再试 */ }
    })();
  }, POLL_MS);
}

async function send(message: string, opts?: { silent?: boolean }) {
  const msg = message.trim();
  if (!msg) return;
  // 有轮在跑也直接真发：后端专家等待期会让位（用户消息优先，专家结果转入补答队列）；
  // 不可让位（正在出答案）会拒 2203，届时再回落本地排队——排不排队由后端仲裁，前端不预判
  const prevAbort = abortCtrl;
  const bubbleIndex = state.items.length;   // 本条气泡的位置，被拒时改标排队
  stopPolling();
  if (!opts?.silent) updateItems(prev => [...prev, { kind: 'user', content: msg }]);
  set({ loading: true, background: false });
  const abort = new AbortController();
  abortCtrl = abort;
  let fellBack = false;
  try {
    await workbenchApi.chat(state.sessionId, msg, handleEvent, abort.signal);
  } catch (err) {
    if (!abort.signal.aborted) {
      const code = err instanceof ApiError ? err.code : 0;
      if (code === CHAT_ERROR.ALREADY_RUNNING) {
        // 后端不让位：本条转入排队（silent=排队续发被拒，塞回队头保序）。
        // 本地有在跑的流就把主导权还给它；没有（刷新后后台轮在跑）就转后台轮询等那轮结束
        if (opts?.silent) {
          sendQueue.unshift(msg);
        } else {
          sendQueue.push(msg);
          updateItems(prev => prev.map((it, i) =>
            i === bubbleIndex && it.kind === 'user' ? { ...it, queued: true } : it));
        }
        if (prevAbort && !prevAbort.signal.aborted) {
          if (abortCtrl === abort) abortCtrl = prevAbort;
          return;
        }
        fellBack = true;
        return;
      }
      updateItems(prev => [...prev, { kind: 'error', message: (err as Error).message || '连接中断，可直接重问续聊' }]);
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
      } else if (deferredPending && state.sessionId) {
        // 本轮收尾但还欠着补答：转后台轮询，补答落历史后整体回放补显
        set({ loading: false, background: true });
        startPolling(state.sessionId);
      } else {
        set({ loading: false });
        drainQueue();
      }
    }
  }
}

/** 续发排队消息：气泡已上屏，去掉排队标记后以 silent 真发（失败各自报错，不阻塞后面的） */
function drainQueue() {
  const next = sendQueue.shift();
  if (next == null) return;
  updateItems(prev => {
    const i = prev.findIndex(it => it.kind === 'user' && it.queued);
    return i < 0 ? prev : prev.map((it, j) => (j === i && it.kind === 'user' ? { ...it, queued: false } : it));
  });
  void send(next, { silent: true });
}

/** 载入会话：消息回放 + 运行状态感知（还在跑→轮询等结果，新消息照常可排队） */
async function openSession(sid: string) {
  abortCtrl?.abort();
  abortCtrl = null;
  stopPolling();
  sendQueue = [];   // 排队的消息属于上一个会话语境，跟着带过去只会答非所问
  deferredPending = false;   // 欠账归会话：切走后靠 status 轮询口径重新感知
  const [msgs, running] = await Promise.all([
    workbenchApi.sessionMessages(sid),
    workbenchApi.sessionStatus(sid).catch(() => false),
  ]);
  // await 期间用户已发起新对话流：别用旧快照覆盖在途状态
  if (abortCtrl) return;
  // 表单卡是 trader 动作、不属于哪个会话（跟排队消息不同），换会话也带过去，别抹掉填一半的
  const forms = pendingForms();
  setSession(sid);
  set({ items: [...toItems(msgs), ...forms], loading: running, background: running });
  if (running) startPolling(sid);
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
  if (approved) await send(item.resumeMessage);
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
  send,
  openSession,
  hitlDecide,
  newSession,
  /** 入口按钮直接弹卡：跟模型弹的卡走同一条 items 通路，不经后端 */
  openForm(form: FormKind, prefill?: Record<string, unknown>) {
    updateItems(prev => [...prev, { kind: 'form', id: nextFormId(), form, prefill, status: 'pending' }]);
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
