import { NavLink, useNavigate } from 'react-router-dom';
import { useUserStore } from '../stores/userStore';
import { useTheme } from '../hooks/useTheme';
import { Button } from './ui/button';
import { cn } from '../lib/utils';
import { Home, List, Briefcase, LogOut, LogIn, TrendingUp, Sun, Moon, Trophy, LineChart, Coins } from 'lucide-react';

interface Props {
  children: React.ReactNode;
}

export function Layout({ children }: Props) {
  const navigate = useNavigate();
  const { user, logout } = useUserStore();
  const { ref: themeRef, toggleTheme, isDark } = useTheme();

  const handleLogout = async () => {
    await logout();
    navigate('/login');
  };

  return (
    <div className="min-h-screen flex flex-col bg-background">
      {/* Header */}
      <header className="sticky top-0 z-50 w-full border-b bg-card/80 backdrop-blur-md">
        <div className="max-w-4xl mx-auto px-4 py-3 flex justify-between items-center">
          {/* Logo */}
          <button
            type="button"
            className="flex items-center gap-2 cursor-pointer group focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 rounded-md"
            onClick={() => navigate('/')}
            aria-label="返回首页"
          >
            <TrendingUp className="w-6 h-6 text-primary transition-transform group-hover:scale-110" />
            <span className="text-lg font-bold bg-gradient-to-r from-primary to-blue-400 bg-clip-text text-transparent">
              WhatIfIBought
            </span>
          </button>

          {/* Desktop Navigation */}
          <nav className="hidden md:flex items-center gap-1">
            <HeaderNavItem to="/" icon={<Home className="w-4 h-4" />} label="行情" />
            <HeaderNavItem to="/stocks" icon={<List className="w-4 h-4" />} label="股票" />
            <HeaderNavItem to="/coin" icon={<Coins className="w-4 h-4" />} label="钱币" />
            <HeaderNavItem to="/options" icon={<LineChart className="w-4 h-4" />} label="期权" />
            <HeaderNavItem to="/portfolio" icon={<Briefcase className="w-4 h-4" />} label="持仓" />
            <HeaderNavItem to="/ranking" icon={<Trophy className="w-4 h-4" />} label="排行" />
          </nav>

          {/* User Actions */}
          <div className="flex items-center gap-2">
            {/* GitHub */}
            <a
              href="https://github.com/mamawai/wiib"
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center justify-center w-9 h-9 rounded-md text-muted-foreground hover:text-foreground hover:bg-accent transition-colors"
              aria-label="GitHub"
            >
              <svg className="w-4 h-4" viewBox="0 0 24 24" fill="currentColor"><path d="M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12"/></svg>
            </a>

            {/* Theme Toggle */}
            <Button
              ref={themeRef}
              variant="ghost"
              size="icon"
              onClick={toggleTheme}
              className="w-9 h-9"
              aria-label={isDark ? '切换到亮色模式' : '切换到暗色模式'}
            >
              {isDark ? <Sun className="w-4 h-4" /> : <Moon className="w-4 h-4" />}
            </Button>

            {user ? (
              <>
                <span className="text-sm text-muted-foreground hidden sm:inline">
                  {user.username}
                </span>
                <Button variant="ghost" size="sm" onClick={handleLogout}>
                  <LogOut className="w-4 h-4" />
                  <span className="hidden sm:inline">退出</span>
                </Button>
              </>
            ) : (
              <Button variant="default" size="sm" onClick={() => navigate('/login')}>
                <LogIn className="w-4 h-4" />
                <span>登录</span>
              </Button>
            )}
          </div>
        </div>
      </header>

      {/* Main Content */}
      <main className="flex-1 pb-16 md:pb-0">
        {children}
      </main>

      {/* Bottom Navigation - Mobile Only */}
      <nav className="fixed bottom-0 left-0 right-0 bg-card/95 backdrop-blur-md border-t md:hidden z-50">
        <div className="flex">
          <BottomNavItem to="/" icon={<Home className="w-5 h-5" />} label="行情" />
          <BottomNavItem to="/stocks" icon={<List className="w-5 h-5" />} label="股票" />
          <BottomNavItem to="/coin" icon={<Coins className="w-5 h-5" />} label="钱币" />
          <BottomNavItem to="/options" icon={<LineChart className="w-5 h-5" />} label="期权" />
          <BottomNavItem to="/portfolio" icon={<Briefcase className="w-5 h-5" />} label="持仓" />
          <BottomNavItem to="/ranking" icon={<Trophy className="w-5 h-5" />} label="排行" />
        </div>
      </nav>
    </div>
  );
}

function HeaderNavItem({ to, icon, label }: { to: string; icon: React.ReactNode; label: string }) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        cn(
          "flex items-center gap-1.5 px-3 py-2 rounded-md text-sm font-medium transition-colors",
          isActive
            ? "bg-primary/10 text-primary"
            : "text-muted-foreground hover:text-foreground hover:bg-accent"
        )
      }
    >
      {icon}
      {label}
    </NavLink>
  );
}

function BottomNavItem({ to, icon, label }: { to: string; icon: React.ReactNode; label: string }) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        cn(
          "flex-1 flex flex-col items-center py-2 transition-colors",
          isActive ? "text-primary" : "text-muted-foreground hover:text-foreground"
        )
      }
    >
      {icon}
      <span className="text-xs mt-1">{label}</span>
    </NavLink>
  );
}
