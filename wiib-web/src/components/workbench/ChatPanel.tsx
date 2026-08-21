import { useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { useTranslation } from 'react-i18next';
import { ArrowDown, Bot, ChevronsDownUp, ChevronsUpDown, History, KeyRound, Loader2, Maximize2, Minimize2, PanelLeftClose, PanelLeftOpen, RotateCcw, Square, X } from 'lucide-react';
import { workbenchApi } from '../../api';
import { cn } from '../../lib/utils';
import { chatStore } from './chatStore';
import { AssistantAnswer, HitlCard, ProcessRail, UserBubble } from './ChatMessages';
import { groupBlocks, HUB_NAME } from './chatView';
import { ChatComposer } from './ChatComposer';
import { SessionHistory } from './SessionHistory';
import { TraderFormCard } from './TraderFormCards';
import { BehaviorReportCard } from './BehaviorReportCard';
import type { ChatIntent, WorkbenchSessionSummary } from '../../types';

/** 会话标题截断长度：与后端 ChatHistoryService.TITLE_MAX 同口径，历史列表与面板头对得上 */
const TITLE_MAX = 40;

/** 贴底判定的容差：小于它就算"用户在看最新内容"，新内容照常跟随滚动 */
const STICK_PX = 80;

interface ChatPanelProps {
  /** 停靠壳（ChatDock）传入：点头部 X 关面板 */
  onClose?: () => void;
  /** 后端报配置缺失/不可用时，引导条按钮跳模型配置页 */
  onGoConfig?: () => void;
  /** PC 全屏开关（铺满浏览器视口，不吃地址栏）。不传就不出这个按钮——移动端面板本来就是铺满视口的全屏层 */
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
  const { t } = useTranslation(['ai', 'common']);
  const { items, loading, background, sessionId, needsConfig } = useSyncExternalStore(chatStore.subscribe, chatStore.getSnapshot);
  // 在途的那张确认卡（按 requestId 认）。面板里可能同时挂着几张，用一个布尔会把别的卡一起禁掉
  const [hitlBusy, setHitlBusy] = useState<{ requestId: string; approved: boolean } | null>(null);
  const [showHistory, setShowHistory] = useState(false);
  // 全屏左栏的开合。跟 showHistory 各记各的：退出全屏时叠层不该跟着弹出来，反过来也一样
  const [sideOpen, setSideOpen] = useState(true);
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

  const loadSessions = useCallback(() => {
    setHistoryLoading(true);
    workbenchApi.sessions()
      .then(setSessions)
      .catch(() => setSessions([]))
      .finally(() => setHistoryLoading(false));
  }, []);

  const openHistory = useCallback(() => {
    setShowHistory(true);
    loadSessions();
  }, [loadSessions]);

  // 全屏的左栏是常驻的，进全屏就得先把列表备好；叠层那条路"点开才拉"的时机搬过来的话，
  // 左栏会一直空着，直到用户想起来去点那个开关
  useEffect(() => { if (fullscreen) loadSessions(); }, [fullscreen, loadSessions]);

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

  const handleSend = useCallback((msg: string, intent?: ChatIntent) => {
    scrollToBottom();   // 自己刚发的话总要看见
    setStopping(false);
    void chatStore.send(msg, { intent });
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
      chatStore.pushError((err as Error).message || t('chat.hitlFailed'));
    } finally {
      // 只收自己那张：同时挂两张卡时，先点那张收尾会把后点那张的转圈一起清掉
      setHitlBusy(cur => (cur?.requestId === requestId ? null : cur));
    }
  }, [t]);

  /** 删除会话：列表移除；删的是当前会话时 store 一并清空。删了不可恢复，先问一句（站内破坏性操作的既有写法） */
  const removeSession = useCallback(async (s: WorkbenchSessionSummary) => {
    if (!window.confirm(t('history.deleteConfirm', { title: s.title, count: s.messageCount }))) return;
    try {
      await workbenchApi.deleteSession(s.sessionId);
      setSessions(prev => prev.filter(x => x.sessionId !== s.sessionId));
      chatStore.clearIfCurrent(s.sessionId);
    } catch { /* 删除失败保持原样 */ }
  }, [t]);

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
        <span className="text-sm font-black shrink-0">{t('chat.title')}</span>
        {/* 副标题吃掉剩余空间并允许截断：面板宽度可拖到 320，而 sm: 判的是视口不是面板，
            不给它 flex-1 + truncate 的话 PC 上窄面板会被这句话把按钮挤出去 */}
        <span className="hidden sm:block flex-1 min-w-0 truncate text-[10px] text-muted-foreground">
          {title ?? t('chat.subtitle', { hub: HUB_NAME })}
        </span>
        {/* 按钮组自己带 ml-auto 把自己顶到右边：副标题在 <640px 是 hidden、不占 flex 位，
            指望它撑开的话手机上整排按钮会挤到左端 */}
        <div className="ml-auto flex items-center gap-2 shrink-0">
          {railKeys.length > 0 && (
            <button
              onClick={toggleAllRails}
              className="shrink-0 border border-border hover:bg-surface-hover w-7 h-7 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary"
              title={anyRailOpen ? t('chat.collapseAllRails') : t('chat.expandAllRails')}
              aria-label={t('chat.toggleAllRails')}
            >
              {anyRailOpen ? <ChevronsDownUp className="w-3.5 h-3.5" /> : <ChevronsUpDown className="w-3.5 h-3.5" />}
            </button>
          )}
          {/* 同一个键在两种形态下管两件事：全屏时开合左栏，浮窗/手机时开合右滑叠层。
              对用户都是"看历史对话"，位置不变最省事 */}
          <button
            onClick={() => fullscreen ? setSideOpen(v => !v) : (showHistory ? setShowHistory(false) : openHistory())}
            className={cn(
              'shrink-0 border border-border hover:bg-surface-hover w-7 h-7 rounded-lg flex items-center justify-center hover:text-primary',
              (fullscreen ? sideOpen : showHistory) ? 'text-primary' : 'text-muted-foreground',
            )}
            title={fullscreen ? (sideOpen ? t('chat.hideHistory') : t('chat.showHistory')) : t('history.title')}
          >
            {fullscreen
              ? (sideOpen ? <PanelLeftClose className="w-3.5 h-3.5" /> : <PanelLeftOpen className="w-3.5 h-3.5" />)
              : <History className="w-3.5 h-3.5" />}
          </button>
          <button
            onClick={handleNewSession}
            className="shrink-0 border border-border hover:bg-surface-hover w-7 h-7 rounded-lg flex items-center justify-center text-muted-foreground hover:text-primary"
            title={t('chat.newSession')}
          >
            <RotateCcw className="w-3.5 h-3.5" />
          </button>
          {/* 全屏只在 PC 出：移动端面板本来就铺满视口 */}
          {onToggleFullscreen && (
            <button
              onClick={onToggleFullscreen}
              className="shrink-0 hidden md:flex border border-border hover:bg-surface-hover w-7 h-7 rounded-lg items-center justify-center text-muted-foreground hover:text-primary"
              title={fullscreen ? t('chat.exitFullscreen') : t('chat.fullscreen')}
              aria-label={fullscreen ? t('chat.exitFullscreenAria') : t('chat.fullscreenAria')}
            >
              {fullscreen ? <Minimize2 className="w-3.5 h-3.5" /> : <Maximize2 className="w-3.5 h-3.5" />}
            </button>
          )}
          {onClose && (
            <button
              onClick={onClose}
              className="shrink-0 border border-border hover:bg-surface-hover w-7 h-7 rounded-lg flex items-center justify-center text-muted-foreground hover:text-foreground"
              title={t('common:close')}
              aria-label={t('chat.closeAria')}
            >
              <X className="w-3.5 h-3.5" />
            </button>
          )}
        </div>
      </div>

      {/* 内容区：全屏时左边是常驻历史栏 + 右边对话；浮窗/手机没有左栏，历史是盖在对话上的右滑叠层 */}
      <div className="relative flex-1 min-h-0 flex">
        {/* 左栏开合做成一层宽度过渡；里面那份 SessionHistory 自己钉死 w-64（原因见它那边的注释） */}
        {fullscreen && (
          <div className={cn(
            'shrink-0 overflow-hidden transition-[width] duration-300 ease-[cubic-bezier(.16,1,.3,1)]',
            sideOpen ? 'w-64' : 'w-0',
          )}>
            <SessionHistory
              sidebar
              open
              loading={historyLoading}
              sessions={sessions}
              currentId={sessionId}
              onBack={() => setSideOpen(false)}
              onOpen={s => void openSession(s)}
              onRemove={s => void removeSession(s)}
              onNew={handleNewSession}
            />
          </div>
        )}

        {/* 右边：对话本体（消息流 + 输入区）。全屏时它跟左栏并排，其余形态下它就是整个内容区 */}
        <div className="relative flex-1 min-w-0 flex flex-col">
          {/* 消息流自带一层定位上下文：回到底部要贴消息流的下沿，
              挂在外层的话 bottom 量的是输入区底边，浮标会压在"Enter 发送"那行上 */}
          <div className="relative flex-1 min-h-0 flex flex-col">
            {/* 全屏后每个条目限宽居中：铺满整屏的正文一行能拉到一千多像素，读长回答很累。
                限在子元素上而不是套一层容器——空态那块靠 h-full 撑满，中间多一层它就撑不起来了 */}
            <div ref={scrollRef} onScroll={onScroll} className={cn(
              'flex-1 overflow-y-auto px-4 py-3 space-y-3',
              fullscreen && '[&>*]:mx-auto [&>*]:w-full [&>*]:max-w-3xl',
            )}>
              {items.length === 0 && (
                <div className="h-full flex flex-col items-center justify-center gap-3 text-center px-6">
                  <div className="w-12 h-12 rounded-full border border-border bg-background flex items-center justify-center text-muted-foreground/60">
                    <Bot className="w-6 h-6" />
                  </div>
                  <p className="text-sm text-muted-foreground">{t('chat.emptyTitle')}</p>
                  <p className="text-[10px] text-muted-foreground/70">{t('chat.emptyHint', { hub: HUB_NAME })}</p>
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
                  case 'behavior':
                    return <BehaviorReportCard key={index} report={item.report} />;
                  case 'error':
                    // keyed=前端自己的兜底文案（存的是 key），后端/异常带回来的 message 原样显示
                    return (
                      <p key={index} className="text-[11px] text-destructive/80 text-center py-1">
                        {item.keyed ? t(item.message) : item.message}
                      </p>
                    );
                }
              })}
              {loading && (
                <div className="flex items-center gap-2 text-xs text-muted-foreground">
                  {!streamingNow && (
                    <>
                      <Loader2 className="w-3.5 h-3.5 animate-spin" />
                      {background ? t('chat.background', { hub: HUB_NAME }) : t('chat.thinking', { hub: HUB_NAME })}
                    </>
                  )}
                  {/* 后台轮询态没有可中断的本地轮：补答跑在后台，没有面板可点停止 */}
                  {!background && (
                    <button
                      onClick={() => void handleStop()}
                      disabled={stopping}
                      className="inline-flex items-center gap-1 border border-border rounded-full px-2.5 py-0.5 text-[11px] font-bold hover:text-loss hover:border-loss/40 disabled:opacity-50 transition-colors"
                      title={t('chat.stopTitle')}
                    >
                      <Square className="w-2.5 h-2.5 fill-current" />
                      {stopping ? t('chat.stopping') : t('chat.stop')}
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
                <ArrowDown className="w-3 h-3" /> {t('chat.toBottom')}
              </button>
            )}
          </div>

          {/* 配置引导条：后端报 2201/2202 时出现——光一行红字用户不知道去哪儿改（配置在 AI 页模型配置 Tab） */}
          {needsConfig && (
            <div className={cn(
              'mx-3 mb-2 rounded-lg border border-warning/40 bg-warning/10 px-3 py-2 flex items-center gap-2 shrink-0',
              fullscreen && 'w-full max-w-3xl mx-auto',
            )}>
              <KeyRound className="w-3.5 h-3.5 text-warning shrink-0" />
              <span className="text-[11px] font-bold flex-1 text-left">{t('chat.needsConfig')}</span>
              <button
                onClick={() => { chatStore.clearNeedsConfig(); onGoConfig?.(); }}
                className="text-[11px] font-bold text-primary shrink-0 hover:underline"
              >
                {t('chat.goConfig')}
              </button>
              <button
                onClick={() => chatStore.clearNeedsConfig()}
                aria-label={t('chat.dismiss')}
                className="text-muted-foreground/60 hover:text-foreground shrink-0"
              >
                <X className="w-3.5 h-3.5" />
              </button>
            </div>
          )}

          <ChatComposer loading={loading} onSend={handleSend} fullscreen={fullscreen} />

          {/* 浮窗 / 手机：历史还是盖在对话上的右滑叠层。全屏那份在左边常驻，这里不重复挂 */}
          {!fullscreen && (
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
          )}
        </div>
      </div>
    </div>
  );
}
