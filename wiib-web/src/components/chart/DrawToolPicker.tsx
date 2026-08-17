import { useCallback, useRef, useState, type ReactNode } from 'react';
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
 */
const TOOLS: { k: Tool; icon: ReactNode; name: string; title: string }[] = [
  { k: null, icon: <MousePointer2 className="w-3.5 h-3.5" />, name: '选择', title: '选择/拖拽（Esc 取消选中，Del 删除）' },
  { k: 'trend', icon: <Slash className="w-3.5 h-3.5" />, name: '趋势线', title: '趋势线：点两下定两端' },
  { k: 'ray', icon: <MoveUpRight className="w-3.5 h-3.5" />, name: '射线', title: '射线：起点 + 方向点，沿方向延伸到图边' },
  { k: 'hline', icon: <Minus className="w-3.5 h-3.5" />, name: '水平线', title: '水平线：点一下即成' },
  { k: 'vline', icon: <Minus className="w-3.5 h-3.5 rotate-90" />, name: '垂直线', title: '垂直线：点一下即成' },
  { k: 'channel', icon: <Equal className="w-3.5 h-3.5 -rotate-45" />, name: '平行通道', title: '平行通道：点三下（基线两端 + 平行线过的点）' },
  { k: 'rect', icon: <RectangleHorizontal className="w-3.5 h-3.5" />, name: '矩形', title: '矩形：点两下定对角' },
  { k: 'fib', icon: <span className="text-[10px] font-extrabold leading-none tracking-tight">FIB</span>, name: '斐波那契', title: '斐波那契回撤：点两下定 0/1 两端' },
  { k: 'long', icon: <TrendingUp className="w-3.5 h-3.5 text-gain" />, name: '多头仓位', title: '多头仓位：先点入场再点止损，止盈按 2:1 派生（可拖）' },
  { k: 'short', icon: <TrendingDown className="w-3.5 h-3.5 text-loss" />, name: '空头仓位', title: '空头仓位：先点入场再点止损，止盈按 2:1 派生（可拖）' },
  { k: 'range', icon: <Ruler className="w-3.5 h-3.5" />, name: '价格区间', title: '价格区间：点两下量价差/涨跌幅/根数/时长' },
  { k: 'text', icon: <Type className="w-3.5 h-3.5" />, name: '文字', title: '文字标注：点一下再输入' },
];

/** 与周期切换组同一套视觉：激活=顶部主色内阴影 */
const iconCls = (on: boolean) =>
  `px-2 py-1.5 flex items-center justify-center transition-colors cursor-pointer ${
    on ? 'bg-card-2 text-foreground shadow-[inset_0_2px_0_var(--color-primary)]'
       : 'text-muted-foreground hover:bg-surface-hover hover:text-foreground'}`;

export function DrawToolPicker({ tool, onSelect }: { tool: Tool; onSelect: (t: Tool) => void }) {
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
          <button key={b.k ?? 'pick'} type="button" title={b.title}
                  onClick={() => onSelect(b.k)} className={iconCls(tool === b.k)}>
            {b.icon}
          </button>
        ))}
      </div>

      {/* 手机：当前工具 + 弹层 */}
      <div ref={wrapRef} className="relative md:hidden">
        <button type="button" onClick={() => setOpen(o => !o)} title={cur.title}
                className={`rounded-md border border-border gap-1 ${iconCls(tool !== null)}`}>
          {cur.icon}
          <span className="text-[10px] font-bold">{cur.name}</span>
          <ChevronDown className={`w-3 h-3 transition-transform ${open ? 'rotate-180' : ''}`} />
        </button>
        {open && (
          <div className="absolute left-0 top-full mt-1 z-20 w-[248px] p-1.5 rounded-lg border border-border bg-card shadow-lg grid grid-cols-3 gap-1">
            {TOOLS.map(b => (
              <button key={b.k ?? 'pick'} type="button" title={b.title}
                      onClick={() => { onSelect(b.k); setOpen(false); }}
                      className={`h-12 rounded-md flex flex-col items-center justify-center gap-1 transition-colors ${
                        tool === b.k ? 'bg-primary/15 text-primary' : 'text-muted-foreground hover:bg-surface-hover hover:text-foreground'}`}>
                {b.icon}
                <span className="text-[10px] font-bold leading-none">{b.name}</span>
              </button>
            ))}
          </div>
        )}
      </div>
    </>
  );
}
