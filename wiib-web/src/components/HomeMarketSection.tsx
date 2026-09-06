import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { cn } from '../lib/utils';
import { COIN_MAP, COMMODITY_LIST, formatCoinPrice } from '../lib/coinConfig';
import { useCoinQuote, useStockQuote } from '../hooks/useQuote';
import { useStagger } from '../hooks/useStagger';
import { Skeleton } from './ui/skeleton';
import { bstockApi } from '../api';
import type { BStock } from '../types';

const ROW = 'num hov grid grid-cols-[1fr_auto_72px] gap-3 items-baseline py-2 border-b border-border';
const SYM = 'text-[15px] font-bold';
const NAME = 'ml-1.5 text-[12px] text-muted-foreground';
const PRICE = 'text-[17px] font-semibold font-stretch-[85%]';

/** 涨跌幅列：没数就一个破折号，别画成 0% */
function Chg({ pct }: { pct: number | null }) {
  return (
    <span className={cn('text-[13px] font-semibold text-right', pct == null ? 'mute' : pct >= 0 ? 'up' : 'dn')}>
      {pct == null ? '—' : `${pct >= 0 ? '+' : ''}${pct.toFixed(2)}%`}
    </span>
  );
}

/** 分类栏头：分类名 + 副标题 + 右侧「全部」 */
function CatHead({ title, sub, to }: { title: string; sub: string; to: string }) {
  const { t } = useTranslation('common');
  return (
    <h3 className="flex items-baseline gap-2 m-0 pb-1.5 border-b border-border text-[15px] font-bold">
      {title}
      <span className="text-[12px] font-medium text-muted-foreground min-w-0 truncate">{sub}</span>
      <Link to={to} className="ml-auto shrink-0 text-[12px] font-medium underline underline-offset-[3px]">{t('all')}</Link>
    </h3>
  );
}

/** 币种/商品/TradFi 一行：整行可点直达交易页 */
function CoinRow({ symbol }: { symbol: string }) {
  const cfg = COIN_MAP[symbol];
  const q = useCoinQuote(symbol);
  return (
    <Link to={q.to} className={ROW}>
      <span className="min-w-0 truncate">
        <span className={SYM}>{cfg.name}</span>
        <span className={NAME}>{cfg.pair}</span>
      </span>
      <span className={PRICE}>{q.price == null ? '—' : formatCoinPrice(symbol, q.price)}</span>
      <Chg pct={q.pct} />
    </Link>
  );
}

/** 代币化美股一行：代号当主名，公司名作灰字 */
function StockRow({ stock }: { stock: BStock }) {
  const q = useStockQuote(stock);
  return (
    <Link to={q.to} className={ROW}>
      <span className="min-w-0 truncate">
        <span className={SYM}>{stock.ticker}</span>
        <span className={NAME}>{stock.name}</span>
      </span>
      <span className={PRICE}>
        {q.price == null ? '—' : q.price.toLocaleString('en-US', { maximumFractionDigits: 2 })}
      </span>
      <Chg pct={q.pct} />
    </Link>
  );
}

/**
 * 首页市场：股票 / Crypto / 大宗商品 / TradFi 四类各两只代表，点行直达交易页。
 * 大屏四列并排，中屏两列，手机单列。
 */
export function HomeMarketSection() {
  const { t } = useTranslation('home');
  const [topStocks, setTopStocks] = useState<BStock[]>([]);
  const gridRef = useStagger<HTMLDivElement>();

  // 市值前 2 作代表。只拉一次：这里要的是"哪两只 + 名称"这类静态元数据，
  // 价格和涨跌由 StockRow 自己走 Spot 流实时刷，不需要轮询
  useEffect(() => {
    bstockApi.list()
      .then(list => setTopStocks([...list].sort((a, b) => (b.marketCap ?? 0) - (a.marketCap ?? 0)).slice(0, 2)))
      .catch(() => {});
  }, []);

  return (
    <>
      <div className="sec-h">
        <h2>{t('market.title')}</h2>
        <span>{t('market.sub')}</span>
      </div>
      <div ref={gridRef} className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-8">
        <div>
          <CatHead title={t('market.stocks')} sub={t('market.stocksSub')} to="/bstock" />
          {topStocks.length
            ? topStocks.map(s => <StockRow key={s.symbol} stock={s} />)
            : Array.from({ length: 2 }).map((_, i) => <Skeleton key={i} className="h-[34px] mt-px" />)}
        </div>

        <div>
          <CatHead title={t('market.crypto')} sub={t('market.cryptoSub')} to="/coin" />
          <CoinRow symbol="BTCUSDT" />
          <CoinRow symbol="ETHUSDT" />
        </div>

        <div>
          <CatHead title={t('market.commodity')} sub={t('market.commoditySub')} to="/commodity" />
          {COMMODITY_LIST.map(c => <CoinRow key={c.symbol} symbol={c.symbol} />)}
        </div>

        <div>
          <CatHead title={t('market.tradfi')} sub={t('market.tradfiSub')} to="/tradfi" />
          {/* TradFi 代表：SpaceX（话题标的）+ SK海力士（成交最活跃） */}
          <CoinRow symbol="SPCXUSDT" />
          <CoinRow symbol="SKHYNIXUSDT" />
        </div>
      </div>
    </>
  );
}
