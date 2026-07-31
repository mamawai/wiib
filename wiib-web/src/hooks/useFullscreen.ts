/**
 * 元素级全屏，带 CSS 降级。
 *
 * 优先走原生 Fullscreen API（能盖掉浏览器地址栏，手机上多出一大截高度）；
 * iPhone Safari 至今不支持非 video 元素的 requestFullscreen，那里自动退成
 * `position:fixed` 铺满视口 —— 视觉上少了隐藏浏览器 UI 那一步，其余体验一致。
 *
 * 两种模式都**不动 DOM 结构**：只给同一个元素加类名/调 API，
 * 所以里面的图表不会被 React 卸载重建，已画的线、滚动位置、行情数据全都留着。
 */
import { useCallback, useEffect, useRef, useState, type RefObject } from 'react';

/** Safari 老前缀；类型上开个洞比到处 as any 干净 */
interface WebkitEl extends HTMLElement { webkitRequestFullscreen?: () => Promise<void> | void; }
interface WebkitDoc extends Document { webkitFullscreenElement?: Element | null; webkitExitFullscreen?: () => Promise<void> | void; }

export function useFullscreen(ref: RefObject<HTMLElement | null>) {
  const [native, setNative] = useState(false);
  const [css, setCss] = useState(false);
  const cssRef = useRef(false);
  useEffect(() => { cssRef.current = css; }, [css]);

  // 用户按 ESC / F11 退出时浏览器不通知调用方，只发这个事件，按钮状态得跟着回位
  useEffect(() => {
    const sync = () => {
      const d = document as WebkitDoc;
      setNative((d.fullscreenElement ?? d.webkitFullscreenElement) === ref.current);
    };
    document.addEventListener('fullscreenchange', sync);
    document.addEventListener('webkitfullscreenchange', sync);
    return () => {
      document.removeEventListener('fullscreenchange', sync);
      document.removeEventListener('webkitfullscreenchange', sync);
    };
  }, [ref]);

  // 降级模式没有浏览器兜底的 ESC，自己接
  useEffect(() => {
    if (!css) return;
    const esc = (e: KeyboardEvent) => { if (e.key === 'Escape') setCss(false); };
    window.addEventListener('keydown', esc);
    return () => window.removeEventListener('keydown', esc);
  }, [css]);

  const toggle = useCallback(() => {
    const el = ref.current as WebkitEl | null;
    if (!el) return;
    const d = document as WebkitDoc;
    if (d.fullscreenElement ?? d.webkitFullscreenElement) { (d.exitFullscreen ?? d.webkitExitFullscreen)?.call(d); return; }
    if (cssRef.current) { setCss(false); return; }
    // requestFullscreen 可能同步抛(不支持)也可能返回 reject 的 promise(被策略拒)，两条都得接住
    try {
      const r = (el.requestFullscreen ?? el.webkitRequestFullscreen)?.call(el);
      if (r && typeof r.then === 'function') r.then(undefined, () => setCss(true));
      else if (!r && !el.requestFullscreen && !el.webkitRequestFullscreen) setCss(true);
    } catch {
      setCss(true);
    }
  }, [ref]);

  return { active: native || css, cssMode: css, toggle };
}
