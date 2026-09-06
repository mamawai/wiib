import { Loader2, Check } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { cn } from '../lib/utils';

/**
 * 下单按钮：一整条实色墨块，做多/买入走 gain，做空/卖出走 loss。
 * 提交中转菊花，成功一闪对勾（回落由调用方的 success 计时器管）。
 */
interface FuturesActionButtonProps {
  side: 'LONG' | 'SHORT' | 'BUY' | 'SELL';
  leverage?: number;
  /** 合约=币对（开多 BTCUSDT · 5x），现货=币名（买入 BTC） */
  label?: string;
  loading?: boolean;
  success?: boolean;
  disabled?: boolean;
  className?: string;
  onClick?: () => void;
}

export function FuturesActionButton({
  side,
  leverage,
  label,
  loading = false,
  success = false,
  disabled = false,
  className,
  onClick,
}: FuturesActionButtonProps) {
  const { t } = useTranslation('trade');
  const isSpot = side === 'BUY' || side === 'SELL';
  const isUp = side === 'LONG' || side === 'BUY';
  const text = isSpot
    ? `${t(side === 'BUY' ? 'side.buy' : 'side.sell')}${label ? ` ${label}` : ''}`
    : `${t(side === 'LONG' ? 'side.openLong' : 'side.openShort')}${label ? ` ${label}` : ''} · ${leverage}x`;

  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled || loading || success}
      className={cn('btn w-full h-14 text-[18px] font-extrabold disabled:opacity-50', isUp ? 'gain' : 'loss', className)}
    >
      {loading ? <Loader2 className="w-5 h-5 animate-spin" /> : success ? <Check className="w-5 h-5" /> : text}
    </button>
  );
}
