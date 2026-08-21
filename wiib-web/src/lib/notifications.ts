import i18n from '../i18n';
import type { NotificationItem, TradeNotifType } from '../types';

/** 评论类：多人赞同一条会合并成一组 */
export interface CommentNotification {
  kind: 'comment';
  key: string;
  type: 1 | 2;
  commentId: number;
  actors: string[];
  latestAt: string;
  unread: boolean;
}

/** 交易类：每条都是独立事件，不合并 */
export interface TradeNotification {
  kind: 'trade';
  key: string;
  type: TradeNotifType;
  symbol: string | null;
  side: 'LONG' | 'SHORT' | null;
  quantity: number | null;
  price: number | null;
  pnl: number | null;
  latestAt: string;
  unread: boolean;
}

export type MergedNotification = CommentNotification | TradeNotification;

const isTrade = (type: number): type is TradeNotifType => type >= 3;

/**
 * 分组规则按类型分叉：
 * - 评论类用 {@code type:commentId}。赞的 commentId 都指向"我那条被赞的评论"，多人赞自动合并成一组；
 *   回复的 commentId 是"对方那条回复"各不相同，天然落成独立组（回复本来就不该合并——
 *   每条内容不同、点击要跳到不同位置）。
 * - 交易类用 {@code type:id}，即每条自成一组。两次强平就是两件事，合并了会丢掉各自的价格和盈亏；
 *   而且交易通知的 commentId 恒为 null，按老规则会把所有同类型的挤成一组。
 */
export function mergeNotifications(list: NotificationItem[]): MergedNotification[] {
  const groups = new Map<string, MergedNotification>();

  for (const n of list) {
    if (isTrade(n.type)) {
      groups.set(`${n.type}:${n.id}`, {
        kind: 'trade',
        key: `${n.type}:${n.id}`,
        type: n.type,
        symbol: n.symbol,
        side: n.side,
        quantity: n.quantity,
        price: n.price,
        pnl: n.pnl,
        latestAt: n.createdAt,
        unread: !n.isRead,
      });
      continue;
    }

    const key = `${n.type}:${n.commentId}`;
    const g = groups.get(key);
    if (g && g.kind === 'comment') {
      if (n.actorName && !g.actors.includes(n.actorName)) g.actors.push(n.actorName);
      if (!n.isRead) g.unread = true;
    } else {
      groups.set(key, {
        kind: 'comment',
        key,
        type: n.type,
        commentId: n.commentId ?? 0,
        actors: n.actorName ? [n.actorName] : [],
        latestAt: n.createdAt,
        unread: !n.isRead,
      });
    }
  }

  return [...groups.values()].sort((a, b) => b.latestAt.localeCompare(a.latestAt));
}

/**
 * 按人数挑一条整句。不能拿"名字 + 动词"拼：英文动词夹在名字后面（A liked your comment），
 * 中文动词也黏在人数后面，拼出来的必是病句，所以四种人数各进一条完整句子。
 */
function describeActors(
  g: CommentNotification,
  k: { anon: string; one: string; two: string; more: string },
): string {
  const [a, b] = g.actors;
  if (g.actors.length === 0) return i18n.t(k.anon);
  if (g.actors.length === 1) return i18n.t(k.one, { a });
  if (g.actors.length === 2) return i18n.t(k.two, { a, b });
  // count 传"除头两个之外还剩几人"：英文 N other/others 要按这个数变单复数，中文照样念得通
  return i18n.t(k.more, { a, b, count: g.actors.length - 2 });
}

/** 评论类文案：一行说清谁做了什么 */
export function describeComment(g: CommentNotification): string {
  return g.type === 1
    ? describeActors(g, {
        anon: 'account:notif.like.anon', one: 'account:notif.like.one',
        two: 'account:notif.like.two', more: 'account:notif.like.more',
      })
    : describeActors(g, {
        anon: 'account:notif.reply.anon', one: 'account:notif.reply.one',
        two: 'account:notif.reply.two', more: 'account:notif.reply.more',
      });
}

/**
 * 方向与事件的词表 key。存 key 不存文案：这是模块级常量，
 * 直接存 i18n.t(...) 的结果会在模块加载那一刻定死，切语言不跟着变。
 */
const SIDE_KEY: Record<'LONG' | 'SHORT', string> = {
  LONG: 'account:notif.side.long',
  SHORT: 'account:notif.side.short',
};
const TRADE_KEY: Record<Exclude<TradeNotifType, 6>, string> = {
  3: 'account:notif.trade.liquidation',
  4: 'account:notif.trade.stopLoss',
  5: 'account:notif.trade.takeProfit',
};

/** 交易类标题：`BTCUSDT 多单强平` / `全仓爆仓 · 3 个仓位` */
export function describeTrade(g: TradeNotification): string {
  if (g.type === 6) {
    // quantity 在全仓爆仓下是"爆掉的仓位数"，不是币的数量
    const count = g.quantity == null ? null : Math.round(g.quantity);
    return count
      ? i18n.t('account:notif.trade.crossCount', { count })
      : i18n.t('account:notif.trade.cross');
  }
  const side = g.side ? i18n.t(SIDE_KEY[g.side]) : '';
  // symbol/side 缺一个就会在句子里留个空档，统一压掉，免得出现双空格或行首空格
  return i18n.t(TRADE_KEY[g.type], { symbol: g.symbol ?? '', side }).replace(/\s{2,}/g, ' ').trim();
}
