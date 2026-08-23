import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { useMemo, type ReactNode, useEffect } from 'react';
import { Layout } from './components/Layout';
import { LanguageGate } from './components/LanguageGate';
import { Home } from './pages/Home';
import { BStockList } from './pages/BStockList';
import { BStockRoute } from './pages/BStockDetail';
import { Portfolio } from './pages/Portfolio';
import { PositionHistory } from './pages/PositionHistory';
import { Ledger } from './pages/Ledger';
import { Trades } from './pages/Trades';
import { UserProfile } from './pages/UserProfile';
import { CoinRoute } from './pages/Coin';
import { CoinSelect } from './pages/CoinSelect';
import { CommoditySelect } from './pages/CommoditySelect';
import { TradFiSelect } from './pages/TradFiSelect';
import { Ranking } from './pages/Ranking';
import { Comments } from './pages/Comments';
import { Campaign } from './pages/Campaign';
import { Login } from './pages/Login';
import { Admin } from './pages/Admin';
import { Blackjack } from './pages/Blackjack';
import { Mines } from './pages/Mines';
import { VideoPoker } from './pages/VideoPoker';
import { Games } from './pages/Games';
import { Intro } from './pages/Intro';
import { Me } from './pages/Me';
import { Prediction } from './pages/Prediction';
import { AiAgent } from './pages/AiAgent';
import { Arena } from './pages/Arena';
import { ArenaDetail } from './pages/ArenaDetail';
import { MyTrader } from './pages/MyTrader';
import { Strategies } from './pages/Strategies';
import { Backtest } from './pages/Backtest';
import { TestnetMonitor } from './pages/TestnetMonitor';
import { ForceOrders } from './pages/ForceOrders';
import { useUserStore } from './stores/userStore';

declare global {
  interface Window {
    /** 开屏动画的收尾钩子，定义在 index.html 内联脚本里（那边还有 6s 兜底，漏调不会卡死） */
    __wiibSplashDone?: () => void;
  }
}

/**
 * 全站唯一登录守卫，挂在 /* 上。
 * 判 token 不判 user，这么写为了刷新不闪登录页（token 同步恢复、user 异步）；
 * 页面内要用 user 的自己判 null 等它到（见 Portfolio）
 */
function RequireAuth({ children }: { children: ReactNode }) {
  const token = useUserStore(s => s.token);
  if (!token) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

function App() {
  const { token, fetchUser } = useUserStore();
  const fetchKey = useMemo(() => (token ? `auth:current:${token}` : null), [token]);

  // 开屏一直遮到用户信息就位，顺带把顶栏"登录→用户名"那一下闪烁盖掉。
  // 游客没 token 不发请求，直接放行，否则开屏要一路等到 6s 兜底
  useEffect(() => {
      if (fetchKey == null) { window.__wiibSplashDone?.(); return; }
      void fetchUser().finally(() => window.__wiibSplashDone?.());
    }, [fetchKey, fetchUser]);

  return (
    <BrowserRouter>
      {/* 挂在路由外层而不是 Layout 里：/login 在 Layout 之外，挂 Layout 游客就看不到 */}
      <LanguageGate />
      <Routes>
        <Route path="/login" element={<Login />} />
        {/* 全站唯一免登录页。其余页面进来都要发 API，游客第一个 401 就被响应拦截器弹去 /login，
            与其让人卡在半路被莫名弹走，不如在路由这层一次挡干净 */}
        <Route path="/intro" element={<Layout><Intro /></Layout>} />
        <Route
          path="/*"
          element={
            <RequireAuth>
              <Layout>
                <Routes>
                  <Route path="/" element={<Home />} />
                  <Route path="/bstock" element={<BStockList />} />
                  <Route path="/bstock/:symbol" element={<BStockRoute />} />
                  <Route path="/portfolio" element={<Portfolio />} />
                  <Route path="/portfolio/history" element={<PositionHistory />} />
                  <Route path="/ledger" element={<Ledger />} />
                  <Route path="/trades" element={<Trades />} />
                  <Route path="/coin" element={<CoinSelect />} />
                  <Route path="/coin/:symbol" element={<CoinRoute />} />
                  <Route path="/commodity" element={<CommoditySelect />} />
                  <Route path="/tradfi" element={<TradFiSelect />} />
                  <Route path="/ranking" element={<Ranking />} />
                  <Route path="/user/:id" element={<UserProfile />} />
                  <Route path="/comments" element={<Comments />} />
                  <Route path="/campaign" element={<Campaign />} />
                  <Route path="/admin" element={<Admin />} />
                  <Route path="/games" element={<Games />} />
                  <Route path="/me" element={<Me />} />
                  <Route path="/blackjack" element={<Blackjack />} />
                  <Route path="/mines" element={<Mines />} />
                  <Route path="/videopoker" element={<VideoPoker />} />
                  <Route path="/prediction" element={<Prediction />} />
                  <Route path="/ai" element={<AiAgent />} />
                  <Route path="/arena" element={<Arena />} />
                  <Route path="/arena/:id" element={<ArenaDetail />} />
                  <Route path="/my-trader" element={<MyTrader />} />
                  <Route path="/strategies" element={<Strategies />} />
                  <Route path="/backtest" element={<Backtest />} />
                  <Route path="/testnet" element={<TestnetMonitor />} />
                  <Route path="/force-orders" element={<ForceOrders />} />
                  <Route path="*" element={<Navigate to="/" replace />} />
                </Routes>
              </Layout>
            </RequireAuth>
          }
        />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
