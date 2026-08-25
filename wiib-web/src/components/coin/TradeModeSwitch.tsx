import { useTranslation } from 'react-i18next';

/** 合约/现货模式切换（现货/合约两个交易面板头部共用；爆仓入口已移至首页爆仓动态卡） */
export function TradeModeSwitch({ mode, futuresOnly, onModeChange }: {
  mode: 'spot' | 'futures';
  futuresOnly?: boolean;
  onModeChange: (m: 'spot' | 'futures') => void;
}) {
  const { t } = useTranslation('trade');
  const btn = (active: boolean) =>
    `px-5 py-2.5 text-xs font-semibold transition-colors cursor-pointer ${
      active
        ? 'bg-card-2 text-foreground shadow-[inset_0_2px_0_var(--color-primary)]'
        : 'text-muted-foreground hover:bg-surface-hover hover:text-foreground'
    }`;
  return (
    <div className="flex rounded-md border border-border overflow-hidden divide-x divide-border bg-card">
      <button onClick={() => onModeChange('futures')} className={btn(mode === 'futures')}>{t('mode.futures')}</button>
      {/* 纯合约标的（黄金/原油）无现货，藏掉现货切换 */}
      {!futuresOnly && (
        <button onClick={() => onModeChange('spot')} className={btn(mode === 'spot')}>{t('mode.spot')}</button>
      )}
    </div>
  );
}
