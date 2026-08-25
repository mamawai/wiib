import i18n from '../i18n';
import { COIN_MAP } from './coinConfig';

/**
 * 成交方向的词表 key 与涨跌配色。首页「最新成交」、全站成交页、仓位历史、用户详情共用一份，
 * 各写一份迟早对不上。
 * <p>表里存 key 不存文案：直接存 i18n.t(...) 的结果会在模块加载那一刻定死，切语言不跟着变。
 */
const FUTURES_SIDE: Record<string, { labelKey: string; tone: 'buy' | 'sell' }> = {
  OPEN_LONG: { labelKey: 'labels:orderSide.openLong', tone: 'buy' },
  OPEN_SHORT: { labelKey: 'labels:orderSide.openShort', tone: 'sell' },
  CLOSE_LONG: { labelKey: 'labels:orderSide.closeLong', tone: 'sell' },
  CLOSE_SHORT: { labelKey: 'labels:orderSide.closeShort', tone: 'buy' },
  // 加仓：后端 order_side 注释里列了这两个值，漏掉会退化成显示原始英文
  INCREASE_LONG: { labelKey: 'labels:orderSide.increaseLong', tone: 'buy' },
  INCREASE_SHORT: { labelKey: 'labels:orderSide.increaseShort', tone: 'sell' },
};

export function orderSideView(orderSide: string): { label: string; tone: 'buy' | 'sell' } {
  const futures = FUTURES_SIDE[orderSide];
  if (futures) return { label: i18n.t(futures.labelKey), tone: futures.tone };
  // 现货只有 BUY/SELL；认不出的方向按卖处理并原样显示，别悄悄画成买
  if (orderSide === 'BUY') return { label: i18n.t('labels:orderSide.buy'), tone: 'buy' };
  if (orderSide === 'SELL') return { label: i18n.t('labels:orderSide.sell'), tone: 'sell' };
  return { label: orderSide, tone: 'sell' };
}

/**
 * 成交记录点进去该去哪个页面。
 * <p>
 * crypto_order 这张表里混着 crypto/大宗/TradFi 和 bStock 代币化美股（共用现货引擎），
 * 靠 COIN_MAP 里有没有区分：有 → 币种交易页，没有 → bStock 详情页。
 * 不能拿 getCoin() 判——它对认不出的 symbol 会兜底成 BTC，bStock 会被画成比特币。
 */
export function tradeHref(symbol: string): string {
  return COIN_MAP[symbol] ? `/coin/${symbol}` : `/bstock/${symbol}`;
}

/** 展示名：COIN_MAP 里有就用配置名，否则退回去掉 USDT 的原始符号（bStock 走这支） */
export function tradeSymbolName(symbol: string): string {
  return COIN_MAP[symbol]?.name ?? symbol.replace('USDT', '');
}
