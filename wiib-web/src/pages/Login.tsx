import { useCallback, useEffect, useState, useRef } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { authApi } from '../api';
import { useUserStore } from '../stores/userStore';
import { Button } from '../components/ui/button';
import { Card, CardContent } from '../components/ui/card';
import { Typewriter } from '../components/ui/typewriter';
import { TrendingUp, Loader2, Globe, BarChart3, Wallet, LineChart } from 'lucide-react';

const LINUXDO_CONFIG = {
  clientId: 'toCFytIO9bCHpbUbFKM1mTgvy1ax8tG2',
  authorizeUrl: 'https://connect.linux.do/oauth2/authorize',
  redirectUri: 'https://linuxdo.stockgame.icu/login',
};
// const LINUXDO_CONFIG = {
//   clientId: 'NIrMpQ09Jgzjb7r1ZgU3QYnuejk8Z3qS',
//   authorizeUrl: 'https://connect.linux.do/oauth2/authorize',
//   redirectUri: 'http://localhost:3000/login',
// };

export function Login() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { user, setToken, fetchUser } = useUserStore();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const callbackHandled = useRef(false);

  const handleOAuthCallback = useCallback(async (code: string, state: string) => {
    const savedState = localStorage.getItem('oauth_state');
    if (state !== savedState) {
      setError('安全验证失败，请重试');
      return;
    }
    localStorage.removeItem('oauth_state');

    setLoading(true);
    setError('');

    try {
      const token = await authApi.linuxDoCallback(code);
      if (token) {
        setToken(token);
        await fetchUser();
        navigate('/');
      }
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'LinuxDo 登录失败';
      setError(msg);
    } finally {
      setLoading(false);
    }
  }, [fetchUser, navigate, setToken]);

  useEffect(() => {
    if (user) {
      navigate('/');
    }
  }, [user, navigate]);

  useEffect(() => {
    const code = searchParams.get('code');
    const state = searchParams.get('state');
    if (code && state && !callbackHandled.current) {
      callbackHandled.current = true;
      handleOAuthCallback(code, state);
    }
  }, [searchParams, handleOAuthCallback]);

  const handleLinuxDoLogin = () => {
    const state = Math.random().toString(36).substring(2, 10);
    localStorage.setItem('oauth_state', state);
    window.location.href = `${LINUXDO_CONFIG.authorizeUrl}?client_id=${LINUXDO_CONFIG.clientId}&redirect_uri=${encodeURIComponent(LINUXDO_CONFIG.redirectUri)}&response_type=code&state=${state}`;
  };

  return (
    <div className="min-h-screen flex items-center justify-center p-4 relative overflow-hidden">
      {/* Static Background */}
      <div className="absolute inset-0 bg-[#0a0a0f]">
        {/* Grid pattern */}
        <div
          className="absolute inset-0 opacity-20"
          style={{
            backgroundImage: `linear-gradient(rgba(59, 130, 246, 0.4) 1px, transparent 1px), linear-gradient(90deg, rgba(59, 130, 246, 0.4) 1px, transparent 1px)`,
            backgroundSize: '40px 40px',
          }}
        />
        {/* Gradient overlay */}
        <div className="absolute inset-0 bg-gradient-to-br from-primary/10 via-transparent to-purple-500/10" />
      </div>

      <Card className="w-full max-w-md relative z-10 border-white/10 bg-black/40 backdrop-blur-xl shadow-2xl">
        <CardContent className="p-8">
          {/* Logo */}
          <div className="flex justify-center mb-8">
            <div className="relative">
              <div className="absolute inset-0 bg-primary/50 blur-xl rounded-full" />
              <div className="relative w-20 h-20 rounded-2xl bg-gradient-to-br from-primary via-blue-500 to-purple-600 flex items-center justify-center shadow-lg">
                <TrendingUp className="w-10 h-10 text-white" />
              </div>
            </div>
          </div>

          {/* Title with Typewriter */}
          <div className="text-center mb-8">
            <h1 className="text-3xl font-bold bg-gradient-to-r from-white via-blue-100 to-white bg-clip-text text-transparent mb-4">
              What If I Bought
            </h1>
            <p className="text-muted-foreground h-12">
              <Typewriter text="模拟股票交易，看看如果当初买了会怎样" speed={100} />
            </p>
          </div>

          {/* Features */}
          <div className="grid grid-cols-3 gap-4 mb-8">
            <div className="text-center p-3 rounded-xl bg-white/5 border border-white/10">
              <BarChart3 className="w-6 h-6 mx-auto mb-2 text-primary" />
              <span className="text-xs text-muted-foreground">实时行情</span>
            </div>
            <div className="text-center p-3 rounded-xl bg-white/5 border border-white/10">
              <Wallet className="w-6 h-6 mx-auto mb-2 text-green-500" />
              <span className="text-xs text-muted-foreground">模拟交易</span>
            </div>
            <div className="text-center p-3 rounded-xl bg-white/5 border border-white/10">
              <LineChart className="w-6 h-6 mx-auto mb-2 text-purple-500" />
              <span className="text-xs text-muted-foreground">收益追踪</span>
            </div>
          </div>

          {/* Error */}
          {error && (
            <div className="p-3 rounded-lg bg-red-500/10 border border-red-500/20 text-red-400 text-sm text-center mb-4 animate-in fade-in slide-in-from-top-2">
              {error}
            </div>
          )}

          {/* Login Button */}
          {loading ? (
            <div className="flex flex-col items-center gap-4 py-6">
              <div className="relative">
                <div className="absolute inset-0 bg-primary/30 blur-xl rounded-full animate-pulse" />
                <Loader2 className="w-10 h-10 text-primary animate-spin relative" />
              </div>
              <p className="text-sm text-muted-foreground">正在登录...</p>
            </div>
          ) : (
            <Button
              onClick={handleLinuxDoLogin}
              className="w-full h-14 text-base font-medium bg-gradient-to-r from-amber-500 via-orange-500 to-amber-600 hover:from-amber-400 hover:via-orange-400 hover:to-amber-500 text-white shadow-lg shadow-orange-500/30 transition-all duration-300 hover:shadow-orange-500/50 hover:-translate-y-1 hover:scale-[1.02] border-0"
            >
              <Globe className="w-5 h-5 mr-2" />
              使用 LinuxDo 登录
            </Button>
          )}

          <p className="text-xs text-center text-muted-foreground/60 mt-6">
            登录即表示同意我们的服务条款
          </p>
        </CardContent>
      </Card>
    </div>
  );
}
