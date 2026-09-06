import { useEffect, useRef } from 'react';

/**
 * 数字滚动：挂载时 0 → to，之后 to 变了就从当前画出的值补间过去，easeOutQuart。
 * <p>
 * 直接写 textContent 不走 state：一次滚动几十帧，每帧 setState 就是整页重渲染。
 * 用法 const ref = useCountUp<HTMLSpanElement>(v, n => `$${fmtNum(n)}`); 再把 ref 挂到 span/b 上。
 */
export function useCountUp<T extends HTMLElement = HTMLElement>(to: number, render: (v: number) => string, ms = 800) {
  const ref = useRef<T | null>(null);
  const cur = useRef(0);          // 已经画出去的值，to 变了从这儿接着补
  const fmt = useRef(render);

  // 声明在滚动那个 effect 前面：换了 render 身份不该重启动画，只换格式化
  useEffect(() => { fmt.current = render; });

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const paint = (v: number) => {
      cur.current = v;
      el.textContent = fmt.current(v);
    };

    if (matchMedia('(prefers-reduced-motion: reduce)').matches) { paint(to); return; }

    const from = cur.current;
    if (from === to) { paint(to); return; }

    const t0 = performance.now();
    let raf = 0;
    const step = (now: number) => {
      const p = Math.min(1, (now - t0) / ms);
      const e = 1 - (1 - p) ** 4;
      // 最后一帧钉精确终值，缓动算出来的尾数不能当数
      paint(p < 1 ? from + (to - from) * e : to);
      if (p < 1) raf = requestAnimationFrame(step);
    };
    raf = requestAnimationFrame(step);
    return () => cancelAnimationFrame(raf);
  }, [to, ms]);

  return ref;
}
