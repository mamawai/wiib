import { Globe } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { CoinMarketGrid } from '../components/CoinMarketGrid';
import { TRADFI_LIST } from '../lib/coinConfig';

export function TradFiSelect() {
  const { t } = useTranslation('market');
  return (
    <div className="max-w-4xl mx-auto px-4 py-8 space-y-6">
      <div className="flex flex-col items-center gap-1.5">
        <div className="flex items-center gap-2.5">
          <div className="p-1.5 rounded-lg bg-sky-500/10">
            <Globe className="w-5 h-5 text-sky-500" />
          </div>
          <h1 className="text-xl font-bold">{t('select.tradfiTitle')}</h1>
        </div>
        <p className="text-[11px] text-muted-foreground">{t('select.tradfiSub')}</p>
      </div>
      <CoinMarketGrid list={TRADFI_LIST} />
    </div>
  );
}
