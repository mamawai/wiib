import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { useMemo } from 'react';
import { Layout } from './components/Layout';
import { Home } from './pages/Home';
import { StockList } from './pages/StockList';
import { StockDetail } from './pages/StockDetail';
import { StockKline } from './pages/StockKline';
import { Portfolio } from './pages/Portfolio';
import { Options } from './pages/Options';
import { Gold } from './pages/Gold';
import { Ranking } from './pages/Ranking';
import { Login } from './pages/Login';
import { Admin } from './pages/Admin';
import { useUserStore } from './stores/userStore';
import { useDedupedEffect } from './hooks/useDedupedEffect';

function App() {
  const { token, fetchUser } = useUserStore();
  const fetchKey = useMemo(() => (token ? `auth:current:${token}` : null), [token]);

  useDedupedEffect(
    fetchKey,
    () => {
      void fetchUser();
    },
    [fetchKey, fetchUser],
  );

  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route
          path="/*"
          element={
            <Layout>
              <Routes>
                <Route path="/" element={<Home />} />
                <Route path="/stocks" element={<StockList />} />
                <Route path="/stock/:id" element={<StockDetail />} />
                <Route path="/stock/:id/kline" element={<StockKline />} />
                <Route path="/portfolio" element={<Portfolio />} />
                <Route path="/options" element={<Options />} />
                <Route path="/gold" element={<Gold />} />
                <Route path="/ranking" element={<Ranking />} />
                <Route path="/admin" element={<Admin />} />
                <Route path="*" element={<Navigate to="/" replace />} />
              </Routes>
            </Layout>
          }
        />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
