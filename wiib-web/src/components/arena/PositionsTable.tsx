import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { cn, fmtNum } from '../../lib/utils';
import type { FuturesOrder, FuturesPosition } from '../../types';

const TH = 'text-[12px] font-semibold mute text-left px-2 pb-2 border-b border-foreground first:pl-0 last:pr-0';
const TD = 'px-2 py-3 border-b border-border text-[14px] align-top first:pl-0 last:pr-0';

/** 格子里的副行：小一号灰字压在主值下面，一格塞两个数又不把表格撑高 */
function Sub({ children, tone }: { children: ReactNode; tone?: string }) {
  return <span className={cn('block text-[11.5px] mute mt-0.5', tone)}>{children}</span>;
}

/**
 * 持仓 / 挂单表。侧栏只有 2/5 宽，塞不下六列，所以压成四列＋副行：
 * 数量下面是开仓价、止损下面是止盈、浮盈下面是百分比。挂单接在持仓后面，整行浅底区分。
 */
export function PositionsTable({ positions, orders }: { positions: FuturesPosition[]; orders: FuturesOrder[] }) {
  const { t } = useTranslation('ai');
  return (
    <table className="num w-full border-collapse">
      <thead>
        <tr>
          <th className={TH}>{t('detail.colSymbol')}</th>
          <th className={cn(TH, 'text-right')}>{t('detail.qty')}</th>
          <th className={cn(TH, 'text-right')}>{t('detail.colSlTp')}</th>
          <th className={cn(TH, 'text-right')}>{t('detail.colUpnl')}</th>
        </tr>
      </thead>
      <tbody>
        {positions.map(p => {
          const isLong = p.side === 'LONG';
          const up = p.unrealizedPnl >= 0;
          return (
            <tr key={p.id}>
              <td className={TD}>
                <b className="font-bold">{p.symbol}</b>
                <Sub tone={isLong ? 'up' : 'dn'}>{t(isLong ? 'term.long' : 'term.short')} {p.leverage}x</Sub>
              </td>
              <td className={cn(TD, 'text-right whitespace-nowrap')}>
                {p.quantity}
                {/* 副行不带标签的话，这个数会被当成第二个数量 */}
                <Sub>{t('detail.entry')} {fmtNum(p.entryPrice)}</Sub>
              </td>
              {/* 一个仓可以挂多个止损/止盈，多个就用 / 连起来 */}
              <td className={cn(TD, 'text-right whitespace-nowrap')}>
                <span className="dn">{p.stopLosses?.length ? p.stopLosses.map(s => fmtNum(s.price)).join(' / ') : '—'}</span>
                <Sub tone="up">{p.takeProfits?.length ? p.takeProfits.map(x => fmtNum(x.price)).join(' / ') : '—'}</Sub>
              </td>
              <td className={cn(TD, 'text-right whitespace-nowrap')}>
                <b className={cn('font-bold', up ? 'up' : 'dn')}>{up ? '+' : ''}{fmtNum(p.unrealizedPnl)}</b>
                <Sub tone={up ? 'up' : 'dn'}>{up ? '+' : ''}{p.unrealizedPnlPct.toFixed(2)}%</Sub>
              </td>
            </tr>
          );
        })}
        {orders.map(o => {
          // 开/平 与 多/空 拼成一个词：中文能直接接起来，英文中间要空格，所以四种组合各一条词条
          const isLong = o.orderSide.includes('LONG');
          const sideKey = o.orderSide.startsWith('OPEN')
            ? (isLong ? 'detail.openLong' : 'detail.openShort')
            : (isLong ? 'detail.closeLong' : 'detail.closeShort');
          return (
            <tr key={o.orderId} className="bg-card-2 mute">
              <td className={TD}>
                <b className="font-bold">{o.symbol}</b>
                <Sub>{t('detail.limitOrder')}</Sub>
              </td>
              <td className={cn(TD, 'text-right whitespace-nowrap')}>
                {o.quantity}
                <Sub tone={isLong ? 'up' : 'dn'}>{t(sideKey)} {o.leverage}x</Sub>
              </td>
              {/* 挂单没有止损止盈和浮盈，两列并一格放限价 */}
              <td className={cn(TD, 'text-right whitespace-nowrap')} colSpan={2}>
                {o.limitPrice != null ? fmtNum(o.limitPrice) : '—'}
                <Sub>{t('detail.limitPrice')}</Sub>
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
