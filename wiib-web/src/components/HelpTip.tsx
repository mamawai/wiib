import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { HelpCircle } from 'lucide-react';
import { cn } from '../lib/utils';
import { useAnchoredPosition, type Placement } from '../hooks/useAnchoredPosition';

/**
 * 小问号点击弹说明气泡。
 *
 * 气泡走 portal 挂到 body + position:fixed：旧版是 absolute 定位在图标里，
 * 图标一靠近屏幕边缘气泡就溢出屏幕（手机上几乎必现），外层只要有 overflow:hidden 还会被整块裁掉。
 * 现在由 useAnchoredPosition 按实测位置钳进视口，两个毛病一起消失。
 *
 * side 从"硬指定"降级为偏好：那一侧放不下会自动翻面，老调用点传的 side 照常生效。
 */
export function HelpTip({ text, side = 'bottom', iconClassName }: {
  text: string;
  side?: Placement;
  iconClassName?: string;
}) {
  const [open, setOpen] = useState(false);
  // 锚点也用回调 ref 存进 state：render 期间读 ref.current 在并发渲染下不安全
  const [anchor, setAnchor] = useState<HTMLButtonElement | null>(null);
  const [bubble, setBubble] = useState<HTMLSpanElement | null>(null);
  const pos = useAnchoredPosition(anchor, bubble, open, side);

  // 点击外部 / Esc 关闭。气泡在 body 上，不能靠祖先节点判断"外部"，改用两个 ref 各判一次
  useEffect(() => {
    if (!open) {
      return;
    }
    const onDown = (e: MouseEvent | TouchEvent) => {
      const t = e.target as Node;
      if (!anchor?.contains(t) && !bubble?.contains(t)) {
        setOpen(false);
      }
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('touchstart', onDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDown);
      document.removeEventListener('touchstart', onDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open, anchor, bubble]);

  return (
    <>
      <button
        ref={setAnchor}
        type="button"
        aria-label="说明"
        onClick={e => { e.stopPropagation(); setOpen(v => !v); }}
        className="inline-flex text-muted-foreground hover:text-foreground transition-colors cursor-pointer"
      >
        <HelpCircle className={cn('w-3.5 h-3.5', iconClassName)} />
      </button>
      {open && createPortal(
        <span
          ref={setBubble}
          role="tooltip"
          style={{ top: pos?.top ?? 0, left: pos?.left ?? 0 }}
          className={cn(
            'fixed z-[60] w-52 max-w-[calc(100vw-16px)] p-2.5 rounded-lg border bg-card text-xs',
            'text-muted-foreground shadow-lg leading-relaxed whitespace-pre-line',
            // 首帧还没量出位置，先隐形，免得在左上角闪一下
            pos ? 'opacity-100' : 'opacity-0',
          )}
        >
          {text}
        </span>,
        document.body,
      )}
    </>
  );
}
