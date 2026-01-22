import type { Stock } from '../types';
import { cn } from '../lib/utils';
import { TrendingUp, TrendingDown, Minus } from 'lucide-react';

interface Props {
  stock: Stock;
  onClick?: () => void;
}

export function StockCard({ stock, onClick }: Props) {
  const change = stock.change ?? 0;
  const changePct = stock.changePct ?? 0;
  const price = stock.price ?? 0;
  const isUp = change > 0;
  const isDown = change < 0;

  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        "w-full text-left",
        "flex justify-between items-center p-4 cursor-pointer transition-all duration-200",
        "hover:bg-accent/50 border-b border-border last:border-b-0",
        "group",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
      )}
    >
      <div className="flex flex-col gap-1">
        <span className="font-medium text-foreground group-hover:text-primary transition-colors">
          {stock.name}
        </span>
        <span className="text-xs text-muted-foreground">{stock.code}</span>
      </div>

      <div className="flex flex-col items-end gap-1">
        <div className="flex items-center gap-1">
          {isUp ? (
            <TrendingUp className="w-4 h-4 text-red-500" />
          ) : isDown ? (
            <TrendingDown className="w-4 h-4 text-green-500" />
          ) : (
            <Minus className="w-4 h-4 text-muted-foreground" />
          )}
          <span className={cn(
            "text-lg font-semibold tabular-nums",
            isUp ? "text-red-500" : isDown ? "text-green-500" : "text-muted-foreground"
          )}>
            {price.toFixed(2)}
          </span>
        </div>
        <span className={cn(
          "text-xs tabular-nums",
          isUp ? "text-red-500" : isDown ? "text-green-500" : "text-muted-foreground"
        )}>
          {isUp ? '+' : ''}{change.toFixed(2)} ({isUp ? '+' : ''}{changePct.toFixed(2)}%)
        </span>
      </div>
    </button>
  );
}
