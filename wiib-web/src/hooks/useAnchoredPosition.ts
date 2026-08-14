import { useCallback, useLayoutEffect, useState } from 'react';

export type Placement = 'top' | 'bottom';

export interface AnchoredPos {
  /** 视口坐标，配合 position:fixed 使用 */
  top: number;
  left: number;
  /** 实际落位方向（空间不足时可能与 prefer 相反） */
  placement: Placement;
}

const EDGE = 8;

/**
 * 把浮层贴到锚点元素上，并钳制在视口内。
 *
 * 为什么必须钳制：浮层宽度固定（如 208px），锚点却常是 14px 的小图标。
 * 用 left:50% + translateX(-50%) 的老写法，图标一靠近屏幕边缘气泡必然溢出——手机上尤其明显。
 * 这里按锚点实测位置算，水平方向强行收进 [EDGE, 视口宽-浮层宽-EDGE]。
 *
 * 配合 position:fixed + createPortal 到 body 使用，顺便躲开所有祖先的 overflow 裁剪。
 * 滚动与缩放时重算：scroll 用捕获阶段监听，内层滚动容器也能收到。
 */
export function useAnchoredPosition(
  anchor: HTMLElement | null,
  floating: HTMLElement | null,
  open: boolean,
  prefer: Placement = 'bottom',
  gap = 8,
): AnchoredPos | null {
  // 位置和"量的是哪个元素"绑在一起存：浮层关掉再打开是个新元素，
  // 只留 pos 的话重开首帧会拿上一次的坐标闪一下
  const [state, setState] = useState<{ el: HTMLElement | null; pos: AnchoredPos | null }>(
    { el: null, pos: null });

  const measure = useCallback(() => {
    if (!anchor || !floating) {
      return;
    }
    const a = anchor.getBoundingClientRect();
    const f = floating.getBoundingClientRect();
    const vw = window.innerWidth;
    const vh = window.innerHeight;

    // 首选方向放不下就翻面；两边都放不下时选空间大的那边
    const spaceBelow = vh - a.bottom - gap;
    const spaceAbove = a.top - gap;
    let placement: Placement = prefer;
    if (prefer === 'bottom' && spaceBelow < f.height && spaceAbove > spaceBelow) {
      placement = 'top';
    } else if (prefer === 'top' && spaceAbove < f.height && spaceBelow > spaceAbove) {
      placement = 'bottom';
    }

    const top = placement === 'bottom' ? a.bottom + gap : a.top - gap - f.height;
    const centered = a.left + a.width / 2 - f.width / 2;
    const left = Math.min(Math.max(centered, EDGE), Math.max(EDGE, vw - f.width - EDGE));

    setState({
      el: floating,
      pos: { top: Math.min(Math.max(top, EDGE), Math.max(EDGE, vh - f.height - EDGE)), left, placement },
    });
  }, [anchor, floating, prefer, gap]);

  useLayoutEffect(() => {
    if (!open) {
      return;
    }
    // 放进 rAF 而不是直接调：effect 体内同步 setState 会被判为级联渲染，
    // 而且浮层的尺寸本来就该等它这一帧渲染完再量
    const raf = requestAnimationFrame(measure);
    window.addEventListener('resize', measure);
    // capture=true：锚点可能在内层滚动容器里，冒泡阶段收不到它的 scroll
    window.addEventListener('scroll', measure, true);
    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener('resize', measure);
      window.removeEventListener('scroll', measure, true);
    };
  }, [open, measure]);

  // 元素对不上就当没量过——调用方据此保持透明，不会在旧坐标上闪一下
  return open && state.el === floating ? state.pos : null;
}
