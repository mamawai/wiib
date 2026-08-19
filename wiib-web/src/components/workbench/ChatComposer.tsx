import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { BookOpenCheck, CornerDownLeft, Cpu, MessageSquarePlus, Pencil, Plus, Trash2, Zap } from 'lucide-react';
import { useClickOutside } from '../../hooks/useClickOutside';
import { cn } from '../../lib/utils';
import { chatStore } from './chatStore';
import { HUB_NAME } from './chatView';
import type { TraderFormKind } from '../../types';

/** 出厂的四条快捷提问：行情 / 新闻 / 交易员 / 深研判 四种能力各一条。用户改过之后存 localStorage，这里只当"恢复默认"的底本 */
const DEFAULT_SUGGESTS = ['BTC 现在的市场结构怎么样？', '最近有什么值得注意的加密新闻？', '我的 AI 交易员最近表现如何？', '对 ETH 做一次深度研判'];
const SUGGEST_KEY = 'wiib-chat-suggests';
/** 最多四条：这一行还要跟 trader 按钮分地方，再多就只能靠滚才看得全，不叫快捷了 */
const SUGGEST_MAX = 4;

/** trader 动作入口的三项：点了只是把表单卡放进对话，执行要在卡上再按一次 */
const TRADER_ACTIONS: { form: TraderFormKind; label: string; icon: typeof Zap }[] = [
  { form: 'note', label: '给它留言', icon: MessageSquarePlus },
  { form: 'wake', label: '手动唤醒', icon: Zap },
  { form: 'review', label: '立即复盘', icon: BookOpenCheck },
];

/** 拖动阈值与吞 click 的时限，跟悬浮球（ChatDock）同一把尺子：8px 照触屏的 tap slop 定的 */
const DRAG_THRESHOLD = 8;
const CLICK_SWALLOW_MS = 300;
/** 横滚两端的渐隐宽度，配 index.css 的 .hscroll-fade */
const FADE_W = '1.25rem';

function loadSuggests(): string[] {
  try {
    const v: unknown = JSON.parse(localStorage.getItem(SUGGEST_KEY) || '');
    // 四条删光了存的就是 []，那就真一条都不显示——把"空"当成"没配过"的话，用户永远删不掉
    if (Array.isArray(v)) return v.filter((s): s is string => typeof s === 'string' && !!s.trim()).slice(0, SUGGEST_MAX);
  } catch { /* 没存过 / 存坏了都退回出厂四条 */ }
  return DEFAULT_SUGGESTS;
}

/**
 * 输入区：trader 入口 + 快捷提问 + 多行自适应输入 + 状态行。
 * <p>
 * 敲字只动这一小块的本地 state，不带着上面整条消息列表重画；
 * 同时同步一份到 store —— 关面板会把整个面板卸载，只留本地 state 的话草稿就没了。
 * <p>
 * 快捷提问可改可删（最多四条，存 localStorage）；trader 那三个动作从按钮右侧滑出、
 * 顶掉提示词的位置——两边都是"点一下就走"的入口，轮流用同一段横向空间比各占一行省。
 */
export function ChatComposer({ loading, onSend, fullscreen }: {
  loading: boolean;
  onSend: (text: string) => void;
  /** 全屏时输入区跟正文共用一条限宽线，不然输入框会横跨整个屏幕 */
  fullscreen?: boolean;
}) {
  const [input, setInput] = useState(chatStore.getDraft);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const [suggests, setSuggests] = useState<string[]>(loadSuggests);
  const [editing, setEditing] = useState(false);
  const [traderOpen, setTraderOpen] = useState(false);
  // 编辑面板浮在这一行上方，点这一行以外的地方就收起来。
  // ref 要连铅笔按钮一起圈住，否则点铅笔会同时触发 onOutside 与 onClick，面板一闪就没
  const quickRef = useRef<HTMLDivElement>(null);
  useClickOutside(quickRef, () => setEditing(false), editing);

  /** 内容撑到哪就多高，最多 96px（rows=1 是起始高度，靠这个函数往上长） */
  const fitHeight = (el: HTMLTextAreaElement) => {
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 96)}px`;
  };

  // 挂载时把恢复回来的草稿撑开：只灌 value 不调高度的话，多行草稿回来只剩一行
  useLayoutEffect(() => {
    if (inputRef.current && inputRef.current.value) fitHeight(inputRef.current);
  }, []);

  const edit = useCallback((text: string) => {
    setInput(text);
    chatStore.setDraft(text);
  }, []);

  const submit = useCallback((text?: string) => {
    const msg = (text ?? input).trim();
    if (!msg) return;
    edit('');
    if (inputRef.current) inputRef.current.style.height = 'auto';
    onSend(msg);
  }, [input, edit, onSend]);

  const saveSuggests = useCallback((next: string[]) => {
    setSuggests(next);
    localStorage.setItem(SUGGEST_KEY, JSON.stringify(next));
    setEditing(false);
  }, []);

  return (
    // 底部 padding 避让 home indicator：移动端面板是 inset-0 铺满视口的（ChatDock 只配了
    // pt 那侧的安全区），10px 顶不住 34px 手势条。用 max() 而不是相加——手势条那块本身就是留白。
    // md 起固定回 10px：env() 是视口级的，iPad 上不贴底的浮窗也会拿到 34px，那是多余的
    <div className="border-t border-border px-3 pt-2 pb-[max(0.625rem,env(safe-area-inset-bottom))] md:pb-2.5 shrink-0">
      <div className={cn('space-y-2', fullscreen && 'mx-auto w-full max-w-3xl')}>
        <div ref={quickRef} className="relative flex items-center gap-1.5">
          {editing && (
            <SuggestEditor initial={suggests} onSave={saveSuggests} onCancel={() => setEditing(false)} />
          )}
          <button
            onClick={() => { setTraderOpen(v => !v); setEditing(false); }}
            className={cn(
              'shrink-0 inline-flex items-center gap-1 rounded-full border px-2.5 py-1 text-[11px] font-bold transition-colors',
              traderOpen
                ? 'border-primary/45 bg-primary/6 text-primary'
                : 'border-border text-muted-foreground hover:text-foreground hover:bg-surface-hover',
            )}
            title="我的 trader"
            aria-expanded={traderOpen}
          >
            <Cpu className="w-3 h-3 shrink-0" /> trader
          </button>

          <ScrollRow contentKey={traderOpen ? 'trader' : suggests.join('|')}>
            {traderOpen ? (
              TRADER_ACTIONS.map((a, i) => (
                <button
                  key={a.form}
                  onClick={() => { chatStore.openForm(a.form); setTraderOpen(false); }}
                  // 三项从左往右依次登场，让人看清是"从 trader 里长出来的"，不是凭空换了一排
                  style={{ animationDelay: `${i * 50}ms` }}
                  className="shrink-0 inline-flex items-center gap-1 rounded-full border border-primary/45 px-2.5 py-1 text-[11px] font-bold text-primary hover:bg-primary/8 transition-colors animate-in slide-in-from-left-2"
                >
                  <a.icon className="w-3 h-3 shrink-0" /> {a.label}
                </button>
              ))
            ) : suggests.length > 0 ? (
              suggests.map(q => (
                <button
                  key={q}
                  onClick={() => submit(q)}
                  className="shrink-0 border border-border rounded-full px-2.5 py-1 text-[11px] font-medium text-muted-foreground hover:text-foreground hover:bg-surface-hover transition-colors"
                >
                  {q}
                </button>
              ))
            ) : (
              <span className="text-[11px] text-muted-foreground/70">快捷提问删光了，点右边铅笔加回来</span>
            )}
          </ScrollRow>

          {/* trader 展开时把铅笔收起来：那会儿这一行里没有提示词可改，留着只会误点 */}
          {!traderOpen && (
            <button
              onClick={() => setEditing(v => !v)}
              className={cn(
                'shrink-0 w-6 h-6 rounded-md flex items-center justify-center border transition-colors',
                editing ? 'border-border text-primary' : 'border-transparent text-muted-foreground hover:text-primary hover:border-border',
              )}
              title="编辑快捷提问"
              aria-label="编辑快捷提问"
            >
              <Pencil className="w-3.5 h-3.5" />
            </button>
          )}
        </div>

        <div className="flex items-end gap-2 rounded-xl border border-border bg-card-2 px-3 py-2 transition-colors focus-within:border-primary/50">
          <textarea
            ref={inputRef}
            rows={1}
            value={input}
            onChange={e => edit(e.target.value)}
            onInput={e => fitHeight(e.currentTarget)}
            onKeyDown={e => {
              if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
                e.preventDefault();
                submit();
              }
            }}
            placeholder={loading ? `${HUB_NAME} 工作中——可继续发消息插话` : '问行情、查新闻、看你的交易员…'}
            className="flex-1 bg-transparent text-sm leading-relaxed resize-none max-h-24 focus:outline-none placeholder:text-muted-foreground/60"
          />
          {/* 高度按输入框一行文字定（text-sm × leading-relaxed ≈ 1.4rem），跟末行齐平而不是杵个大方块。
              视觉压小了但热区没缩：after 那层往外撑到 44px 那一档，手机上照按不误 */}
          <button
            onClick={() => submit()}
            disabled={!input.trim()}
            className="relative w-7 h-[1.4rem] rounded-md bg-primary text-primary-foreground flex items-center justify-center shrink-0 hover:brightness-105 active:scale-95 transition-all disabled:opacity-40 disabled:active:scale-100 after:content-[''] after:absolute after:-inset-2.5"
            title="发送（Enter）"
            aria-label="发送"
          >
            <CornerDownLeft className="w-3.5 h-3.5" />
          </button>
        </div>

        <div className="flex justify-between px-0.5 text-[10px] text-muted-foreground/60">
          <span>Enter 发送 · Shift+Enter 换行</span>
          <span className="num">{loading ? `${HUB_NAME} 工作中 · 可直接插话` : `${HUB_NAME} 就绪`}</span>
        </div>
      </div>
    </div>
  );
}

/**
 * 横滚行（快捷提问 / trader 三项）：滚动条藏起来，靠三条路让人知道能滚——
 * 纵向滚轮直接推、鼠标按住拖、两端按滚动位置渐隐。触屏一概不接管，交给原生惯性滑动。
 * <p>
 * 全程只改 scrollLeft 和两个 CSS 变量，一次 state 都不起：拖动中不重画，也就不会拖出卡顿。
 */
function ScrollRow({ contentKey, children }: {
  /** 内容的身份。它一变就说明这一行宽度变了，渐隐得重算 */
  contentKey: string;
  children: React.ReactNode;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const dragRef = useRef<{ id: number; x: number; left: number; moved: boolean } | null>(null);
  const swallowRef = useRef(0);

  /** 渐隐跟着滚动位置走：滚到头的那侧收成 0，停在中间时两侧都露一截虚化 */
  const sync = useCallback(() => {
    const el = ref.current;
    if (!el) return;
    const max = el.scrollWidth - el.clientWidth;
    el.style.setProperty('--fade-l', el.scrollLeft > 1 ? FADE_W : '0px');
    el.style.setProperty('--fade-r', el.scrollLeft < max - 1 ? FADE_W : '0px');
  }, []);

  // 内容换了（改过提示词、trader 开合）要重算；面板拖拽 resize、进出全屏这类宽度变化交给 ResizeObserver
  useEffect(() => { sync(); }, [contentKey, sync]);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const ro = new ResizeObserver(sync);
    ro.observe(el);
    return () => ro.disconnect();
  }, [sync]);

  // PC 上没有横向滚轮，纵向的直接拿来推——不接这条等于"能滚但滚不动"。
  // 必须手接原生监听：React 的 onWheel 挂的是 passive，里面 preventDefault 不生效
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const onWheel = (e: WheelEvent) => {
      if (!e.deltaY || el.scrollWidth <= el.clientWidth) return;
      e.preventDefault();
      el.scrollLeft += e.deltaY;
    };
    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  }, []);

  const onPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    const el = ref.current;
    // 只接鼠标：触屏本来就能划，抢过来反而把惯性弄没了
    if (!el || e.pointerType !== 'mouse' || el.scrollWidth <= el.clientWidth) return;
    dragRef.current = { id: e.pointerId, x: e.clientX, left: el.scrollLeft, moved: false };
  };
  const onPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    const d = dragRef.current, el = ref.current;
    if (!d || !el || d.id !== e.pointerId) return;
    const dx = e.clientX - d.x;
    if (!d.moved && Math.abs(dx) > DRAG_THRESHOLD) {
      d.moved = true;
      el.setPointerCapture(e.pointerId);
    }
    if (d.moved) el.scrollLeft = d.left - dx;
  };
  const onPointerEnd = (e: React.PointerEvent<HTMLDivElement>) => {
    const d = dragRef.current;
    if (!d || d.id !== e.pointerId) return;
    if (d.moved) swallowRef.current = performance.now();   // 松手后浏览器还会补一个 click
    dragRef.current = null;
  };

  return (
    <div
      ref={ref}
      onScroll={sync}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerEnd}
      onPointerCancel={onPointerEnd}
      // 捕获阶段吞掉拖完补出来的那个 click，否则松手那一下会顺手把某条提问发出去
      onClickCapture={e => {
        if (performance.now() - swallowRef.current >= CLICK_SWALLOW_MS) return;
        swallowRef.current = 0;
        e.stopPropagation();
        e.preventDefault();
      }}
      className="hscroll-fade flex-1 min-w-0 flex items-center gap-1.5 overflow-x-auto overscroll-x-contain [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
    >
      {children}
    </div>
  );
}

/** 快捷提问编辑面板：浮在那一行上方。改 / 删 / 加，最多四条，按保存才落 localStorage */
function SuggestEditor({ initial, onSave, onCancel }: {
  initial: string[];
  onSave: (next: string[]) => void;
  onCancel: () => void;
}) {
  // id 跟着行走：删中间一行时按下标做 key 会让 React 拿错输入框，把正在敲的字挪到别行去
  const [rows, setRows] = useState(
    () => (initial.length ? initial : ['']).map((text, i) => ({ id: i, text })),
  );
  // 后来新增的行从初始条数往后接着发号，撞不上已有的 id
  const seq = useRef(rows.length);

  return (
    <div className="absolute left-0 right-0 bottom-full mb-2 z-20 rounded-xl pt-card shadow-lg p-3 animate-in fade-in slide-in-from-bottom-2">
      <div className="flex items-center gap-2 mb-2">
        <Pencil className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
        <span className="text-xs font-black shrink-0">自定义快捷提问</span>
        <span className="text-[10px] text-muted-foreground truncate">最多 {SUGGEST_MAX} 条 · 留空或按垃圾桶都算删</span>
      </div>

      <div className="space-y-1.5">
        {rows.map((r, i) => (
          <div key={r.id} className="flex items-center gap-1.5">
            <span className="num w-4 shrink-0 text-center text-[10px] text-muted-foreground">{i + 1}</span>
            <input
              value={r.text}
              onChange={e => setRows(prev => prev.map(x => (x.id === r.id ? { ...x, text: e.target.value } : x)))}
              placeholder="输入一条快捷提问"
              className="flex-1 min-w-0 rounded-lg border border-border bg-card-2 px-2 py-1.5 text-xs focus:outline-none focus:border-primary/50"
            />
            <button
              onClick={() => setRows(prev => prev.filter(x => x.id !== r.id))}
              className="shrink-0 w-6 h-6 rounded-md flex items-center justify-center text-muted-foreground/60 hover:text-loss hover:bg-loss/10 transition-colors"
              title="删掉这条"
              aria-label="删掉这条快捷提问"
            >
              <Trash2 className="w-3.5 h-3.5" />
            </button>
          </div>
        ))}
      </div>

      {rows.length < SUGGEST_MAX && (
        <button
          onClick={() => setRows(prev => [...prev, { id: seq.current++, text: '' }])}
          className="mt-1.5 w-full border border-dashed border-border rounded-lg py-1.5 text-[11px] font-bold text-primary hover:bg-primary/6 flex items-center justify-center gap-1 transition-colors"
        >
          <Plus className="w-3 h-3" /> 添加一条
        </button>
      )}

      <div className="flex items-center gap-2 mt-2.5">
        <button
          onClick={() => setRows(DEFAULT_SUGGESTS.map(text => ({ id: seq.current++, text })))}
          className="text-[11px] font-bold text-muted-foreground hover:text-foreground hover:underline"
        >
          恢复默认
        </button>
        <div className="ml-auto flex items-center gap-2">
          <button
            onClick={onCancel}
            className="border border-border rounded-lg px-3 h-7 text-[11px] font-bold text-muted-foreground hover:bg-surface-hover transition-colors"
          >
            取消
          </button>
          <button
            onClick={() => onSave(rows.map(r => r.text.trim()).filter(Boolean).slice(0, SUGGEST_MAX))}
            className="rounded-lg px-3 h-7 text-[11px] font-bold bg-primary text-primary-foreground hover:brightness-105 transition-all"
          >
            保存
          </button>
        </div>
      </div>
    </div>
  );
}
