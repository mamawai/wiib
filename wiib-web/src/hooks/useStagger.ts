import { useEffect, useRef } from 'react';

/**
 * 列表逐项登场：给子元素编号，CSS 按 --i 递增延迟（见 index.css .stagger>*）。
 * 挂到容器上就行，子元素随数据增减，所以每次渲染后都重编一遍号——只是写几个 style 属性，便宜。
 */
export function useStagger<T extends HTMLElement>() {
  const ref = useRef<T | null>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    [...el.children].forEach((c, i) => (c as HTMLElement).style.setProperty('--i', String(i)));
    el.classList.add('stagger');
  });

  return ref;
}
