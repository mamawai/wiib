import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Skeleton } from './ui/skeleton';
import { FEED_MAX_H } from './NewsFlashCard';
import { useStagger } from '../hooks/useStagger';
import { fmtNum, fmtTime } from '../lib/utils';
import { orderSideView } from '../lib/orderSide';

/**
 * 一条成交要的最少信息：只装后端原始值，方向标签和展示名一律留到渲染期算。
 * 存译好的字会把文案冻在拉数那一刻，之后切语言这批卡片就不跟着变了。
 */
export interface TradeItem {
  id: string;
  /** 后端原始方向枚举（BUY/SELL/OPEN_LONG/...），渲染时交给 orderSideView 现算标签与配色 */
  orderSide: string;
  /** 去掉 USDT 的标的符号，既当展示名也当数量单位 */
  base: string;
  /** 合约单：展示名要带「合约」后缀，现货不带 */
  isFutures?: boolean;
  quantity: number | string;
  filledAmount?: number;
  createdAt: string;
  isAi?: boolean;
}

interface Props {
  trades: TradeItem[];
  loading: boolean;
}

export function LatestTradesCard({ trades, loading }: Props) {
  // 「全部」全站通用，直接复用 common，不在本 ns 再写一份
  const { t } = useTranslation(['home', 'common']);
  const listRef = useStagger<HTMLDivElement>();

  return (
    <>
      {/* min-h-7 对齐快讯卡头部（那边有 28px 高的日期控件），两卡底边才齐 */}
      <div className="sec-h min-h-7">
        <h2>{t('trades.title')}</h2>
        {/* 这里只给最新 20 条，全量分页在 /trades（交易者匿名） */}
        <Link to="/trades">{t('common:all')}</Link>
      </div>
      {loading ? (
        <div>
          {Array.from({ length: 5 }).map((_, i) => <Skeleton key={i} className="h-[22px] my-[11px]" />)}
        </div>
      ) : trades.length === 0 ? (
        <div className="py-10 text-center text-sm text-muted-foreground">{t('trades.empty')}</div>
      ) : (
        <div ref={listRef} className={FEED_MAX_H}>
          {trades.map(item => {
            // 方向标签渲染期现算：本组件订阅了 useTranslation，切语言会重渲染，这里就跟着重查一遍词表
            const { label, tone } = orderSideView(item.orderSide);
            const name = item.isFutures ? t('trades.futuresName', { base: item.base }) : item.base;
            return (
              <div key={item.id} className="num grid grid-cols-[40px_1fr_auto] gap-3 items-baseline py-[11px] border-b border-border text-[15px]">
                <span className={`text-[13px] font-bold ${tone === 'buy' ? 'up' : 'dn'}`}>{label}</span>
                <span className="min-w-0 truncate">
                  <span className="font-semibold">{name}</span>
                  {item.isAi && (
                    <span title={t('trades.aiTrader')} className="inline-block text-[10px] font-bold border border-foreground px-1 ml-1.5 align-[1px]">AI</span>
                  )}
                </span>
                <span className="whitespace-nowrap">
                  <span className="text-[16px] font-semibold font-stretch-[85%]">
                    {item.filledAmount == null ? `${item.quantity}${item.base}` : `$${fmtNum(item.filledAmount, 1)}`}
                  </span>
                  <span className="text-[12px] text-muted-foreground ml-3">{fmtTime(item.createdAt)}</span>
                </span>
              </div>
            );
          })}
        </div>
      )}
    </>
  );
}
