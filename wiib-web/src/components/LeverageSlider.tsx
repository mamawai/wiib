import { useTranslation } from 'react-i18next';

/**
 * 杠杆滑杆：一条墨线轨道 + 方旋钮 + 下面一排可点档位。
 *
 * 档位在轨道上等距排（1/10/25/50… 挨个占一格），段内线性取整——不然 5x 的旋钮会贴在最左边，
 * 跟它自己的标签对不上。交互层是一个透明的原生 <input type="range">（绝对定位盖住轨道区）跑位置值，
 * 拖拽/点击/键盘/aria 全部由浏览器原生实现——此前两版手写 pointer 事件跟踪都出过"回拖卡住"类 bug。
 */
interface LeverageSliderProps {
  value: number;
  max: number;        // 当前币种最高杠杆（档位未加载时可能为 0）
  ticks: number[];    // 可点击档位（已按 min/max 过滤）
  onChange: (v: number) => void;
  min?: number;       // 下限，默认 1；持仓调杠杆时逐仓只能调高（下限=当前杠杆）
}

/** 原生 range 的位置分辨率：0..1000 映射轨道全长 */
const RES = 1000;

export function LeverageSlider({ value, max, ticks, onChange, min = 1 }: LeverageSliderProps) {
  const { t } = useTranslation('trade');
  const hi = Math.max(max, min);
  const disabled = hi <= min;
  // 轨道上的节点：档位 + 两端（有的币最高杠杆不在常规档位里，补成最后一格）
  const nodes = [...new Set([min, ...ticks.filter(v => v >= min && v <= hi), hi])].sort((a, b) => a - b);
  const seg = nodes.length - 1;

  /** 杠杆值 → 位置 0..1 */
  const posOf = (v: number) => {
    if (seg <= 0) return 0;
    if (v <= nodes[0]) return 0;
    if (v >= nodes[seg]) return 1;
    let i = 0;
    while (i < seg - 1 && v >= nodes[i + 1]) i++;
    return (i + (v - nodes[i]) / (nodes[i + 1] - nodes[i])) / seg;
  };
  /** 位置 0..1 → 杠杆值（整数） */
  const valueAt = (p: number) => {
    if (seg <= 0) return nodes[0];
    const x = Math.max(0, Math.min(1, p)) * seg;
    const i = Math.min(seg - 1, Math.floor(x));
    return Math.round(nodes[i] + (x - i) * (nodes[i + 1] - nodes[i]));
  };

  const pct = posOf(value) * 100;

  return (
    <div className={disabled ? 'opacity-45' : ''}>
      <div className="relative">
        <div className="relative h-1.5 bg-border mx-1.5 mt-3 mb-1.5">
          <div className="absolute left-0 top-0 bottom-0 bg-foreground" style={{ width: `${pct}%` }} />
          <div className="absolute top-1/2 w-4 h-4 bg-background border-2 border-foreground -translate-x-1/2 -translate-y-1/2" style={{ left: `${pct}%` }} />
        </div>
        {/* 拇指热区放到 28px，手机好按；touch-action:none 免得拖滑杆带着页面滚 */}
        <input
          type="range"
          className="absolute inset-0 w-full h-full m-0 opacity-0 cursor-pointer appearance-none touch-none disabled:cursor-default [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:w-7 [&::-webkit-slider-thumb]:h-7 [&::-moz-range-thumb]:w-7 [&::-moz-range-thumb]:h-7 [&::-moz-range-thumb]:border-0"
          min={0}
          max={RES}
          step={1}
          value={Math.round(posOf(value) * RES)}
          disabled={disabled}
          aria-label={t('lev.label')}
          aria-valuetext={`${value}x`}
          onChange={e => onChange(valueAt(Number(e.target.value) / RES))}
        />
      </div>
      <div className="num flex justify-between px-0.5 text-[11px] text-muted-foreground">
        {nodes.map(node => (
          <button
            key={node}
            type="button"
            className={`cursor-pointer transition-colors ${value === node ? 'text-foreground font-bold' : 'hover:text-foreground'}`}
            onClick={() => onChange(node)}
          >
            {node}
          </button>
        ))}
      </div>
    </div>
  );
}
