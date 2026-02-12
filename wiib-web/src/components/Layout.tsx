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
