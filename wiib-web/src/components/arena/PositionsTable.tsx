import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { ArrowDownRight, ArrowUpRight, Clock } from 'lucide-react';
import { cn, fmtNum } from '../../lib/utils';
import type { FuturesOrder, FuturesPosition } from '../../types';

/** 格子里的副行：小一号灰字压在主值下面，一格塞两个数又不把表格撑高 */
function Sub({ children, tone }: { children: ReactNode; tone?: string }) {
  return <span className={cn('block text-[10px] leading-tight text-muted-foreground', tone)}>{children}</span>;
}

/**
 * 持仓 / 挂单表。侧栏只有 2/5 宽，塞不下六列，所以压成四列＋副行：
 * 数量下面是开仓价、止损下面是止盈、浮盈下面是百分比。挂单接在持仓后面，整行浅底区分。
 */
export function PositionsTable({ positions, orders }: { positions: FuturesPosition[]; orders: FuturesOrder[] }) {
  const { t } = useTranslation('ai');
  return (
    <table className="w-full text-xs">
      <thead>
        <tr className="text-left text-[10px] uppercase text-muted-foreground border-b border-border">
          <th className="py-2 pr-2 font-medium">{t('detail.colSymbol')}</th>
          <th className="py-2 px-2 font-medium text-right">{t('detail.qty')}</th>
          <th className="py-2 px-2 font-medium text-right">{t('detail.colSlTp')}</th>
          <th className="py-2 pl-2 font-medium text-right">{t('detail.colUpnl')}</th>
        </tr>
      </thead>
      <tbody>
        {positions.map(p => {
          const isLong = p.side === 'LONG';
          const up = p.unrealizedPnl >= 0;
          return (
            <tr key={p.id} className="border-b border-border/60 last:border-0">
              <td className="py-2 pr-2">
                <span className="font-black inline-flex items-center gap-1">
                  {isLong ? <ArrowUpRight className="w-3.5 h-3.5 text-gain" /> : <ArrowDownRight className="w-3.5 h-3.5 text-loss" />}
                  {p.symbol}
                </span>
                <Sub tone={isLong ? 'text-gain' : 'text-loss'}>
                  {isLong ? t('term.long') : t('term.short')} {p.leverage}x
                </Sub>
              </td>
              <td className="py-2 px-2 text-right num whitespace-nowrap">
                {p.quantity}
                {/* 副行不带标签的话，这个数会被当成第二个数量 */}
                <Sub>{t('detail.entry')} {fmtNum(p.entryPrice)}</Sub>
              </td>
              {/* 一个仓可以挂多个止损/止盈，多个就用 / 连起来 */}
              <td className="py-2 px-2 text-right num whitespace-nowrap">
                <span className="text-loss">{p.stopLosses?.length ? p.stopLosses.map(s => fmtNum(s.price)).join(' / ') : '—'}</span>
                <Sub tone="text-gain">{p.takeProfits?.length ? p.takeProfits.map(x => fmtNum(x.price)).join(' / ') : '—'}</Sub>
              </td>
              <td className="py-2 pl-2 text-right num whitespace-nowrap">
                <b className={cn('font-black', up ? 'text-gain' : 'text-loss')}>
                  {up ? '+' : ''}{fmtNum(p.unrealizedPnl)}
                </b>
                <Sub tone={up ? 'text-gain' : 'text-loss'}>
                  {up ? '+' : ''}{p.unrealizedPnlPct.toFixed(2)}%
                </Sub>
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
            <tr key={o.orderId} className="border-b border-border/60 last:border-0 bg-card-2">
              <td className="py-2 pr-2">
                <span className="font-black inline-flex items-center gap-1 text-muted-foreground">
                  <Clock className="w-3.5 h-3.5" />
                  {o.symbol}
                </span>
                <Sub>{t('detail.limitOrder')}</Sub>
              </td>
              <td className="py-2 px-2 text-right num text-muted-foreground whitespace-nowrap">
                {o.quantity}
                <Sub tone={isLong ? 'text-gain' : 'text-loss'}>{t(sideKey)} {o.leverage}x</Sub>
              </td>
              {/* 挂单没有止损止盈和浮盈，两列并一格放限价 */}
              <td className="py-2 pl-2 text-right num text-muted-foreground whitespace-nowrap" colSpan={2}>
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
