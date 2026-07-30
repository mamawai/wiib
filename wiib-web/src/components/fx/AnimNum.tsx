import { useEffect, useRef } from 'react';
import { fmtNum } from '../../lib/utils';

/**
 * 数值补间：旧值三次缓出到新值，600ms。持仓页整页的钱都走它。
 * <p>
 * 直接写 DOM textContent 不走 state：一次补间 36 帧，每帧 setState 就是 36 次
 * 组件重渲染，一页十几个数字叠起来必掉帧。这里只有一个 span，改文本最省。
 */
export function AnimNum({ value, prefix = '', suffix = '', duration = 600, fromZero = false }: {
  value: number;
  prefix?: string;
  suffix?: string;
  duration?: number;
  /** 首次挂载就从 0 滚到 value。缺省是首屏直接出数、只有后续变化才滚 */
  fromZero?: boolean;
}) {
  const ref = useRef<HTMLSpanElement>(null);
  const prev = useRef(fromZero ? 0 : value);
  const mounted = useRef(false);

  useEffect(() => {
    const paint = (v: number) => {
      if (ref.current) ref.current.textContent = prefix + fmtNum(v) + suffix;
    };

    if (!mounted.current) {
      mounted.current = true;
      // fromZero 时不在这儿收尾，落到下面从 prev(=0) 补间上去
      if (!fromZero) { paint(value); return; }
    }

    const from = prev.current;
    const to = value;
    prev.current = to;
    if (from === to) { paint(to); return; }

    const start = performance.now();
    let raf = 0;
    const tick = (now: number) => {
      const t = Math.min((now - start) / duration, 1);
      const ease = 1 - (1 - t) ** 3;
      paint(from + (to - from) * ease);
      if (t < 1) raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf);
  }, [value, prefix, suffix, duration, fromZero]);

  return <span ref={ref} />;
}
