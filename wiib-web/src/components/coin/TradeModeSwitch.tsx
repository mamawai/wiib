import { useTranslation } from 'react-i18next';

/** 合约/现货模式切换（现货/合约两个交易面板头部共用；爆仓入口已移至首页爆仓动态卡） */
export function TradeModeSwitch({ mode, futuresOnly, onModeChange }: {
  mode: 'spot' | 'futures';
  futuresOnly?: boolean;
  onModeChange: (m: 'spot' | 'futures') => void;
}) {
  const { t } = useTranslation('trade');
  return (
    <div className="seg">
      <button className={mode === 'futures' ? 'on' : ''} onClick={() => onModeChange('futures')}>{t('mode.futures')}</button>
      {/* 纯合约标的（黄金/原油）无现货，藏掉现货切换 */}
      {!futuresOnly && (
        <button className={mode === 'spot' ? 'on' : ''} onClick={() => onModeChange('spot')}>{t('mode.spot')}</button>
      )}
    </div>
  );
}
