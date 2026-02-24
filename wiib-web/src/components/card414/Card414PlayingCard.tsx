import { cn } from '../../lib/utils';

interface Props {
  card: string;
  selected?: boolean;
  isHun?: boolean;
  faceDown?: boolean;
  onClick?: () => void;
  className?: string;
  size?: 'sm' | 'md';
}

const SUIT_SYMBOL: Record<string, string> = { H: '♥', D: '♦', C: '♣', S: '♠' };
const SUIT_COLOR: Record<string, string> = { H: 'text-red-600', D: 'text-red-600', C: 'text-gray-800', S: 'text-gray-800' };
const RANK_DISPLAY: Record<string, string> = { T: '10', J: 'J', Q: 'Q', K: 'K', A: 'A' };

function parseCard(card: string) {
  if (card === 'BK') return { color: 'text-red-600', display: 'Joker', suitChar: '' };
  if (card === 'JK') return { color: 'text-gray-800', display: 'Joker', suitChar: '' };
  const suit = card[0];
  const rank = card.substring(1);
  return {
    color: SUIT_COLOR[suit] || 'text-foreground',
    display: RANK_DISPLAY[rank] || rank,
    suitChar: SUIT_SYMBOL[suit] || '',
  };
}

export function Card414PlayingCard({ card, selected, isHun, faceDown, onClick, className, size = 'md' }: Props) {
  if (faceDown) {
    return (
      <div className={cn(
        'card414-card flex items-center justify-center bg-gradient-to-br from-blue-800 to-blue-900 border border-blue-600/50',
        size === 'sm' ? 'w-9 h-12' : '',
        className
      )}>
        <div className="text-blue-400/60 text-[10px] font-bold">414</div>
      </div>
    );
  }

  const { color, display, suitChar } = parseCard(card);
  const isJoker = card === 'BK' || card === 'JK';
  const isSm = size === 'sm';

  return (
    <div
      onClick={onClick}
      className={cn(
        'card414-card flex flex-col justify-between bg-white border border-gray-200 select-none',
        isSm ? 'w-9 h-12 p-0.5 text-[10px]' : 'p-1.5 text-sm',
        selected && 'selected',
        isHun && 'hun',
        onClick && 'cursor-pointer hover:brightness-105 active:scale-95',
        className
      )}
    >
      {isJoker ? (
        <>
          <div className={cn('font-bold leading-none self-start', isSm ? 'text-[8px]' : 'text-xs', color)}>
            {card === 'BK' ? 'BIG' : 'SM'}
          </div>
          <div className={cn('text-center font-bold leading-none', isSm ? 'text-[8px]' : 'text-[10px]', color)}>
            Joker
          </div>
          <div className={cn('font-bold leading-none self-end rotate-180', isSm ? 'text-[8px]' : 'text-xs', color)}>
            {card === 'BK' ? 'BIG' : 'SM'}
          </div>
        </>
      ) : (
        <>
          <div className={cn('font-bold leading-none self-start', color)}>{display}</div>
          <div className={cn('text-center leading-none', isSm ? 'text-sm' : 'text-lg', color)}>{suitChar}</div>
          <div className={cn('font-bold leading-none self-end rotate-180', color)}>{display}</div>
        </>
      )}
    </div>
  );
}
