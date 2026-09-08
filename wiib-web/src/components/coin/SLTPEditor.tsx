import { X } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { fmtNum } from '../../lib/utils';
import { getStepPrecision, FUTURES_COMMISSION_RATE, type SLTPRow } from './futuresMath';
import { NumInput } from './TradeFields';

/**
 * 止损/止盈多档编辑器：一档两个输入框——价格（右侧单位显示相对开仓价的涨跌幅）和数量（右侧单位是币名）；
 * 价格+数量齐全时实时预估触发盈亏（扣平仓手续费）与对应保证金份额的回报率。
 * <p>止损/止盈由 kind 区分，不拿显示文案判——文案会随语言变，判不出来。
 */
export function SLTPEditor({ rows, onChange, kind, posQty, minQty, entryPrice, margin, side, unit, minPriceStep = 0.01, priceFormatter = fmtNum }: {
  rows: SLTPRow[];
  onChange: (rows: SLTPRow[]) => void;
  kind: 'SL' | 'TP';
  posQty: number;
  minQty: number;
  entryPrice: number;
  margin: number;
  side: 'LONG' | 'SHORT';
  /** 数量单位=币名 */
  unit: string;
  minPriceStep?: number;
  priceFormatter?: (value?: number | null) => string;
}) {
  const { t } = useTranslation('trade');
  const isSL = kind === 'SL';
  const inputPriceStepText = minPriceStep.toFixed(getStepPrecision(minPriceStep));
  const qtyStepText = String(minQty);

  // 触发即市价平仓：盈亏差价×数量-taker手续费；回报率相对该档占用的保证金份额
  const estimate = (row: SLTPRow) => {
    const price = parseFloat(row.price) || 0;
    const qty = parseFloat(row.quantity) || 0;
    if (price <= 0 || qty <= 0 || entryPrice <= 0 || posQty <= 0) return null;
    const pnl = (price - entryPrice) * qty * (side === 'LONG' ? 1 : -1);
    const fee = price * qty * FUTURES_COMMISSION_RATE;
    const value = pnl - fee;
    const marginShare = margin * qty / posQty;
    const roi = marginShare > 0 ? (value / marginShare) * 100 : 0;
    return { value, roi };
  };

  /** 价格框右侧单位：这一档离开仓价多远 */
  const diffPct = (priceStr: string) => {
    const price = parseFloat(priceStr) || 0;
    if (price <= 0 || entryPrice <= 0) return '%';
    const d = (price - entryPrice) / entryPrice * 100;
    return `${d >= 0 ? '+' : ''}${d.toFixed(1)}%`;
  };

  const total = rows.reduce((s, r) => s + (parseFloat(r.quantity) || 0), 0);
  const over = total > posQty + 1e-9;

  return (
    <div className="flex flex-col gap-2.5">
      {rows.map((row, i) => {
        const est = estimate(row);
        return (
          <div key={i} className="flex flex-col gap-1.5">
            <div className="flex items-center gap-1.5">
              <NumInput
                className="flex-1 min-w-0"
                value={row.price}
                placeholder={isSL ? t('sltp.slPrice') : t('sltp.tpPrice')}
                step={inputPriceStepText}
                unit={diffPct(row.price)}
                onChange={v => { const n = [...rows]; n[i] = { ...n[i], price: v }; onChange(n); }}
              />
              {rows.length > 1 && (
                <button type="button" onClick={() => onChange(rows.filter((_, j) => j !== i))} className="ibtn w-[26px] h-[26px] shrink-0" aria-label={t('sltp.removeRow')}>
                  <X className="w-3.5 h-3.5" />
                </button>
              )}
            </div>
            <NumInput
              value={row.quantity}
              placeholder={qtyStepText}
              step={qtyStepText}
              min={0}
              max={posQty}
              unit={unit}
              onChange={v => { const n = [...rows]; n[i] = { ...n[i], quantity: v }; onChange(n); }}
            />
            {est && (
              <div className="flex flex-wrap gap-x-3 gap-y-0.5 text-[12px] text-muted-foreground">
                <span>{t('sltp.trigger', { price: priceFormatter(parseFloat(row.price)) })}</span>
                <span className={est.value >= 0 ? 'up' : 'dn'}>
                  {t('sltp.estPnl', { value: `${est.value >= 0 ? '+' : ''}${fmtNum(est.value)}` })}
                </span>
                <span className={est.roi >= 0 ? 'up' : 'dn'}>
                  {t('sltp.roi', { roi: `${est.roi >= 0 ? '+' : ''}${est.roi.toFixed(1)}` })}
                </span>
              </div>
            )}
          </div>
        );
      })}
      <div className="flex items-center justify-between gap-2">
        {rows.length < 4
          ? <button type="button" className="btn xs" onClick={() => onChange([...rows, { price: '', quantity: '' }])}>{t('sltp.add')}</button>
          : <span />}
        {/* 开仓面板里数量还没填时没有可分配的量，这行显示 0/0 纯噪音 */}
        {posQty > 0 && (
          <span className={`text-[12px] ${over ? 'dn' : 'text-muted-foreground'}`}>
            {t('sltp.allocated', { used: fmtNum(total * entryPrice), total: fmtNum(posQty * entryPrice) })}
          </span>
        )}
      </div>
    </div>
  );
}
