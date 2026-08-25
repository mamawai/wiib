import { useCallback, useRef, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import {
  ChevronDown, Equal, Minus, MousePointer2, MoveUpRight, RectangleHorizontal, Ruler, Slash,
  TrendingDown, TrendingUp, Type,
} from 'lucide-react';
import { useClickOutside } from '../../hooks/useClickOutside';
import type { Tool } from './useDrawings';

/**
 * 画线工具清单（CandleChart / BacktestChart 共用一份，图标语义一致）。
 * 桌面端整排图标直接点；手机端收成一个"当前工具 + 展开"按钮，弹层里带名字——
 * 一排十二个 14px 图标在 375 宽的屏上既挤又认不出哪个是哪个。
 * <p>常量在组件外拿不到 t，所以存词表 key 字面量，渲染时再查（key 写死才 grep 得到谁在用）。
 */
const TOOLS: { k: Tool; icon: ReactNode; nameKey: string; titleKey: string }[] = [
  { k: null, icon: <MousePointer2 className="w-3.5 h-3.5" />, nameKey: 'drawTool.pick', titleKey: 'drawTip.pick' },
  { k: 'trend', icon: <Slash className="w-3.5 h-3.5" />, nameKey: 'drawTool.trend', titleKey: 'drawTip.trend' },
  { k: 'ray', icon: <MoveUpRight className="w-3.5 h-3.5" />, nameKey: 'drawTool.ray', titleKey: 'drawTip.ray' },
  { k: 'hline', icon: <Minus className="w-3.5 h-3.5" />, nameKey: 'drawTool.hline', titleKey: 'drawTip.hline' },
  { k: 'vline', icon: <Minus className="w-3.5 h-3.5 rotate-90" />, nameKey: 'drawTool.vline', titleKey: 'drawTip.vline' },
  { k: 'channel', icon: <Equal className="w-3.5 h-3.5 -rotate-45" />, nameKey: 'drawTool.channel', titleKey: 'drawTip.channel' },
  { k: 'rect', icon: <RectangleHorizontal className="w-3.5 h-3.5" />, nameKey: 'drawTool.rect', titleKey: 'drawTip.rect' },
  { k: 'fib', icon: <span className="text-[10px] font-extrabold leading-none tracking-tight">FIB</span>, nameKey: 'drawTool.fib', titleKey: 'drawTip.fib' },
  { k: 'long', icon: <TrendingUp className="w-3.5 h-3.5 text-gain" />, nameKey: 'drawTool.long', titleKey: 'drawTip.long' },
  { k: 'short', icon: <TrendingDown className="w-3.5 h-3.5 text-loss" />, nameKey: 'drawTool.short', titleKey: 'drawTip.short' },
  { k: 'range', icon: <Ruler className="w-3.5 h-3.5" />, nameKey: 'drawTool.range', titleKey: 'drawTip.range' },
  { k: 'text', icon: <Type className="w-3.5 h-3.5" />, nameKey: 'drawTool.text', titleKey: 'drawTip.text' },
];

/** 与周期切换组同一套视觉：激活=顶部主色内阴影 */
const iconCls = (on: boolean) =>
  `px-2 py-1.5 flex items-center justify-center transition-colors cursor-pointer ${
    on ? 'bg-card-2 text-foreground shadow-[inset_0_2px_0_var(--color-primary)]'
       : 'text-muted-foreground hover:bg-surface-hover hover:text-foreground'}`;

export function DrawToolPicker({ tool, onSelect }: { tool: Tool; onSelect: (t: Tool) => void }) {
  const { t: tr } = useTranslation('market');
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);
  const close = useCallback(() => setOpen(false), []);
  useClickOutside(wrapRef, close, open);
  const cur = TOOLS.find(t => t.k === tool) ?? TOOLS[0];

  return (
    <>
      {/* 桌面：整排图标 */}
      <div className="hidden md:flex rounded-md border border-border overflow-hidden divide-x divide-border">
        {TOOLS.map(b => (
          <button key={b.k ?? 'pick'} type="button" title={tr(b.titleKey)}
                  onClick={() => onSelect(b.k)} className={iconCls(tool === b.k)}>
            {b.icon}
          </button>
        ))}
      </div>

      {/* 手机：当前工具 + 弹层 */}
      <div ref={wrapRef} className="relative md:hidden">
        <button type="button" onClick={() => setOpen(o => !o)} title={tr(cur.titleKey)}
                className={`rounded-md border border-border gap-1 ${iconCls(tool !== null)}`}>
          {cur.icon}
          <span className="text-[10px] font-bold">{tr(cur.nameKey)}</span>
          <ChevronDown className={`w-3 h-3 transition-transform ${open ? 'rotate-180' : ''}`} />
        </button>
        {open && (
          <div className="absolute left-0 top-full mt-1 z-20 w-[248px] p-1.5 rounded-lg border border-border bg-card shadow-lg grid grid-cols-3 gap-1">
            {TOOLS.map(b => (
              <button key={b.k ?? 'pick'} type="button" title={tr(b.titleKey)}
                      onClick={() => { onSelect(b.k); setOpen(false); }}
                      className={`h-12 rounded-md flex flex-col items-center justify-center gap-1 transition-colors ${
                        tool === b.k ? 'bg-primary/15 text-primary' : 'text-muted-foreground hover:bg-surface-hover hover:text-foreground'}`}>
                {b.icon}
                <span className="text-[10px] font-bold leading-none">{tr(b.nameKey)}</span>
              </button>
            ))}
          </div>
        )}
      </div>
    </>
  );
}
