import { useEffect, useState, type ComponentType } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Landmark, Bitcoin, Gem, Globe, ChevronRight, type LucideProps } from 'lucide-react';
import { cn } from '../lib/utils';
import { COIN_MAP, COMMODITY_LIST } from '../lib/coinConfig';
import { CoinMarketRow, BStockMarketRow } from './MarketRow';
import { Skeleton } from './ui/skeleton';
import { bstockApi } from '../api';
import type { BStock } from '../types';

/** 分类头：整行可点，跳转对应市场页。title/subtitle 由调用方翻好了传进来，壳子只管「全部」 */
function CategoryHeader({ icon: Icon, title, subtitle, iconColor, to }: {
  icon: ComponentType<LucideProps>; title: string; subtitle: string;
  iconColor: string; to: string;
}) {
  const navigate = useNavigate();
  const { t } = useTranslation('common');
  return (
    <button
      onClick={() => navigate(to)}
      className="flex items-center gap-2 w-full px-3 py-2.5 border-b border-border group cursor-pointer hover:bg-surface-hover transition-colors"
    >
      {/* 四列并排时这行只有 ~294px：主标题/图标/「全部」都锁死不缩，让副标题独自吃掉差额并省略号收尾，
         否则 flex 会按比例一起压，主标题先折成两行 */}
      <Icon className={cn('w-3.5 h-3.5 shrink-0', iconColor)} />
      <span className="text-xs font-bold shrink-0">{title}</span>
      <span className="text-[10px] text-muted-foreground min-w-0 truncate">{subtitle}</span>
      <span className="ml-auto shrink-0 inline-flex items-center gap-0.5 text-[10px] font-semibold text-muted-foreground group-hover:text-primary transition-colors">
        {t('all')} <ChevronRight className="w-3 h-3" />
      </span>
    </button>
  );
}

/**
 * 首页市场行情：bStock / Crypto / 大宗商品 / TradFi 合约四分类终端表，每类 2 个代表标的。
 * 点分类头去市场页，点行直达交易页。大屏四列并排，中屏两两成行，移动端纵向堆叠。
 */
export function HomeMarketSection() {
  const { t } = useTranslation('home');
  const [topStocks, setTopStocks] = useState<BStock[]>([]);

  // 市值前 2 作代表。只拉一次：这里要的是"哪两只 + 名称"这类静态元数据，
  // 价格和涨跌由 BStockMarketRow 自己走 Spot 流实时刷，不需要轮询
  useEffect(() => {
    bstockApi.list()
      .then(list => setTopStocks([...list].sort((a, b) => (b.marketCap ?? 0) - (a.marketCap ?? 0)).slice(0, 2)))
      .catch(() => {});
  }, []);

  const cryptoReps = [COIN_MAP.BTCUSDT, COIN_MAP.ETHUSDT];
  // TradFi 代表：SpaceX（话题标的）+ SK海力士（成交最活跃）
  const tradfiReps = [COIN_MAP.SPCXUSDT, COIN_MAP.SKHYNIXUSDT];

  return (
    <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-4">
      <div className="@container pt-card rounded-lg overflow-hidden">
        <CategoryHeader icon={Landmark} title={t('market.stocks')} subtitle={t('market.stocksSub')} iconColor="text-blue-500" to="/bstock" />
        {topStocks.length
          ? topStocks.map(s => <BStockMarketRow key={s.symbol} stock={s} />)
          : Array.from({ length: 2 }).map((_, i) => <Skeleton key={i} className="h-[52px] m-2" />)}
      </div>

      <div className="@container pt-card rounded-lg overflow-hidden">
        <CategoryHeader icon={Bitcoin} title={t('market.crypto')} subtitle={t('market.cryptoSub')} iconColor="text-amber-500" to="/coin" />
        {cryptoReps.map(c => <CoinMarketRow key={c.symbol} cfg={c} />)}
      </div>

      <div className="@container pt-card rounded-lg overflow-hidden">
        <CategoryHeader icon={Gem} title={t('market.commodity')} subtitle={t('market.commoditySub')} iconColor="text-yellow-500" to="/commodity" />
        {COMMODITY_LIST.map(c => <CoinMarketRow key={c.symbol} cfg={c} />)}
      </div>

      <div className="@container pt-card rounded-lg overflow-hidden">
        <CategoryHeader icon={Globe} title={t('market.tradfi')} subtitle={t('market.tradfiSub')} iconColor="text-sky-500" to="/tradfi" />
        {tradfiReps.map(c => <CoinMarketRow key={c.symbol} cfg={c} />)}
      </div>
    </div>
  );
}
