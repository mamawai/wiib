import { useCallback, useEffect, useState, useRef } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { authApi } from '../api';
import { useUserStore } from '../stores/userStore';
import { Typewriter } from '../components/ui/typewriter';
import { Loader2, Globe, BarChart3, Wallet, LineChart } from 'lucide-react';

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

// 定制化新粗野主义商标组件：时光机+涨幅趋势
function ProjectLogo({ className, ...props }: React.SVGProps<SVGSVGElement>) {
  return (
    <svg viewBox="0 0 100 100" fill="none" xmlns="http://www.w3.org/2000/svg" className={className} {...props}>
      {/* 底部偏移阴影（时钟的背影） */}
      <circle cx="50" cy="58" r="32" fill="currentColor" opacity="0.15" />
      {/* 时钟表盘 */}
      <circle cx="46" cy="54" r="32" fill="#FFFFFF" stroke="currentColor" strokeWidth="7" />
      
      {/* 时钟刻度 */}
      <line x1="46" y1="30" x2="46" y2="36" stroke="currentColor" strokeWidth="5" strokeLinecap="round" />
      <line x1="70" y1="54" x2="64" y2="54" stroke="currentColor" strokeWidth="5" strokeLinecap="round" />
      <line x1="46" y1="78" x2="46" y2="72" stroke="currentColor" strokeWidth="5" strokeLinecap="round" />
      <line x1="22" y1="54" x2="28" y2="54" stroke="currentColor" strokeWidth="5" strokeLinecap="round" />
      
      {/* 时钟中心点 */}
      <circle cx="46" cy="54" r="6" fill="currentColor" />
      
      {/* 突破时间限制的增长箭头 */}
      <polyline points="32,68 46,54 74,26" fill="none" stroke="currentColor" strokeWidth="7" strokeLinecap="square" strokeLinejoin="miter" />
      <polygon points="61,23 81,19 77,39" fill="currentColor" stroke="currentColor" strokeWidth="2" strokeLinejoin="round" />
    </svg>
  );
}

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
    <div className="min-h-screen flex items-center justify-center p-4 relative overflow-hidden bg-background">
      {/* 恢复原版的渐变垂直条纹背景 */}
      <div className="absolute inset-0 z-0 pointer-events-none">
        <div className="absolute inset-0 flex">
          {Array.from({ length: 26 }).map((_, i) => (
            <div
              key={i}
              className="flex-1"
              style={{
                background: `linear-gradient(to bottom,
                  rgba(249, 115, 22, 0) 0%,
                  rgba(253, 186, 116, ${Math.sin((i / 26) * Math.PI) * 0.35 + 0.25}) 50%,
                  rgba(249, 115, 22, 0) 100%)`,
              }}
            />
          ))}
        </div>
      </div>

      {/* 主卡片 */}
      <div className="w-full max-w-md relative z-10">
        {/* 卡片后置阴影块（纯物理错位感） */}
        <div className="absolute inset-0 bg-edge rounded-4xl translate-x-3 translate-y-3" />
        
        <div className="relative rounded-4xl border-[3.5px] border-edge bg-card p-8 md:p-10 flex flex-col items-center">
          
          {/* Logo - 倾斜重叠设计 + 专属商标 */}
          <div className="mb-8 relative group cursor-pointer">
            <div className="absolute inset-0 bg-warning rounded-2xl border-[3.5px] border-edge rotate-6 transition-transform duration-300 group-hover:rotate-12" />
            <div className="relative w-24 h-24 rounded-2xl bg-primary border-[3.5px] border-edge flex items-center justify-center shadow-[4px_4px_0_0_var(--color-edge)] -rotate-6 transition-transform duration-300 group-hover:rotate-0">
              <ProjectLogo className="w-16 h-16 text-edge" />
            </div>
          </div>

          {/* 标题 */}
          <div className="text-center mb-10 w-full flex flex-col items-center">
            <h1 className="text-3xl sm:text-4xl md:text-[2.5rem] font-black text-edge mb-6 tracking-tighter uppercase flex flex-wrap items-center justify-center gap-x-3 gap-y-4">
              <span className="relative inline-block z-10" style={{ textShadow: '4px 4px 0 var(--color-warning)' }}>
                What If
              </span>
              <span className="px-4 py-1 bg-primary text-primary-foreground border-[3.5px] border-edge shadow-[5px_5px_0_0_var(--color-edge)] -rotate-2 inline-block relative z-20 hover:rotate-0 hover:translate-y-1 transition-all">
                I Bought
              </span>
            </h1>
            <div className="inline-flex items-center justify-center px-4 py-2.5 rounded-xl border-[2.5px] border-edge bg-surface shadow-[3px_3px_0_0_var(--color-edge)]">
              <span className="text-sm font-bold text-foreground">
                <Typewriter text="模拟股票交易，看看如果当初买了会怎样" speed={80} />
              </span>
            </div>
          </div>

          {/* 功能点矩阵 */}
          <div className="grid grid-cols-3 gap-3 w-full mb-10">
            <div className="text-center p-4 rounded-xl border-[3px] border-edge bg-[#FEF08A] shadow-[3px_3px_0_0_var(--color-edge)] hover:-translate-y-1 hover:-translate-x-px hover:shadow-[5px_5px_0_0_var(--color-edge)] transition-all">
              <BarChart3 className="w-7 h-7 mx-auto mb-2 text-edge" strokeWidth={2.5} />
              <span className="text-xs font-black text-edge">真实行情</span>
            </div>
            <div className="text-center p-4 rounded-xl border-[3px] border-edge bg-[#86EFAC] shadow-[3px_3px_0_0_var(--color-edge)] hover:-translate-y-1 hover:-translate-x-px hover:shadow-[5px_5px_0_0_var(--color-edge)] transition-all">
              <Wallet className="w-7 h-7 mx-auto mb-2 text-edge" strokeWidth={2.5} />
              <span className="text-xs font-black text-edge">无损模拟</span>
            </div>
            <div className="text-center p-4 rounded-xl border-[3px] border-edge bg-[#93C5FD] shadow-[3px_3px_0_0_var(--color-edge)] hover:-translate-y-1 hover:-translate-x-px hover:shadow-[5px_5px_0_0_var(--color-edge)] transition-all">
              <LineChart className="w-7 h-7 mx-auto mb-2 text-edge" strokeWidth={2.5} />
              <span className="text-xs font-black text-edge">收益复盘</span>
            </div>
          </div>

          {/* 错误提示 */}
          {error && (
            <div className="w-full p-4 rounded-xl border-[3px] border-edge bg-destructive text-white text-sm font-black text-center mb-8 shadow-[4px_4px_0_0_var(--color-edge)] animate-in slide-in-from-top-2">
              {error}
            </div>
          )}

          {/* 登录按钮 */}
          <div className="w-full">
            {loading ? (
              <div className="flex flex-col items-center justify-center h-16 rounded-2xl border-[3.5px] border-edge bg-surface shadow-[4px_4px_0_0_var(--color-edge)] gap-2">
                <Loader2 className="w-6 h-6 text-edge animate-spin" strokeWidth={3} />
                <p className="text-sm font-black text-edge">登录中...</p>
              </div>
            ) : (
              <button
                onClick={handleLinuxDoLogin}
                className="group relative w-full h-16 rounded-2xl border-[3.5px] border-edge bg-primary text-primary-foreground font-black text-lg shadow-[6px_6px_0_0_var(--color-edge)] hover:-translate-y-1 hover:-translate-x-px hover:shadow-[8px_8px_0_0_var(--color-edge)] active:translate-y-1.5 active:translate-x-1.5 active:shadow-[0px_0px_0_0_var(--color-edge)] transition-all flex items-center justify-center gap-3 overflow-hidden"
              >
                {/* 按钮内扫光效果 */}
                <div className="absolute inset-0 w-full h-full bg-white/20 -translate-x-full group-hover:translate-x-full transition-transform duration-700" />
                <Globe className="w-6 h-6 relative z-10" strokeWidth={2.5} />
                <span className="relative z-10 tracking-wide">使用 LinuxDo 登录</span>
              </button>
            )}
          </div>

          <p className="text-[11px] font-black text-center text-muted-foreground mt-8 uppercase tracking-[0.2em]">
            Welcome To The Playground
          </p>
        </div>
      </div>
    </div>
  );
}