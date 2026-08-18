import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { useNavigate } from 'react-router-dom';
import { Bot, KeyRound, Loader2, X } from 'lucide-react';
import { llmEndpointApi } from '../../api';
import { useFullscreen } from '../../hooks/useFullscreen';
import { cn } from '../../lib/utils';
import { chatStore } from './chatStore';
import { ChatPanel } from './ChatPanel';

const SIZE_KEY = 'wiib-chatdock-size';
const BALL_KEY = 'wiib-chatdock-ball';
const MIN_W = 320, MAX_W = 760, MIN_H = 420;
type Size = { w: number; h: number };
type Axis = 'x' | 'y' | 'xy';

/** 球停在哪：横向只有贴左/贴右两种，竖向存比例——存像素的话换个窗口高度就跑到视口外了 */
type BallPos = { side: 'left' | 'right'; yRatio: number };

const BALL = 48;
const BALL_EDGE = 16;
/** 竖向留白：移动端下方是底部导航（Layout 的 h-20），PC 没有 */
const BALL_BOTTOM_MOBILE = 80, BALL_BOTTOM_DESKTOP = 24;
/** 位移超过它才算拖动；一旦算过就不回退，手抖着挪回原点也不该当成点击 */
const DRAG_THRESHOLD = 4;

function loadSize(): Size {
  try {
    const s = JSON.parse(localStorage.getItem(SIZE_KEY) || '') as Size;
    if (typeof s.w === 'number' && typeof s.h === 'number') return s;
  } catch { /* 没存过/存坏了都用默认 */ }
  return { w: 400, h: 672 };
}

function loadBall(): BallPos {
  try {
    const b = JSON.parse(localStorage.getItem(BALL_KEY) || '') as BallPos;
    if ((b.side === 'left' || b.side === 'right') && typeof b.yRatio === 'number') {
      return { side: b.side, yRatio: Math.min(Math.max(b.yRatio, 0), 1) };
    }
  } catch { /* 没存过/存坏了都用默认 */ }
  return { side: 'right', yRatio: 1 };   // 右下角，与拖拽上线前的位置一致
}

/** 球的活动范围，随视口变化实时算 */
function ballBounds() {
  const bottom = window.matchMedia('(min-width: 768px)').matches ? BALL_BOTTOM_DESKTOP : BALL_BOTTOM_MOBILE;
  return {
    minX: BALL_EDGE,
    maxX: window.innerWidth - BALL - BALL_EDGE,
    minY: BALL_EDGE,
    maxY: window.innerHeight - bottom - BALL,
  };
}

const clamp = (v: number, lo: number, hi: number) => Math.min(Math.max(v, lo), Math.max(lo, hi));

/**
 * 全站悬浮对话入口（Layout 挂载，登录后可见）：右下角气泡 + PC 锚定浮窗 / 移动端全屏层。
 * chatStore 是页面无关的单例，SSE 不随面板关闭中断——关掉气泡研判照跑；
 * 面板关着时一轮跑完，气泡亮橙点提醒。PC 浮窗可从左/上边缘拖拽调大小（记忆到 localStorage）。
 */
export function ChatDock() {
  const navigate = useNavigate();
  const { loading } = useSyncExternalStore(chatStore.subscribe, chatStore.getSnapshot);
  const [open, setOpen] = useState(false);
  const [unread, setUnread] = useState(false);
  // null=首次还没查回来（面板内转圈）。每次打开都重查：用户去配置页存完回来，不用刷新页面
  const [hasConfig, setHasConfig] = useState<boolean | null>(null);
  // 订阅回调里读不到最新 state，开合状态镜像到 ref（只在事件处理器里写）
  const openRef = useRef(false);

  const [size, setSize] = useState<Size>(loadSize);
  // 拖拽全程的账都记在 ref 里（起点 + 最新值），结束时一次性落 localStorage
  const dragRef = useRef<{ axis: Axis; x: number; y: number; w: number; h: number; cur: Size } | null>(null);

  const [ball, setBall] = useState<BallPos>(loadBall);
  // 拖动中的实时像素位置；null=没在拖，位置由 ball 算出来
  const [ballDrag, setBallDrag] = useState<{ x: number; y: number } | null>(null);
  const ballRef = useRef<{ sx: number; sy: number; ox: number; oy: number; moved: boolean } | null>(null);
  // 拖完松手浏览器还会补一个 click，用它吞掉那一下。开合仍挂在 onClick 上——
  // 键盘 Enter/Space 只触发 click，改成在 pointerup 里开合的话球就没法用键盘操作了
  const swallowClickRef = useRef(false);
  // 球的落点存的是比例，视口一变就得按新范围重算
  const [bounds, setBounds] = useState(ballBounds);
  useEffect(() => {
    const onResize = () => setBounds(ballBounds());
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  // PC 全屏：原生 Fullscreen API，不支持的（iPhone Safari）退成 position:fixed 铺满
  const dockRef = useRef<HTMLDivElement>(null);
  const fs = useFullscreen(dockRef);

  // 关面板时退出全屏。原生那条路浏览器会自己退（宿主元素没了），但 CSS 降级路只是个类名，
  // 不主动退的话全屏态会挂在关着的面板上——而全屏时球是藏起来的，等于再也打不开
  const fsActive = fs.active, fsToggle = fs.toggle;
  useEffect(() => {
    if (!open && fsActive) fsToggle();
  }, [open, fsActive, fsToggle]);

  // 面板关着时一轮研判跑完（loading 真→假）→ 气泡亮橙点
  useEffect(() => {
    let prev = chatStore.getSnapshot().loading;
    return chatStore.subscribe(() => {
      const now = chatStore.getSnapshot().loading;
      if (prev && !now && !openRef.current) setUnread(true);
      prev = now;
    });
  }, []);

  const setOpenBoth = useCallback((v: boolean) => {
    openRef.current = v;
    setOpen(v);
  }, []);

  const toggle = useCallback(() => {
    const next = !openRef.current;
    if (next) {
      setUnread(false);
      llmEndpointApi.list().then(list => setHasConfig(list.length > 0)).catch(() => setHasConfig(false));
    }
    setOpenBoth(next);
  }, [setOpenBoth]);

  const goConfig = useCallback(() => {
    setOpenBoth(false);
    navigate('/ai?tab=config');
  }, [setOpenBoth, navigate]);

  /* ===== 悬浮球拖拽：按住挪走 → 松手吸最近的左右边；位移没过阈值才算点击 ===== */
  const ballDown = (e: React.PointerEvent<HTMLButtonElement>) => {
    e.currentTarget.setPointerCapture(e.pointerId);
    swallowClickRef.current = false;   // 上一次若没等到 click，标记不该留到这一次
    const b = ballBounds();
    ballRef.current = {
      sx: e.clientX, sy: e.clientY, moved: false,
      ox: ball.side === 'left' ? b.minX : b.maxX,
      oy: clamp(b.minY + ball.yRatio * (b.maxY - b.minY), b.minY, b.maxY),
    };
  };
  const ballMove = (e: React.PointerEvent) => {
    const d = ballRef.current;
    if (!d) return;
    const dx = e.clientX - d.sx, dy = e.clientY - d.sy;
    if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) d.moved = true;
    if (!d.moved) return;
    const b = ballBounds();
    setBallDrag({ x: clamp(d.ox + dx, b.minX, b.maxX), y: clamp(d.oy + dy, b.minY, b.maxY) });
  };
  /** @param expectClick pointerup 之后浏览器会补一个 click，pointercancel 不会——别留个吞不掉的标记 */
  const ballEnd = (expectClick: boolean) => {
    const d = ballRef.current;
    if (!d) return;
    ballRef.current = null;
    if (!d.moved) { setBallDrag(null); return; }   // 没拖动，交给随后那个 click 去开合
    if (expectClick) swallowClickRef.current = true;
    const b = ballBounds();
    const cur = ballDrag ?? { x: d.ox, y: d.oy };
    // 横向永远吸边（贴哪边看球心落在哪半屏），竖向随手停——微信悬浮窗那个手感
    const next: BallPos = {
      side: cur.x + BALL / 2 < window.innerWidth / 2 ? 'left' : 'right',
      yRatio: b.maxY > b.minY ? (cur.y - b.minY) / (b.maxY - b.minY) : 1,
    };
    setBall(next);
    setBallDrag(null);
    localStorage.setItem(BALL_KEY, JSON.stringify(next));
  };

  /** 面板锚在球那一侧的底角，所以往"外"拖是放大：锚右往左拖，锚左往右拖 */
  const resizeStart = (axis: Axis) => (e: React.PointerEvent) => {
    e.preventDefault();
    (e.target as HTMLElement).setPointerCapture(e.pointerId);
    dragRef.current = { axis, x: e.clientX, y: e.clientY, w: size.w, h: size.h, cur: size };
  };
  const resizeMove = (e: React.PointerEvent) => {
    const d = dragRef.current;
    if (!d) return;
    // 锚左时把手在右边缘，往右拖才是放大，增量方向跟着镜像
    const widen = ball.side === 'left' ? e.clientX - d.x : d.x - e.clientX;
    const next: Size = {
      w: d.axis === 'y' ? d.w
        : Math.min(Math.max(d.w + widen, MIN_W), Math.min(MAX_W, window.innerWidth - 32)),
      h: d.axis === 'x' ? d.h
        : Math.min(Math.max(d.h + (d.y - e.clientY), MIN_H), window.innerHeight - 96),
    };
    d.cur = next;
    setSize(next);
  };
  const resizeEnd = () => {
    if (!dragRef.current) return;
    localStorage.setItem(SIZE_KEY, JSON.stringify(dragRef.current.cur));
    dragRef.current = null;
  };

  // ballDrag 非空时用拖动中的实时坐标，否则按比例落在 bounds 里
  const ballX = ballDrag ? ballDrag.x : (ball.side === 'left' ? bounds.minX : bounds.maxX);
  const ballY = ballDrag
    ? ballDrag.y
    : clamp(bounds.minY + ball.yRatio * (bounds.maxY - bounds.minY), bounds.minY, bounds.maxY);
  const dragging = ballDrag != null;

  return (
    <>
      {/* 悬浮球：可拖，松手吸最近的左右边（位置记 localStorage）。z-90 夹在 GuidedTour 之上、toast 之下。
          移动端面板全屏时球藏起来，否则会压在输入区上 */}
      <button
        type="button"
        onPointerDown={ballDown}
        onPointerMove={ballMove}
        onPointerUp={() => ballEnd(true)}
        onPointerCancel={() => ballEnd(false)}
        onClick={() => {
          if (swallowClickRef.current) { swallowClickRef.current = false; return; }
          toggle();
        }}
        style={{
          left: ballX,
          top: ballY,
          // 吸边回弹带一点过冲；拖动中必须关掉 left/top 的过渡，否则球跟不上手指。
          // background-color 这项不能漏：它整条盖掉 className 里的过渡，漏了 hover 就成硬切
          transition: dragging
            ? 'transform .16s ease, box-shadow .16s ease, background-color .16s ease'
            : 'left .34s cubic-bezier(.22,1.4,.36,1), top .34s cubic-bezier(.22,1.4,.36,1),'
              + ' transform .16s ease, box-shadow .16s ease, background-color .16s ease',
        }}
        title={open ? '关闭对话' : '研判对话'}
        aria-label={open ? '关闭对话' : '打开研判对话'}
        className={cn(
          'fixed z-[90] w-12 h-12 rounded-full pt-card touch-none',
          'items-center justify-center hover:bg-surface-hover',
          dragging ? 'cursor-grabbing scale-105 shadow-2xl' : 'cursor-grab active:scale-95',
          // 全屏时球没有立足之地：原生全屏下它根本不在全屏元素里，压根渲染不出来
          open ? 'hidden md:flex' : 'flex',
          open && fs.active && 'hidden md:hidden',
        )}
      >
        {/* 研判中：一道扇形绕球扫。-inset-1 让环带落在球外沿，压在图标下面不挡它 */}
        {!open && loading && (
          <span aria-hidden className="absolute -inset-1 rounded-full pointer-events-none wiib-ball-sweep" />
        )}
        {/* 有未读：一圈向外扩散 */}
        {!open && unread && (
          <span aria-hidden className="absolute inset-0 rounded-full border-[1.5px] border-primary pointer-events-none wiib-ball-ping" />
        )}
        {open ? (
          <X className="w-5 h-5 text-muted-foreground" />
        ) : loading ? (
          <Loader2 className="w-5 h-5 text-primary animate-spin" />
        ) : (
          <Bot className="w-5 h-5 text-primary" />
        )}
        {/* 关着时答案到了：亮橙点。脉冲环扩散完的那一瞬靠它留住痕迹。打开即清 */}
        {!open && unread && (
          <span className="absolute top-1 right-1 w-2.5 h-2.5 rounded-full bg-primary" />
        )}
      </button>

      {open && (
        <div
          ref={dockRef}
          style={{ '--dock-w': `${size.w}px`, '--dock-h': `${size.h}px` } as React.CSSProperties}
          className={cn(
            'fixed z-[85] pt-card flex flex-col overflow-hidden',
            fs.active
              // 全屏铺满，浮窗那套尺寸/锚点全让开。cssMode 是没有原生 API 的降级路，得自己钉住视口
              ? cn('inset-0 rounded-none w-full h-full max-w-none max-h-none', fs.cssMode && 'z-[95]')
              : cn(
                  // 移动端全屏（让开刘海），PC 锚定在球那一侧的浮窗（尺寸可拖拽，CSS 变量只在 md 生效）
                  'inset-0 rounded-none pt-[env(safe-area-inset-top)]',
                  // 面板跟着球换边：球在左就从左下角长出来。竖直方向仍锚底——面板高度可变，
                  // 球拖到顶部时它必然要向下铺，锚底最稳，resize 的方向语义也才立得住
                  'md:inset-auto md:bottom-20 md:rounded-xl md:shadow-2xl md:pt-0',
                  ball.side === 'left' ? 'md:left-4 md:origin-bottom-left' : 'md:right-4 md:origin-bottom-right',
                  'md:w-[var(--dock-w)] md:h-[var(--dock-h)]',
                  'md:max-w-[calc(100vw-2rem)] md:max-h-[calc(100vh-6.5rem)]',
                ),
            'animate-in fade-in zoom-in-95',
          )}
        >
          {/* 拖拽把手（仅 PC 浮窗态）：贴外侧的那条边缘调宽、上边缘调高、外侧上角双向。
              锚右时"外侧"是左边，锚左时是右边；全屏没有尺寸可调，整组撤掉 */}
          {!fs.active && (
            <>
              <div onPointerDown={resizeStart('x')} onPointerMove={resizeMove} onPointerUp={resizeEnd} onPointerCancel={resizeEnd}
                   className={cn('hidden md:block absolute top-4 bottom-0 w-1.5 cursor-ew-resize z-20',
                     ball.side === 'left' ? 'right-0' : 'left-0')} />
              <div onPointerDown={resizeStart('y')} onPointerMove={resizeMove} onPointerUp={resizeEnd} onPointerCancel={resizeEnd}
                   className={cn('hidden md:block absolute top-0 h-1.5 cursor-ns-resize z-20',
                     ball.side === 'left' ? 'left-0 right-4' : 'left-4 right-0')} />
              <div onPointerDown={resizeStart('xy')} onPointerMove={resizeMove} onPointerUp={resizeEnd} onPointerCancel={resizeEnd}
                   className={cn('hidden md:flex absolute top-0 w-4 h-4 z-20 items-start group',
                     ball.side === 'left' ? 'right-0 cursor-nesw-resize justify-end' : 'left-0 cursor-nwse-resize justify-start')}
                   title="拖拽调整大小">
                <span className={cn('mt-1 w-2 h-2 border-t-2 border-muted-foreground/40 group-hover:border-primary transition-colors',
                  ball.side === 'left' ? 'mr-1 border-r-2 rounded-tr' : 'ml-1 border-l-2 rounded-tl')} />
              </div>
            </>
          )}

          {hasConfig == null ? (
            <PanelShell onClose={() => setOpenBoth(false)}>
              <div className="flex-1 flex items-center justify-center gap-2 text-xs text-muted-foreground">
                <Loader2 className="w-4 h-4 animate-spin" /> 加载配置...
              </div>
            </PanelShell>
          ) : hasConfig ? (
            <ChatPanel onClose={() => setOpenBoth(false)} onGoConfig={goConfig}
                       fullscreen={fs.active} onToggleFullscreen={fs.toggle} />
          ) : (
            <PanelShell onClose={() => setOpenBoth(false)}>
              <div className="flex-1 flex flex-col items-center justify-center gap-3 text-center px-6">
                <div className="w-12 h-12 rounded-full border border-border bg-background flex items-center justify-center text-muted-foreground/70">
                  <KeyRound className="w-6 h-6" />
                </div>
                <div className="text-sm font-bold text-muted-foreground">用你自己的 API Key 开始对话</div>
                <div className="text-[11px] text-muted-foreground/70 max-w-[16rem]">
                  对话走你配置的模型端点，费用记在你自己的账上，平台不经手
                </div>
                <button
                  type="button"
                  onClick={goConfig}
                  className="mt-1 border border-border rounded-lg px-4 py-2 text-xs font-bold text-primary hover:bg-surface-hover"
                >
                  去配置
                </button>
              </div>
            </PanelShell>
          )}
        </div>
      )}
    </>
  );
}

/** 加载中/引导态的简壳：ChatPanel 自带头部，这两个状态没有，得补一个能关的头 */
function PanelShell({ onClose, children }: { onClose: () => void; children: React.ReactNode }) {
  return (
    <>
      <div className="flex items-center gap-2 px-4 py-3 border-b border-border">
        <Bot className="w-4.5 h-4.5 text-primary" />
        <span className="text-sm font-black">研判对话</span>
        <button
          onClick={onClose}
          className="ml-auto border border-border hover:bg-surface-hover w-7 h-7 rounded-lg flex items-center justify-center text-muted-foreground hover:text-foreground"
          title="关闭"
          aria-label="关闭对话面板"
        >
          <X className="w-3.5 h-3.5" />
        </button>
      </div>
      {children}
    </>
  );
}
