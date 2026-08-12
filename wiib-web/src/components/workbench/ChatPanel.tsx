import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { Bot, ChevronLeft, ChevronRight, History, KeyRound, Loader2, MessageSquareText, RotateCcw, Send, ShieldQuestion, Trash2, X } from 'lucide-react';
import { workbenchApi } from '../../api';
import { Markdown } from '../Markdown';
import { cn, fmtDateTime } from '../../lib/utils';
import { chatStore, type ChatItem } from './chatStore';
import type { WorkbenchSessionSummary } from '../../types';

/** 调度中枢的对外名字：后端事件里仍叫 supervisor，只在展示层换 */
const HUB_NAME = '北辰';

const AGENT_CN: Record<string, string> = {
  market_agent: '市场专家',
  news_agent: '新闻专家',
  supervisor: HUB_NAME,
};

/** 输入区上方的快捷提问（点击直发） */
const SUGGESTS = ['BTC 现在市场结构怎么样？', '对 ETH 做一次深度研判', '你的 vol 预测战绩靠谱吗'];

/** 过程类条目（调度/专家/进度）归进工作过程轨；其余各自成块 */
const isRailKind = (it: ChatItem) => it.kind === 'agent' || it.kind === 'expert' || it.kind === 'progress';

type RailStep = { item: ChatItem; index: number };
type Block =
  | { kind: 'single'; item: ChatItem; index: number }
  | { kind: 'rail'; steps: RailStep[]; key: number };

/** 工作过程轨（方案A"轨道收纳"）：竖轨+节点，运行中默认展开，答案出来后默认收起 */
function ProcessRail({ steps, active, open, onToggle }: {
  steps: RailStep[]; active: boolean; open: boolean; onToggle: () => void;
}) {
  return (
    <div className="max-w-[95%]">
      <button
        onClick={onToggle}
        className="flex items-center gap-1.5 text-[11px] font-bold text-muted-foreground hover:text-foreground py-0.5"
      >
        <ChevronRight className={cn('w-3 h-3 transition-transform duration-200', open && 'rotate-90')} />
        工作过程 · {steps.length} 步
        {active && <Loader2 className="w-3 h-3 animate-spin text-primary" />}
      </button>
      <div className={cn(
        'grid transition-[grid-template-rows] duration-300 ease-out',
        open ? 'grid-rows-[1fr]' : 'grid-rows-[0fr]',
      )}>
        <div className="overflow-hidden min-h-0">
          <div className="ml-[5px] mt-1.5 border-l-2 border-border pl-3.5 space-y-2.5 py-0.5">
            {steps.map(({ item, index }) => {
              const hot = (item.kind === 'expert' && item.streaming) || (item.kind === 'progress' && item.active);
              return (
                <div key={index} className="relative text-[11.5px] leading-relaxed text-muted-foreground">
                  <span className={cn(
                    'absolute -left-[18.5px] top-[5px] w-[7px] h-[7px] rounded-full border-2',
                    hot ? 'bg-primary border-primary shadow-[0_0_6px_var(--color-primary)]' : 'bg-card border-border',
                  )} />
                  {item.kind === 'agent' && (
                    <span>
                      <span className="font-bold text-foreground">{HUB_NAME}</span>
                      {' → '}
                      <span className="font-bold text-primary">{AGENT_CN[item.agent] || AGENT_CN[item.node] || item.agent || item.node}</span>
                      {' 接管分析'}
                    </span>
                  )}
                  {item.kind === 'expert' && (
                    <>
                      <span className="font-bold text-foreground">{AGENT_CN[item.agent] || item.agent}</span> 取数分析
                      <div className="mt-1 rounded-lg border border-border bg-card-2 px-2.5 py-2 text-[11px] max-h-44 overflow-y-auto">
                        <Markdown content={item.content} />
                      </div>
                    </>
                  )}
                  {item.kind === 'progress' && (
                    <span className={cn(hot && 'text-foreground font-bold')}>{item.text}</span>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </div>
  );
}

/** HITL 确认卡：深研判 3 次深模型调用是贵操作，人工把关后 agent 才继续。 */
function HitlCard({ item, onDecide, busy }: {
  item: Extract<ChatItem, { kind: 'hitl' }>;
  onDecide: (approved: boolean) => void;
  busy: boolean;
}) {
  return (
    <div className={cn(
      'rounded-xl border border-border border-l-[3px] p-3.5 space-y-2.5',
      item.status === 'pending' ? 'border-l-primary bg-card' : 'border-l-muted-foreground/30 opacity-70',
    )}>
      <div className="flex items-center gap-2">
        <ShieldQuestion className="w-4 h-4 text-primary shrink-0" />
        <span className="text-xs font-black">深度研判确认</span>
        <span className="text-[10px] font-bold px-2 py-0.5 rounded-full bg-primary/10 text-primary">{item.symbol}</span>
      </div>
      <p className="text-xs text-muted-foreground leading-relaxed">{item.reason}</p>
      {item.status === 'pending' ? (
        <div className="flex gap-2">
          <button
            disabled={busy}
            onClick={() => onDecide(true)}
            className="border border-border hover:bg-primary/8 flex-1 py-1.5 rounded-lg text-xs font-bold text-primary disabled:opacity-50 transition-colors"
          >
            批准执行
          </button>
          <button
            disabled={busy}
            onClick={() => onDecide(false)}
            className="border border-border hover:bg-surface-hover flex-1 py-1.5 rounded-lg text-xs font-bold text-muted-foreground disabled:opacity-50 transition-colors"
          >
            拒绝
          </button>
        </div>
      ) : (
        <p className="text-[10px] font-bold text-muted-foreground">
          {item.status === 'approved' ? '✓ 已批准 · 深研判继续执行' : '✗ 已拒绝 · 本次跳过深研判'}
        </p>
      )}
    </div>
  );
}

interface ChatPanelProps {
  /** 停靠壳（ChatDock）传入：点头部 X 关面板 */
  onClose?: () => void;
  /** 后端报配置缺失/不可用时，引导条按钮跳模型配置页 */
  onGoConfig?: () => void;
}

/**
 * 对话面板：chatStore 的视图层（SSE 消费在 store，关面板/切页不中断）。
 * 布局是"轨道收纳"：过程条目收进可折叠的工作过程轨，答案卡是唯一主角。
 * 自身不带卡片外壳，由 ChatDock 决定浮窗还是全屏。
 */
export function ChatPanel({ onClose, onGoConfig }: ChatPanelProps) {
  const { items, loading, background, sessionId, needsConfig } = useSyncExternalStore(chatStore.subscribe, chatStore.getSnapshot);
  const [input, setInput] = useState('');
  const [hitlBusy, setHitlBusy] = useState(false);
  const [showHistory, setShowHistory] = useState(false);
  const [sessions, setSessions] = useState<WorkbenchSessionSummary[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  // 工作过程轨的手动开合覆盖（键=轨首条目在 items 里的下标）；不覆盖时跟随默认（运行中开、答案出收）
  const [railOverride, setRailOverride] = useState<Record<number, boolean>>({});
  const scrollRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [items]);

  // 首挂：回放历史 + 感知后台运行状态（store 级幂等；关面板再开时 store 状态还在，直接续显）
  useEffect(() => { chatStore.init(); }, []);

  // 换会话后 items 整体重建，旧下标失义
  useEffect(() => { setRailOverride({}); }, [sessionId]);

  /** 相邻过程条目并轨：user/assistant/hitl/error 断轨 */
  const blocks = useMemo(() => {
    const out: Block[] = [];
    items.forEach((item, index) => {
      if (isRailKind(item)) {
        const last = out[out.length - 1];
        if (last?.kind === 'rail') last.steps.push({ item, index });
        else out.push({ kind: 'rail', steps: [{ item, index }], key: index });
      } else {
        out.push({ kind: 'single', item, index });
      }
    });
    return out;
  }, [items]);

  const openHistory = useCallback(() => {
    setShowHistory(true);
    setHistoryLoading(true);
    workbenchApi.sessions()
      .then(setSessions)
      .catch(() => setSessions([]))
      .finally(() => setHistoryLoading(false));
  }, []);

  /** 载入历史会话：消息回放 + sessionId 复用（续聊上下文在后端，继续聊自动带全上下文） */
  const openSession = useCallback(async (s: WorkbenchSessionSummary) => {
    // 点的就是当前在跑的会话：直接关列表回对话，别把在途流掐了重载
    if (s.sessionId === sessionId && loading) {
      setShowHistory(false);
      return;
    }
    setHistoryLoading(true);
    try {
      await chatStore.openSession(s.sessionId);
      setShowHistory(false);
    } catch { /* 拉取失败保持列表 */ } finally {
      setHistoryLoading(false);
    }
  }, [sessionId, loading]);

  const handleSend = useCallback((text?: string) => {
    const msg = (text ?? input).trim();
    if (!msg) return;
    setInput('');
    if (inputRef.current) inputRef.current.style.height = 'auto';
    void chatStore.send(msg);
  }, [input]);

  /** HITL 决策交给 store；busy 只是本地防连点。 */
  const handleHitl = useCallback(async (idx: number, approved: boolean) => {
    setHitlBusy(true);
    try {
      await chatStore.hitlDecide(idx, approved);
    } catch (err) {
      chatStore.pushError((err as Error).message || '确认失败，请重试');
    } finally {
      setHitlBusy(false);
    }
  }, []);

  /** 删除会话：列表移除；删的是当前会话时 store 一并清空。 */
  const removeSession = useCallback(async (s: WorkbenchSessionSummary) => {
    try {
      await workbenchApi.deleteSession(s.sessionId);
      setSessions(prev => prev.filter(x => x.sessionId !== s.sessionId));
      chatStore.clearIfCurrent(s.sessionId);
    } catch { /* 删除失败保持原样 */ }
  }, []);

  const handleNewSession = useCallback(() => {
    chatStore.newSession();
    setShowHistory(false);
  }, []);

  // 有 token 流或亮着的进度行时，答案卡/过程轨自带动效，底部指示条只在"纯静默"时出现
  const streamingNow = items.some(it =>
    ((it.kind === 'assistant' || it.kind === 'expert') && it.streaming) || (it.kind === 'progress' && it.active));

  return (
    <div className="flex flex-col h-full min-h-0">
      {/* 面板头 */}
      <div className="flex items-center gap-2 px-4 py-3 border-b border-border shrink-0">
        <Bot className="w-4.5 h-4.5 text-primary" />
        <span className="text-sm font-black">研判对话</span>
        <span className="text-[10px] text-muted-foreground hidden sm:inline">{HUB_NAME}调度 · 关窗后台继续</span>
        <button
          onClick={() => showHistory ? setShowHistory(false) : openHistory()}
          className={cn(
            'ml-auto border border-border hover:bg-surface-hover w-7 h-7 rounded-lg flex items-center justify-center hover:text-primary',
            showHistory ? 'text-primary' : 'text-muted-foreground',
          )}
          title="历史对话"
        >
          <History className="w-3.5 h-3.5" />
        </button>
        <button
          onClick={handleNewSession}
          className="border border-border hover:bg-surface-hover w-7 h-7 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary"
          title="新会话"
        >
          <RotateCcw className="w-3.5 h-3.5" />
        </button>
        {onClose && (
          <button
            onClick={onClose}
            className="border border-border hover:bg-surface-hover w-7 h-7 rounded-lg flex items-center justify-center text-muted-foreground hover:text-foreground"
            title="关闭"
            aria-label="关闭对话面板"
          >
            <X className="w-3.5 h-3.5" />
          </button>
        )}
      </div>

      {/* 内容区：消息流 + 输入区；历史列表是盖在上面的右滑叠层 */}
      <div className="relative flex-1 min-h-0 flex flex-col">
        {/* 消息流 */}
        <div ref={scrollRef} className="flex-1 overflow-y-auto px-4 py-3 space-y-3">
          {items.length === 0 && (
            <div className="h-full flex flex-col items-center justify-center gap-3 text-center px-6">
              <div className="w-12 h-12 rounded-full border border-border bg-background flex items-center justify-center text-muted-foreground/60">
                <Bot className="w-6 h-6" />
              </div>
              <p className="text-sm text-muted-foreground">问点什么——下面的快捷提问可以直接点</p>
              <p className="text-[10px] text-muted-foreground/70">市场结构 · 深度研判 · 预测战绩，都归{HUB_NAME}调度</p>
            </div>
          )}
          {blocks.map(block => {
            if (block.kind === 'rail') {
              const active = block.steps.some(({ item }) =>
                (item.kind === 'expert' && item.streaming) || (item.kind === 'progress' && item.active));
              // 轨后面出现过任何条目（答案/新提问/HITL）即视为完结 → 默认收起
              const lastIdx = block.steps[block.steps.length - 1].index;
              const settled = !active && items.length - 1 > lastIdx;
              const open = railOverride[block.key] ?? !settled;
              return (
                <ProcessRail
                  key={block.key}
                  steps={block.steps}
                  active={active}
                  open={open}
                  onToggle={() => setRailOverride(prev => ({ ...prev, [block.key]: !open }))}
                />
              );
            }
            const { item, index } = block;
            switch (item.kind) {
              case 'user':
                return (
                  <div key={index} className="flex justify-end">
                    <div className={cn(
                      'max-w-[85%] rounded-2xl rounded-br-md bg-primary/10 px-3.5 py-2.5 text-sm whitespace-pre-wrap leading-relaxed',
                      item.queued && 'opacity-60',
                    )}>
                      {item.content}
                      {item.queued && (
                        <span className="block text-right text-[9px] font-bold text-muted-foreground mt-1">排队中 · 本轮结束后发出</span>
                      )}
                    </div>
                  </div>
                );
              case 'assistant':
                return (
                  <div key={index} className="max-w-[95%] rounded-xl border border-border bg-card-2 overflow-hidden">
                    <div className="flex items-center gap-2 px-3.5 py-1.5 border-b border-border">
                      <span className="w-5 h-5 rounded-md bg-primary/10 text-primary flex items-center justify-center">
                        <Bot className="w-3 h-3" />
                      </span>
                      <span className="microlabel font-bold">{HUB_NAME}</span>
                      {item.streaming && <Loader2 className="w-3 h-3 animate-spin text-muted-foreground ml-auto" />}
                    </div>
                    <div className="px-3.5 py-2.5 text-sm">
                      <Markdown content={item.content} />
                      {item.streaming && item.content && (
                        <span className="inline-block w-1.5 h-3.5 bg-primary/80 rounded-[1px] ml-0.5 align-middle animate-pulse" />
                      )}
                    </div>
                  </div>
                );
              case 'hitl':
                return <HitlCard key={index} item={item} busy={hitlBusy} onDecide={a => void handleHitl(index, a)} />;
              case 'error':
                return (
                  <p key={index} className="text-[11px] text-destructive/80 text-center py-1">{item.message}</p>
                );
            }
          })}
          {loading && !streamingNow && (
            <div className="flex items-center gap-2 text-xs text-muted-foreground">
              <Loader2 className="w-3.5 h-3.5 animate-spin" />
              {background ? `${HUB_NAME}在后台继续研判，完成后自动展示答案` : `${HUB_NAME}分析问题中...`}
            </div>
          )}
        </div>

        {/* 配置引导条：后端报 2201/2202 时出现——光一行红字用户不知道去哪儿改（配置在 AI 页模型配置 Tab） */}
        {needsConfig && (
          <div className="mx-3 mb-2 rounded-lg border border-warning/40 bg-warning/10 px-3 py-2 flex items-center gap-2 shrink-0">
            <KeyRound className="w-3.5 h-3.5 text-warning shrink-0" />
            <span className="text-[11px] font-bold flex-1 text-left">模型配置缺失或不可用</span>
            <button
              onClick={() => { chatStore.clearNeedsConfig(); onGoConfig?.(); }}
              className="text-[11px] font-bold text-primary shrink-0 hover:underline"
            >
              去配置
            </button>
            <button
              onClick={() => chatStore.clearNeedsConfig()}
              aria-label="忽略"
              className="text-muted-foreground/60 hover:text-foreground shrink-0"
            >
              <X className="w-3.5 h-3.5" />
            </button>
          </div>
        )}

        {/* 输入区：快捷提问 + 多行自适应输入 + 状态行 */}
        <div className="border-t border-border px-3 pt-2 pb-2.5 shrink-0 space-y-2">
          <div className="flex gap-1.5 overflow-x-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
            {SUGGESTS.map(q => (
              <button
                key={q}
                onClick={() => handleSend(q)}
                className="shrink-0 border border-border rounded-full px-2.5 py-1 text-[11px] font-medium text-muted-foreground hover:text-foreground hover:bg-surface-hover transition-colors"
              >
                {q}
              </button>
            ))}
          </div>
          <div className="flex items-end gap-2 rounded-xl border border-border bg-card-2 px-3 py-2 transition-colors focus-within:border-primary/50">
            <textarea
              ref={inputRef}
              rows={1}
              value={input}
              onChange={e => setInput(e.target.value)}
              onInput={e => {
                const t = e.currentTarget;
                t.style.height = 'auto';
                t.style.height = `${Math.min(t.scrollHeight, 96)}px`;
              }}
              onKeyDown={e => {
                if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                  e.preventDefault();
                  handleSend();
                }
              }}
              placeholder={loading ? `${HUB_NAME}工作中——继续输入会排队发送` : '问市场、要研判、查战绩…'}
              className="flex-1 bg-transparent text-sm leading-relaxed resize-none max-h-24 focus:outline-none placeholder:text-muted-foreground/60"
            />
            <button
              onClick={() => handleSend()}
              disabled={!input.trim()}
              className="w-8 h-8 rounded-lg bg-primary text-primary-foreground flex items-center justify-center shrink-0 hover:brightness-105 active:scale-95 transition-all disabled:opacity-40 disabled:active:scale-100"
              aria-label="发送"
            >
              <Send className="w-4 h-4" />
            </button>
          </div>
          <div className="flex justify-between px-0.5 text-[10px] text-muted-foreground/60">
            <span>Enter 发送 · Shift+Enter 换行</span>
            <span className="num">{loading ? `${HUB_NAME}工作中 · 新消息将排队` : `${HUB_NAME}就绪`}</span>
          </div>
        </div>

        {/* 历史会话叠层：右滑入，常驻挂载才有出入动画 */}
        <div className={cn(
          'absolute inset-0 z-10 bg-card flex flex-col transition-transform duration-300 ease-[cubic-bezier(.16,1,.3,1)]',
          showHistory ? 'translate-x-0' : 'translate-x-full pointer-events-none',
        )}>
          <div className="flex items-center gap-2 px-3.5 py-2.5 shrink-0">
            <button
              onClick={() => setShowHistory(false)}
              className="border border-border hover:bg-surface-hover w-7 h-7 rounded-lg flex items-center justify-center text-muted-foreground hover:text-foreground"
              aria-label="返回对话"
            >
              <ChevronLeft className="w-3.5 h-3.5" />
            </button>
            <span className="text-xs font-black">历史对话</span>
          </div>
          <div className="flex-1 overflow-y-auto px-3.5 pb-2 space-y-2">
            {historyLoading ? (
              <div className="flex items-center justify-center gap-2 py-10 text-xs text-muted-foreground">
                <Loader2 className="w-4 h-4 animate-spin" /> 加载历史会话...
              </div>
            ) : sessions.length === 0 ? (
              <div className="flex flex-col items-center justify-center gap-2 py-10 text-center">
                <MessageSquareText className="w-8 h-8 text-muted-foreground/40" />
                <p className="text-xs text-muted-foreground">还没有历史对话</p>
              </div>
            ) : (
              sessions.map(s => (
                <div
                  key={s.sessionId}
                  role="button"
                  tabIndex={0}
                  onClick={() => void openSession(s)}
                  onKeyDown={e => e.key === 'Enter' && void openSession(s)}
                  className={cn(
                    'w-full text-left rounded-xl border px-3.5 py-2.5 space-y-1 hover:bg-surface-hover transition-colors cursor-pointer',
                    s.sessionId === sessionId ? 'border-primary/45 bg-card-2' : 'border-border bg-card',
                  )}
                >
                  <div className="flex items-center gap-2">
                    <div className="text-xs font-bold truncate flex-1">{s.title}</div>
                    {s.sessionId === sessionId && (
                      <span className="text-[9px] font-black tracking-wider text-primary shrink-0">当前</span>
                    )}
                    <button
                      onClick={e => { e.stopPropagation(); void removeSession(s); }}
                      className="shrink-0 w-6 h-6 rounded-md flex items-center justify-center text-muted-foreground/50 hover:text-loss hover:bg-loss/10 transition-colors"
                      title="删除会话"
                      aria-label="删除会话"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  </div>
                  <div className="text-[10px] text-muted-foreground flex items-center gap-2">
                    <span>{fmtDateTime(s.lastAt)}</span>
                    <span>· {s.messageCount} 条</span>
                  </div>
                </div>
              ))
            )}
          </div>
          <button
            onClick={handleNewSession}
            className="mx-3.5 mb-3 shrink-0 border border-dashed border-border rounded-xl py-2 text-xs font-bold text-primary hover:bg-primary/6 transition-colors"
          >
            + 开新对话
          </button>
        </div>
      </div>
    </div>
  );
}
