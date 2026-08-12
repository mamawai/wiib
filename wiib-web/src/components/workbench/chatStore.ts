import { ApiError, workbenchApi } from '../../api';
import type { WorkbenchChatMessage, WorkbenchEvent } from '../../types';

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

export type ChatItem =
  // queued=北辰忙时先上屏排队，本轮结束自动真发（同会话同 checkpoint，后端每用户并发=1，做不了真并发）
  | { kind: 'user'; content: string; queued?: boolean }
  | { kind: 'assistant'; content: string; streaming: boolean }
  // 专家过程流（不落历史）：视图层收进"工作过程"轨，折叠状态归视图管
  | { kind: 'expert'; agent: string; content: string; streaming: boolean }
  | { kind: 'agent'; node: string; agent: string }
  // 长工具阶段进度（深研判等）：最新一条亮着转圈，后续事件到达即熄灭
  | { kind: 'progress'; text: string; active: boolean }
  // requestId 存在 item 上：hitlDecide 按 index 取回本条再原样回传，服务端据此确认"点的是哪张卡"
  | { kind: 'hitl'; symbol: string; reason: string; requestId: string; resumeMessage: string; status: 'pending' | 'approved' | 'rejected' }
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
/** 北辰忙时排队的待发消息（气泡已上屏，只差真发） */
let sendQueue: string[] = [];

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

/** 进度行熄灭：token/新进度到达说明上个阶段已过去 */
function deactivateProgress(items: ChatItem[]): ChatItem[] {
  return items.some(it => it.kind === 'progress' && it.active)
    ? items.map(it => (it.kind === 'progress' && it.active ? { ...it, active: false } : it))
    : items;
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
        const last = next[next.length - 1];
        if (last?.kind === 'assistant' && last.streaming) {
          next[next.length - 1] = { ...last, content: last.content + e.text };
        } else {
          next.push({ kind: 'assistant', content: e.text, streaming: true });
        }
        return next;
      });
      break;
    case 'hitl_request':
      updateItems(prev => [...prev, {
        kind: 'hitl', symbol: e.symbol, reason: e.reason, requestId: e.requestId,
        resumeMessage: e.resumeMessage, status: 'pending',
      }]);
      break;
    case 'done':
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
          // 历史回放会整体重建 items：排队气泡还没进后端历史，得补回尾部再续发
          const queued = sendQueue.map(m => ({ kind: 'user' as const, content: m, queued: true }));
          set({ items: [...toItems(msgs), ...queued], loading: false, background: false });
          drainQueue();
        }
      } catch { /* 网络抖动下轮再试 */ }
    })();
  }, POLL_MS);
}

async function send(message: string, opts?: { silent?: boolean }) {
  const msg = message.trim();
  if (!msg) return;
  // 北辰忙着：消息上屏排队，本轮结束自动续发。真并发做不了——
  // 后端并发闸门每用户 1，且同会话两条图并跑会互相踩 checkpoint
  if (state.loading) {
    sendQueue.push(msg);
    updateItems(prev => [...prev, { kind: 'user', content: msg, queued: true }]);
    return;
  }
  stopPolling();
  if (!opts?.silent) updateItems(prev => [...prev, { kind: 'user', content: msg }]);
  set({ loading: true, background: false });
  const abort = new AbortController();
  abortCtrl = abort;
  try {
    await workbenchApi.chat(state.sessionId, msg, handleEvent, abort.signal);
  } catch (err) {
    if (!abort.signal.aborted) {
      updateItems(prev => [...prev, { kind: 'error', message: (err as Error).message || '连接中断，可直接重问续聊' }]);
      // 配置类错误光显一行红字没用，用户得知道去哪儿改——置标记让面板亮"去配置"引导条
      const code = err instanceof ApiError ? err.code : 0;
      if (code === CHAT_ERROR.CONFIG_MISSING || code === CHAT_ERROR.CONFIG_INVALID) {
        set({ needsConfig: true });
      }
    }
  } finally {
    // 被 openSession/newSession 主动掐掉时它们各自接管状态，这里不抢
    if (abortCtrl === abort) {
      abortCtrl = null;
      set({ loading: false });
      drainQueue();
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
  const [msgs, running] = await Promise.all([
    workbenchApi.sessionMessages(sid),
    workbenchApi.sessionStatus(sid).catch(() => false),
  ]);
  // await 期间用户已发起新对话流：别用旧快照覆盖在途状态
  if (abortCtrl) return;
  setSession(sid);
  set({ items: toItems(msgs), loading: running, background: running });
  if (running) startPolling(sid);
}

/** HITL 决策：批准→登记授权→自动补发 resumeMessage 恢复执行；拒绝→仅登记。 */
async function hitlDecide(index: number, approved: boolean) {
  const item = state.items[index];
  if (item?.kind !== 'hitl' || !state.sessionId) return;
  await workbenchApi.approve(state.sessionId, approved, item.requestId);
  updateItems(prev => prev.map((it, i) =>
    i === index && it.kind === 'hitl' ? { ...it, status: approved ? 'approved' : 'rejected' } : it,
  ));
  if (approved) await send(item.resumeMessage);
}

function newSession() {
  abortCtrl?.abort();
  abortCtrl = null;
  stopPolling();
  sendQueue = [];
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
    if (!sid || state.items.length || state.loading) return;
    void openSession(sid).catch(() => {});
  },
  send,
  openSession,
  hitlDecide,
  newSession,
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
