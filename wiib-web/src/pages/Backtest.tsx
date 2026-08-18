import { useSearchParams } from 'react-router-dom';
import { FlaskConical, Bot, MousePointerClick } from 'lucide-react';
import { StrategyBacktestPanel } from '../components/backtest/StrategyBacktestPanel';
import { ReplayPanel } from '../components/backtest/ReplayPanel';
import { cn } from '../lib/utils';

/**
 * 回测页：一页双模式。
 * - 策略回测：项目四策略跑历史行情，K线标注进出场+工作记录还原策略每一步；
 * - 手动复盘：按所选周期（5m~1d）逐根揭示行情，自己按收盘价开多开空练盘感。
 * 两模式共享 BacktestChart（画线工具/时间脱敏/回放游标都在图表层）。
 */
export function Backtest() {
  const [params, setParams] = useSearchParams();
  const mode = params.get('mode') === 'replay' ? 'replay' : 'strategy';

  const switchMode = (m: 'strategy' | 'replay') => {
    if (m === mode) return;
    setParams(m === 'replay' ? { mode: 'replay' } : {}, { replace: true });
  };

  return (
    <div className="page-shell p-4 md:p-6 space-y-5">
      {/* ===== Header + 模式切换 ===== */}
      <div className="flex items-center gap-3 flex-wrap">
        <div className="w-11 h-11 rounded-lg pt-card flex items-center justify-center bg-primary/10">
          <FlaskConical className="w-5.5 h-5.5 text-primary" />
        </div>
        {/* flex-1 把 basis 归零、只按剩余空间伸缩：副标题的 max-content 有 330px，
            带着这个宽度参与换行判定就会独占窄屏一行、把标题挤下去
            （truncate 只管渲染，不参与 flex 的换行判定） */}
        <div className="min-w-0 flex-1">
          <h1 className="text-xl font-black tracking-tight">回测</h1>
          <p className="text-[11px] text-muted-foreground truncate">
            {mode === 'strategy'
              ? '本地 5m K线 · 与实盘同一撮合口径 · 右侧工作记录还原策略每一步'
              : '逐根揭示历史行情 · 按收盘价开多开空、加减仓 · 练自己的盘感'}
          </p>
        </div>
        {/* 切换器窄屏独占第二行、两颗平分（w-full + flex-1），标题行整行留给图标与副标题；
            sm 起收回自然宽度靠右 */}
        <div className="w-full sm:w-auto sm:ml-auto flex rounded-lg border border-border overflow-hidden">
          <button type="button" onClick={() => switchMode('strategy')}
            className={cn('flex-1 sm:flex-none px-3.5 h-10 text-xs font-black flex items-center gap-1.5 transition-colors',
              mode === 'strategy' ? 'bg-primary text-primary-foreground' : 'bg-card-2 text-muted-foreground hover:bg-surface-hover')}>
            <Bot className="w-4 h-4" /> 策略回测
          </button>
          <button type="button" onClick={() => switchMode('replay')}
            className={cn('flex-1 sm:flex-none px-3.5 h-10 text-xs font-black flex items-center gap-1.5 transition-colors',
              mode === 'replay' ? 'bg-primary text-primary-foreground' : 'bg-card-2 text-muted-foreground hover:bg-surface-hover')}>
            <MousePointerClick className="w-4 h-4" /> 手动复盘
          </button>
        </div>
      </div>

      {mode === 'strategy' ? <StrategyBacktestPanel /> : <ReplayPanel />}
    </div>
  );
}
