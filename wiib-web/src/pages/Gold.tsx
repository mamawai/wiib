import { Coins } from 'lucide-react';

export function Gold() {
  return (
    <div className="max-w-4xl mx-auto p-4 flex flex-col items-center justify-center min-h-[60vh]">
      <div className="p-4 rounded-full bg-yellow-500/10 mb-4">
        <Coins className="w-12 h-12 text-yellow-500" />
      </div>
      <h1 className="text-2xl font-bold mb-2">黄金交易</h1>
      <p className="text-muted-foreground">敬请期待</p>
    </div>
  );
}
