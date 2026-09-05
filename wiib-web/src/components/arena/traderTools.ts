import i18n from '../../i18n';

/**
 * trader agent 每次唤醒拿到的全部工具（与后端 TraderWakeupRunner.wakeTools 同一份清单）：
 * 交易动作会动账本，行情数据只读。顺序即展示顺序——时间线的工具名与配置页的 skills 卡共用。
 */
export const TRADE_TOOLS = [
  'open_position', 'close_position', 'set_stop_loss', 'set_take_profit', 'write_plan', 'cancel_order', 'get_account',
] as const;
export const DATA_TOOLS = [
  'klines', 'kline_structure', 'indicators', 'market_snapshot', 'funding_history', 'orderbook_depth', 'option_iv', 'news_search',
] as const;

/** 工具 id → 词表 key（存 key 不存文案：模块级常量只算一次，切语言不会失效） */
const TOOL_KEY: Record<string, string> = {
  open_position: 'tool.openPosition', close_position: 'tool.closePosition', set_stop_loss: 'tool.setStopLoss',
  set_take_profit: 'tool.setTakeProfit', cancel_order: 'tool.cancelOrder', write_plan: 'tool.writePlan',
  get_account: 'tool.getAccount', klines: 'tool.klines', kline_structure: 'tool.klineStructure',
  indicators: 'tool.indicators', market_snapshot: 'tool.marketSnapshot', funding_history: 'tool.fundingHistory',
  orderbook_depth: 'tool.orderbookDepth', option_iv: 'tool.optionIv', news_search: 'tool.newsSearch',
};

/** 工具的展示名；表里没有的（后端加了新工具）原样显示 id */
export function toolName(tool: string): string {
  const key = TOOL_KEY[tool];
  return key ? i18n.t(`ai:${key}`) : tool;
}

/** 会动账本的那几个：时间线上独立成行展示参数与拒因，其余数据查询弱化成一句"看了什么" */
export const TRADE_TOOL_SET: ReadonlySet<string> = new Set(TRADE_TOOLS);

/** actions_json 里的一条工具动作 */
export interface ActionRow {
  tool: string;
  status?: string;
  rejected?: string;
  error?: string;
  args?: Record<string, unknown>;
}

/** 交易动作的关键参数一行话（按工具挑重点，不倒整个 JSON）。词表在函数体里现查，切语言即变 */
export function tradeArgsSummary(a: ActionRow): string {
  const g = (k: string) => a.args?.[k] != null ? String(a.args[k]) : '';
  const tr = (key: string, vars?: Record<string, string>) => i18n.t(`ai:${key}`, vars ?? {});
  switch (a.tool) {
    case 'open_position': {
      const parts = [g('symbol'), g('side') === 'LONG' ? tr('args.long') : g('side') === 'SHORT' ? tr('args.short') : g('side'),
        g('quantity') && tr('args.qty', { n: g('quantity') }), g('leverage') && `${g('leverage')}x`,
        g('stopLossPrice') && tr('args.sl', { p: g('stopLossPrice') }),
        g('takeProfitPrice') && tr('args.tp', { p: g('takeProfitPrice') }),
        g('playType')];
      return parts.filter(Boolean).join(' · ');
    }
    case 'close_position':
      return [tr('args.position', { id: g('positionId') }), g('quantity') && tr('args.qty', { n: g('quantity') }), g('reason')]
        .filter(Boolean).join(' · ');
    case 'set_stop_loss':
      return [`→${g('stopLossPrice')}`, g('reason')].filter(Boolean).join(' · ');
    case 'set_take_profit':
      return [`→${g('takeProfitPrice')}`, g('reason')].filter(Boolean).join(' · ');
    case 'write_plan':
      return [g('playType'), g('invalidationCondition') && tr('args.invalidation', { c: g('invalidationCondition') })]
        .filter(Boolean).join(' · ');
    case 'cancel_order':
      return tr('args.order', { id: g('orderId') });
    default:
      return '';
  }
}
