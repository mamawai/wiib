import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { ArrowDown, BookOpenCheck, Bot, ChevronsDownUp, ChevronsUpDown, Cpu, History, KeyRound, Loader2, Maximize2, MessageSquarePlus, Minimize2, RotateCcw, Square, X, Zap } from 'lucide-react';
import { workbenchApi } from '../../api';
import { useClickOutside } from '../../hooks/useClickOutside';
import { cn } from '../../lib/utils';
import { chatStore } from './chatStore';
import { AssistantAnswer, HitlCard, ProcessRail, UserBubble } from './ChatMessages';
import { groupBlocks, HUB_NAME } from './chatView';
import { ChatComposer } from './ChatComposer';
import { SessionHistory } from './SessionHistory';
import { TraderFormCard } from './TraderFormCards';
import type { TraderFormKind, WorkbenchSessionSummary } from '../../types';

/** trader 动作入口的三项：点了只是把表单卡放进对话，执行要在卡上再按一次 */
const TRADER_ACTIONS: { form: TraderFormKind; label: string; icon: typeof Zap }[] = [
  { form: 'note', label: '给它留言', icon: MessageSquarePlus },
  { form: 'wake', label: '手动唤醒', icon: Zap },
  { form: 'review', label: '立即复盘', icon: BookOpenCheck },
];

/** 会话标题截断长度：与后端 ChatHistoryService.TITLE_MAX 同口径，历史列表与面板头对得上 */
const TITLE_MAX = 40;

/** 贴底判定的容差：小于它就算"用户在看最新内容"，新内容照常跟随滚动 */
const STICK_PX = 80;

interface ChatPanelProps {
  /** 停靠壳（ChatDock）传入：点头部 X 关面板 */
  onClose?: () => void;
  /** 后端报配置缺失/不可用时，引导条按钮跳模型配置页 */
  onGoConfig?: () => void;
  /** PC 全屏开关。不传就不出这个按钮——移动端面板本来就是铺满视口的全屏层 */
  fullscreen?: boolean;
  onToggleFullscreen?: () => void;
}

/**
 * 对话面板：chatStore 的视图层（SSE 消费在 store，关面板/切页不中断）。
 * 自身不带卡片外壳，由 ChatDock 决定浮窗还是全屏。
 * <p>
 * 版式：用户提问是右侧气泡，agent 回答是<b>无框正文</b>（署名行 + 正文 + 脚注读数），
 * 过程条目收进可折叠的工作过程轨。答案是这一屏唯一的主角，所以它不套卡片。
 */
export function ChatPanel({ onClose, onGoConfig, fullscreen, onToggleFullscreen }: ChatPanelProps) {
  const { items, loading, background, sessionId, needsConfig } = useSyncExternalStore(chatStore.subscribe, chatStore.getSnapshot);
  // 在途的那张确认卡（按 requestId 认）。面板里可能同时挂着几张，用一个布尔会把别的卡一起禁掉
  const [hitlBusy, setHitlBusy] = useState<{ requestId: string; approved: boolean } | null>(null);
  const [actionMenu, setActionMenu] = useState(false);
  const actionMenuRef = useRef<HTMLDivElement>(null);
  const [showHistory, setShowHistory] = useState(false);
  const [sessions, setSessions] = useState<WorkbenchSessionSummary[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  // 工作过程轨的手动开合，键是轨首条目的 rid（条目挪位置也认得回来）。默认展开：轨里全是本次
  // 会话实时产生的条目（专家过程不落库），用户正看着专家分析时把它收起来是最恼人的一种"自作主张"
  const [railClosed, setRailClosed] = useState<Record<string, boolean>>({});
  const scrollRef = useRef<HTMLDivElement>(null);
  // 用户往回翻时不再强行拉到底：长回答无框铺开后，往回看是常态
  const [stuckToBottom, setStuckToBottom] = useState(true);
  // 点了停止、还没等到收尾。收尾后 loading 落下即复位；新一轮开跑也要复位——
  // 排队消息续发这类路径下 loading 中间不会落下，只认边沿会让按钮永远卡在"收尾中"
  const [stopping, setStopping] = useState(false);
  useEffect(() => { if (!loading) setStopping(false); }, [loading]);

  const onScroll = useCallback(() => {
    const el = scrollRef.current;
    if (el) setStuckToBottom(el.scrollHeight - el.scrollTop - el.clientHeight < STICK_PX);
  }, []);

  const scrollToBottom = useCallback(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
    setStuckToBottom(true);
  }, []);

  useEffect(() => {
    if (stuckToBottom) scrollToBottom();
  }, [items, stuckToBottom, scrollToBottom]);

  // 首挂：回放历史 + 感知后台运行状态（store 级幂等；关面板再开时 store 状态还在，直接续显）
  useEffect(() => { chatStore.init(); }, []);

  // ref 包住触发按钮本身，否则点按钮会同时触发 onOutside 与 onClick，菜单一闪就关
  useClickOutside(actionMenuRef, () => setActionMenu(false), actionMenu);

  // 换会话后旧轨全不在了，攒着的开合状态没有对应对象；滚动位置也该回到最新一条
  useEffect(() => { setRailClosed({}); setStuckToBottom(true); }, [sessionId]);

  const blocks = useMemo(() => groupBlocks(items), [items]);

  /** 面板头显示当前会话标题：首条提问截断，与后端历史列表同口径 */
  const title = useMemo(() => {
    const first = items.find(it => it.kind === 'user');
    if (!first || first.kind !== 'user') return null;
    const text = first.content.replace(/\s+/g, ' ').trim();
    return text.length > TITLE_MAX ? `${text.slice(0, TITLE_MAX)}…` : text;
  }, [items]);

  const railKeys = useMemo(
    () => blocks.filter(b => b.kind === 'rail').map(b => b.key),
    [blocks],
  );
  const anyRailOpen = railKeys.some(k => !railClosed[k]);
  const toggleAllRails = useCallback(() => {
    setRailClosed(Object.fromEntries(railKeys.map(k => [k, anyRailOpen])));
  }, [railKeys, anyRailOpen]);

  /** 只有会话最后一条答案能重新生成：后端回退上下文只回得到末尾那一轮 */
  const lastAnswerIndex = useMemo(() => {
    for (let i = items.length - 1; i >= 0; i--) {
      if (items[i].kind === 'assistant') return i;
      if (items[i].kind === 'user') break;   // 提问之后还没有答案，这一轮没得重生成
    }
    return -1;
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

  const handleSend = useCallback((msg: string) => {
    scrollToBottom();   // 自己刚发的话总要看见
    setStopping(false);
    void chatStore.send(msg);
  }, [scrollToBottom]);

  const handleRegenerate = useCallback(() => {
    scrollToBottom();
    setStopping(false);
    void chatStore.regenerate();
  }, [scrollToBottom]);

  /** 停止：后端跑到下一个检查点才收尾，所以按钮先进"收尾中"；后端说没轮在跑就恢复原状 */
  const handleStop = useCallback(async () => {
    setStopping(true);
    if (!await chatStore.cancelRun()) setStopping(false);
  }, []);

  /** HITL 决策交给 store；本地只记"哪张卡在提交"用来防连点+出转圈。按 requestId 认卡，条目挪位置也不会打偏。 */
  const handleHitl = useCallback(async (requestId: string, approved: boolean) => {
    setHitlBusy({ requestId, approved });
    try {
      await chatStore.hitlDecide(requestId, approved);
    } catch (err) {
      chatStore.pushError((err as Error).message || '确认失败，请重试');
    } finally {
      // 只收自己那张：同时挂两张卡时，先点那张收尾会把后点那张的转圈一起清掉
      setHitlBusy(cur => (cur?.requestId === requestId ? null : cur));
    }
  }, []);

  /** 删除会话：列表移除；删的是当前会话时 store 一并清空。删了不可恢复，先问一句（站内破坏性操作的既有写法） */
  const removeSession = useCallback(async (s: WorkbenchSessionSummary) => {
    if (!window.confirm(`删除会话「${s.title}」？${s.messageCount} 条消息与它的续聊上下文会一并清掉，不可恢复。`)) return;
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
        <Bot className="w-4.5 h-4.5 text-primary shrink-0" />
        <span className="text-sm font-black shrink-0">研判对话</span>
        {/* 副标题吃掉剩余空间并允许截断：面板宽度可拖到 320，而 sm: 判的是视口不是面板，
            不给它 flex-1 + truncate 的话 PC 上窄面板会被这句话把按钮挤出去 */}
        <span className="hidden sm:block flex-1 min-w-0 truncate text-[10px] text-muted-foreground">
          {title ?? `${HUB_NAME} 调度 · 关窗后台继续`}
        </span>
        {/* 按钮组自己带 ml-auto 把自己顶到右边：副标题在 <640px 是 hidden、不占 flex 位，
            指望它撑开的话手机上整排按钮会挤到左端 */}
        <div className="ml-auto flex items-center gap-2 shrink-0">
          {railKeys.length > 0 && (
            <button
              onClick={toggleAllRails}
              className="shrink-0 border border-border hover:bg-surface-hover w-7 h-7 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary"
              title={anyRailOpen ? '收起全部工作过程' : '展开全部工作过程'}
              aria-label="全部展开或收起工作过程"
            >
              {anyRailOpen ? <ChevronsDownUp className="w-3.5 h-3.5" /> : <ChevronsUpDown className="w-3.5 h-3.5" />}
            </button>
          )}
          <div ref={actionMenuRef} className="relative">
            <button
              onClick={() => setActionMenu(v => !v)}
              className={cn(
                'border border-border hover:bg-surface-hover w-7 h-7 rounded-lg flex items-center justify-center hover:text-primary',
                actionMenu ? 'text-primary' : 'text-muted-foreground',
              )}
              title="我的 trader"
              aria-label="trader 动作面板"
            >
              <Cpu className="w-3.5 h-3.5" />
            </button>
            {actionMenu && (
              // z-20 压过历史叠层的 z-10；菜单落在面板框内，不必 portal 到 body
              <div className="absolute right-0 top-full mt-1 z-20 w-32 rounded-lg pt-card shadow-lg py-1 animate-in fade-in slide-in-from-top-2">
                {TRADER_ACTIONS.map(a => (
                  <button
                    key={a.form}
                    onClick={() => { chatStore.openForm(a.form); setActionMenu(false); }}
                    className="w-full flex items-center gap-2 px-3 py-1.5 text-xs font-bold text-muted-foreground hover:bg-surface-hover hover:text-foreground"
                  >
                    <a.icon className="w-3.5 h-3.5 shrink-0" /> {a.label}
                  </button>
                ))}
              </div>
            )}
        </div>
        <button
          onClick={() => showHistory ? setShowHistory(false) : openHistory()}
          className={cn(
            'shrink-0 border border-border hover:bg-surface-hover w-7 h-7 rounded-lg flex items-center justify-center hover:text-primary',
            showHistory ? 'text-primary' : 'text-muted-foreground',
          )}
          title="历史对话"
        >
          <History className="w-3.5 h-3.5" />
        </button>
        <button
          onClick={handleNewSession}
          className="shrink-0 border border-border hover:bg-surface-hover w-7 h-7 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary"
          title="新会话"
        >
          <RotateCcw className="w-3.5 h-3.5" />
        </button>
        {/* 全屏只在 PC 出：移动端面板本来就铺满视口 */}
        {onToggleFullscreen && (
          <button
            onClick={onToggleFullscreen}
            className="shrink-0 hidden md:flex border border-border hover:bg-surface-hover w-7 h-7 rounded-lg items-center justify-center text-muted-foreground hover:text-primary"
            title={fullscreen ? '退出全屏（Esc）' : '全屏'}
            aria-label={fullscreen ? '退出全屏' : '全屏显示对话面板'}
          >
            {fullscreen ? <Minimize2 className="w-3.5 h-3.5" /> : <Maximize2 className="w-3.5 h-3.5" />}
          </button>
        )}
        {onClose && (
          <button
            onClick={onClose}
            className="shrink-0 border border-border hover:bg-surface-hover w-7 h-7 rounded-lg flex items-center justify-center text-muted-foreground hover:text-foreground"
            title="关闭"
            aria-label="关闭对话面板"
          >
            <X className="w-3.5 h-3.5" />
          </button>
        )}
        </div>
      </div>

      {/* 内容区：消息流 + 输入区；历史列表是盖在上面的右滑叠层 */}
      <div className="relative flex-1 min-h-0 flex flex-col">
        {/* 消息流自带一层定位上下文：回到底部要贴消息流的下沿，
            挂在外层的话 bottom 量的是输入区底边，浮标会压在"Enter 发送"那行上 */}
        <div className="relative flex-1 min-h-0 flex flex-col">
          <div ref={scrollRef} onScroll={onScroll} className="flex-1 overflow-y-auto px-4 py-3 space-y-3">
            {items.length === 0 && (
              <div className="h-full flex flex-col items-center justify-center gap-3 text-center px-6">
                <div className="w-12 h-12 rounded-full border border-border bg-background flex items-center justify-center text-muted-foreground/60">
                  <Bot className="w-6 h-6" />
                </div>
                <p className="text-sm text-muted-foreground">问点什么——下面的快捷提问可以直接点</p>
                <p className="text-[10px] text-muted-foreground/70">行情 · 新闻 · 你的交易员 · 深度研判，都归 {HUB_NAME} 调度</p>
              </div>
            )}
            {blocks.map(block => {
              if (block.kind === 'rail') {
                const active = block.steps.some(({ item }) =>
                  (item.kind === 'expert' && item.streaming) || (item.kind === 'progress' && item.active));
                return (
                  <ProcessRail
                    key={block.key}
                    steps={block.steps}
                    active={active}
                    open={!railClosed[block.key]}
                    onToggle={() => setRailClosed(prev => ({ ...prev, [block.key]: !prev[block.key] }))}
                  />
                );
              }
              const { item, index } = block;
              switch (item.kind) {
                case 'user':
                  return (
                    <UserBubble
                      key={index}
                      item={item}
                      onCancelQueued={item.queued && item.queuedId != null
                        ? () => chatStore.cancelQueued(item.queuedId as number) : undefined}
                    />
                  );
                case 'assistant':
                  return (
                    <AssistantAnswer
                      key={index}
                      item={item}
                      // background=让位后欠着补答、正靠轮询等它落库，这时候重生成会把轮询掐掉
                      canRegenerate={index === lastAnswerIndex && !loading && !background}
                      onRegenerate={handleRegenerate}
                    />
                  );
                case 'hitl':
                  return (
                    <HitlCard
                      key={item.requestId}
                      item={item}
                      submitting={hitlBusy?.requestId === item.requestId
                        ? (hitlBusy.approved ? 'approve' : 'reject') : null}
                      onDecide={a => void handleHitl(item.requestId, a)}
                    />
                  );
                case 'form':
                  // key 用卡自身的 id：草稿在卡的组件 state 里，按下标做 key 时列表中间插条目
                  // 会让 React 拿错元素配对、把正在敲的留言卸载掉
                  return (
                    <TraderFormCard
                      key={item.id}
                      form={item.form}
                      prefill={item.prefill}
                      status={item.status}
                      result={item.result}
                      onSettle={r => chatStore.settleForm(item.id, r)}
                      onCancel={() => chatStore.closeForm(item.id)}
                    />
                  );
                case 'error':
                  return (
                    <p key={index} className="text-[11px] text-destructive/80 text-center py-1">{item.message}</p>
                  );
              }
            })}
            {loading && (
              <div className="flex items-center gap-2 text-xs text-muted-foreground">
                {!streamingNow && (
                  <>
                    <Loader2 className="w-3.5 h-3.5 animate-spin" />
                    {background ? `${HUB_NAME} 在后台继续研判，完成后自动展示答案` : `${HUB_NAME} 分析问题中...`}
                  </>
                )}
                {/* 后台轮询态没有可中断的本地轮：补答跑在后台，没有面板可点停止 */}
                {!background && (
                  <button
                    onClick={() => void handleStop()}
                    disabled={stopping}
                    className="inline-flex items-center gap-1 border border-border rounded-full px-2.5 py-0.5 text-[11px] font-bold hover:text-loss hover:border-loss/40 disabled:opacity-50 transition-colors"
                    title="停止这一轮"
                  >
                    <Square className="w-2.5 h-2.5 fill-current" />
                    {stopping ? '收尾中' : '停止'}
                  </button>
                )}
              </div>
            )}
          </div>

          {/* 回到底部：只在用户往回翻之后出现，贴在消息流下沿 */}
          {!stuckToBottom && (
            <button
              onClick={scrollToBottom}
              className="absolute left-1/2 -translate-x-1/2 bottom-2 z-[5] flex items-center gap-1 rounded-full pt-card shadow-lg px-2.5 py-1 text-[10px] font-bold text-muted-foreground hover:text-primary animate-in fade-in"
            >
              <ArrowDown className="w-3 h-3" /> 回到底部
            </button>
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

        <ChatComposer loading={loading} onSend={handleSend} />

        <SessionHistory
          open={showHistory}
          loading={historyLoading}
          sessions={sessions}
          currentId={sessionId}
          onBack={() => setShowHistory(false)}
          onOpen={s => void openSession(s)}
          onRemove={s => void removeSession(s)}
          onNew={handleNewSession}
        />
      </div>
    </div>
  );
}
