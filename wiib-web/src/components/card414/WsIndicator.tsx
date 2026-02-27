import { cn } from '../../lib/utils';

export function WsIndicator({ connected }: { connected: boolean }) {
  return (
    <div className="flex items-center gap-1.5">
      {!connected && (
        <span className="text-[10px] text-red-400/80 animate-pulse">重连中</span>
      )}
      <span className="relative flex h-2 w-2">
        <span className={cn(
          'absolute inset-0 rounded-full',
          connected
            ? 'bg-emerald-400/60 animate-ping'
            : 'bg-red-500/60 animate-ping'
        )} style={{ animationDuration: connected ? '2.5s' : '1s' }} />
        <span className={cn(
          'relative inline-flex h-2 w-2 rounded-full',
          connected
            ? 'bg-emerald-400 shadow-[0_0_4px_rgba(52,211,153,0.5)]'
            : 'bg-red-500 shadow-[0_0_4px_rgba(239,68,68,0.5)]'
        )} />
      </span>
    </div>
  );
}
