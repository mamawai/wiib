import axios from 'axios';
import type { Stock, User, Position, OrderRequest, Order, DayTick, Settlement, PageResult, News, RankingItem, OptionChainItem, OptionQuote, OptionPosition, OptionOrder, OptionOrderRequest, OptionOrderResult, BuffStatus, UserBuff } from '../types';

const api = axios.create({
  baseURL: '/api',
  withCredentials: true,
});

// 请求拦截器：添加Token到Header
api.interceptors.request.use((config) => {
  const stored = localStorage.getItem('wiib-user');
  if (stored) {
    try {
      const { state } = JSON.parse(stored);
      if (state?.token) {
        config.headers['satoken'] = state.token;
      }
    } catch { /* ignore */ }
  }
  return config;
});

// 响应拦截器：检查code并提取data字段
api.interceptors.response.use(
  (res) => {
    const { code, msg, data } = res.data;
    if (code !== 0) {
      return Promise.reject(new Error(msg || '请求失败'));
    }
    return data;
  },
  (err) => {
    const msg = err.response?.data?.msg || err.response?.data?.message || err.message;
    console.error('API错误:', msg);
    return Promise.reject(new Error(msg));
  }
);

// ========== 认证接口 ==========
export const authApi = {
  // LinuxDo OAuth回调
  linuxDoCallback: (code: string) => api.get<unknown, string>('/auth/callback/linuxdo', { params: { code } }),
  // 获取当前用户信息
  current: () => api.get<unknown, User>('/auth/current'),
  // 退出登录
  logout: () => api.post<unknown, void>('/auth/logout'),
};

// ========== 股票接口 ==========
export const stockApi = {
  // 获取所有股票列表
  list: () => api.get<unknown, Stock[]>('/stock/list'),
  // 分页查询股票列表
  page: (pageNum = 1, pageSize = 10) =>
    api.get<unknown, PageResult<Stock>>('/stock/page', { params: { pageNum, pageSize } }),
  // 获取股票详情
  detail: (id: number) => api.get<unknown, Stock>(`/stock/${id}`),
  // 获取涨幅榜
  gainers: (limit = 10) => api.get<unknown, Stock[]>('/stock/gainers', { params: { limit } }),
  // 获取跌幅榜
  losers: (limit = 10) => api.get<unknown, Stock[]>('/stock/losers', { params: { limit } }),
  // 获取当日分时数据
  ticks: (stockId: number) => api.get<unknown, DayTick[]>(`/stock/${stockId}/ticks`),
};

// ========== 订单接口 ==========
export const orderApi = {
  // 买入下单（市价/限价）
  buy: (data: OrderRequest) => api.post<unknown, Order>('/order/buy', data),
  // 卖出下单（市价/限价）
  sell: (data: OrderRequest) => api.post<unknown, Order>('/order/sell', data),
  // 取消订单
  cancel: (orderId: number) => api.post<unknown, Order>(`/order/cancel/${orderId}`),
  // 查询订单列表（分页）
  list: (status?: string, pageNum = 1, pageSize = 10) =>
    api.get<unknown, PageResult<Order>>('/order/list', { params: { status, pageNum, pageSize } }),
};

// ========== 用户接口 ==========
export const userApi = {
  // 获取用户资产概览
  portfolio: () => api.get<unknown, User>('/user/portfolio'),
  // 获取用户持仓列表
  positions: () => api.get<unknown, Position[]>('/user/positions'),
};

// ========== 结算接口 ==========
export const settlementApi = {
  // 获取待结算列表
  pending: () => api.get<unknown, Settlement[]>('/settlement/pending'),
};

// ========== 新闻接口 ==========
export const newsApi = {
  // 获取股票相关新闻（按日期）
  byStock: (stockCode: string, date?: string) =>
    api.get<unknown, News[]>(`/news/stock/${stockCode}`, { params: { date } }),
};

// ========== 排行榜接口 ==========
export const rankingApi = {
  // 获取排行榜
  list: () => api.get<unknown, RankingItem[]>('/ranking'),
};

// ========== 管理接口 ==========
export interface TaskStatus {
  marketDataTask: boolean;
  orderExecutionTask: boolean;
  settlementTask: boolean;
  rankingTask: boolean;
  isTradingTime: boolean;
  currentTickIndex: number;
}

export interface RefreshStockCacheResult {
  date: string;
  time: string;
  updated: number;
  skipped: number;
}

export const adminApi = {
  taskStatus: () => api.get<unknown, TaskStatus>('/admin/task/status'),
  startMarketPush: () => api.post<unknown, void>('/admin/task/market-push/start'),
  stopMarketPush: () => api.post<unknown, void>('/admin/task/market-push/stop'),
  startSettlement: () => api.post<unknown, void>('/admin/task/settlement/start'),
  stopSettlement: () => api.post<unknown, void>('/admin/task/settlement/stop'),
  expireOrders: () => api.post<unknown, void>('/admin/task/expire-orders'),
  generateData: () => api.post<unknown, void>('/admin/task/generate-data'),
  generateTodayData: () => api.post<unknown, void>('/admin/task/generate-today-data'),
  loadRedis: () => api.post<unknown, void>('/admin/task/load-redis'),
  refreshStockCache: () => api.post<unknown, RefreshStockCacheResult>('/admin/task/refresh-stock-cache'),
  bankruptcyCheck: () => api.post<unknown, void>('/admin/task/bankruptcy/check'),
  accrueInterest: () => api.post<unknown, void>('/admin/task/margin/accrue-interest'),
  getDailyInterestRate: () => api.get<unknown, number>('/admin/task/margin/daily-interest-rate'),
  setDailyInterestRate: (dailyInterestRate: number) =>
    api.post<unknown, number>('/admin/task/margin/daily-interest-rate', { dailyInterestRate }),
};

// ========== 期权接口 ==========
export const optionApi = {
  // 获取期权链
  chain: (stockId: number) => api.get<unknown, OptionChainItem[]>(`/option/chain/${stockId}`),
  // 获取期权报价
  quote: (contractId: number) => api.get<unknown, OptionQuote>(`/option/quote/${contractId}`),
  // 买入开仓
  buy: (data: OptionOrderRequest) => api.post<unknown, OptionOrderResult>('/option/buy', data),
  // 卖出平仓
  sell: (data: OptionOrderRequest) => api.post<unknown, OptionOrderResult>('/option/sell', data),
  // 获取持仓
  positions: () => api.get<unknown, OptionPosition[]>('/option/positions'),
  // 获取订单
  orders: (status?: string, pageNum = 1, pageSize = 10) =>
    api.get<unknown, PageResult<OptionOrder>>('/option/orders', { params: { status, pageNum, pageSize } }),
  // 手动触发到期期权结算
  processExpirySettlement: () => api.post<unknown, void>('/option/settlement/process'),
  // 生成期权链（管理接口）
  generateChain: (stockId: number, steps = 5) =>
    api.post<unknown, unknown>(`/option/generate-chain/${stockId}`, null, { params: { steps } }),
};

// ========== Buff接口 ==========
export const buffApi = {
  // 获取Buff状态
  status: () => api.get<unknown, BuffStatus>('/buff/status'),
  // 抽奖
  draw: () => api.post<unknown, UserBuff>('/buff/draw'),
};
