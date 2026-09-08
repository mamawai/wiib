/** symbol → 新闻标签：BTCUSDT 映射 BTC，美股/商品代码在词表内的直接同名；词表外无标签=不挂新闻轨 */
export function newsTagForSymbol(symbol: string): string | undefined {
  if (symbol === 'BTCUSDT') return 'BTC';
  return ['OIL', 'GOLD', 'COIN', 'MSTR', 'TSLA', 'NVDA'].includes(symbol) ? symbol : undefined;
}
