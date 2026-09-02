import { NavLink, useNavigate, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useClickOutside } from '../hooks/useClickOutside';
import { useState, useRef } from 'react';
import { useUserStore } from '../stores/userStore';
import { useTheme } from '../hooks/useTheme';
import { useSystemHealth, type HealthLevel } from '../hooks/useSystemHealth';
import { Button } from './ui/button';
import { NotificationBell } from './NotificationBell';
import { LanguageSwitcher } from './LanguageSwitcher';
import { GitHubLink } from './GitHubLink';
import { TickerStrip } from './TickerStrip';
import { OfflineBanner } from './OfflineBanner';
import { ChatDock } from './workbench/ChatDock';
import { cn } from '../lib/utils';
import {
  Home, Briefcase, LogOut, LogIn, Sun, Moon,
  BarChart3, User, ChevronDown, List, DollarSign,
  Settings2, Gem, Globe,
  LineChart, FlaskConical, MessageSquare, Info, ExternalLink,
} from 'lucide-react';

interface Props { children: React.ReactNode }

const MARKET_PATHS = ['/bstock', '/coin', '/commodity', '/tradfi'];

/**
 * 低频四项。≥2xl(1536) 平铺进顶栏、More 消失；1024–1535 收进 More▾ 下拉。
 * 两种形态共用这一份，加入口只改这里。存 key 不存文案，理由同 LED_LABEL_KEY。
 * <p>断点卡 2xl 是量出来的：11 项平铺英文顶栏要 1321px（含两侧固定开销 534px），
 * 1280 差 41px、1366 才够；收着 More 只要 1055px，1024 都装得下。
 * <p>后来加的 Intro 是第 12 项（外链，尾巴带个小箭头），按同样字宽估算约 +70px，1536 仍有富余——
 * 这一项是估的不是量的，再往里加项之前重新量一遍。
 */
type MoreItem = { to: string; icon: React.ReactNode; labelKey: string; external?: boolean };
const MORE_ITEMS: MoreItem[] = [
  { to: '/strategies', icon: <LineChart className="w-4 h-4" />, labelKey: 'nav.strategies' },
  { to: '/backtest', icon: <FlaskConical className="w-4 h-4" />, labelKey: 'nav.backtest' },
  { to: '/comments', icon: <MessageSquare className="w-4 h-4" />, labelKey: 'nav.comments' },
  // 介绍站是单独部署的静态站，不是本应用的路由，只能走外链
  { to: 'https://intro.wtfibought.com', icon: <Info className="w-4 h-4" />, labelKey: 'nav.intro', external: true },
];
/** 激活态只认站内路由：外链永远等不上 pathname，放进来白比一遍 */
const MORE_PATHS = MORE_ITEMS.filter(i => !i.external).map(i => i.to);

/** 当前路由是否落在这组前缀里——下拉自身要跟着亮激活态，不然进了子页顶栏就没了着落 */
const matchPaths = (pathname: string, paths: string[]) =>
  paths.some(p => pathname === p || pathname.startsWith(p + '/'));

/** 模块级常量存 key 不存文案：存文案的话切语言不会变 */
const LED_LABEL_KEY: Record<HealthLevel, string> = {
  ok: 'led.ok', warn: 'led.warn', down: 'led.down', unknown: 'led.unknown',
};

/** 三服务状态灯组：feed(行情上游) / quant(量化) / sim(交易主进程)，悬停看明细 */
function SystemLeds() {
  const { feed, quant, sim } = useSystemHealth();
  const { t } = useTranslation('layout');
  const cls = (l: HealthLevel) =>
    l === 'ok' ? 'led' : l === 'warn' ? 'led led-warn' : l === 'down' ? 'led led-down' : 'led led-off';
  return (
    <div
      className="flex items-center gap-1.5 h-6 px-2.5 rounded-full border border-border bg-background"
      // feed / quant / sim 是服务名，不翻
      title={`feed ${t(LED_LABEL_KEY[feed])} · quant ${t(LED_LABEL_KEY[quant])} · sim ${t(LED_LABEL_KEY[sim])}`}
    >
      <span className={cls(feed)} />
      <span className={cls(quant)} />
      <span className={cls(sim)} />
    </div>
  );
}

export function Layout({ children }: Props) {
  const navigate = useNavigate();
  const location = useLocation();
  const { user, token, logout } = useUserStore();
  const { toggleTheme, isDark } = useTheme();
  const { t } = useTranslation('layout');

  const handleLogout = async () => { await logout(); navigate('/login'); };
  const isMarketActive = matchPaths(location.pathname, MARKET_PATHS);
  const isMoreActive = matchPaths(location.pathname, MORE_PATHS);

  return (
    <div className="min-h-screen flex flex-col bg-background">
      {/* ===== 全宽顶栏 + 行情副条 ===== */}
      {/* 安全区：装成 PWA 后页面顶到屏幕边缘，顶栏自己让开刘海和横屏圆角 */}
      <header className="sticky top-0 z-50 w-full bg-card pt-[env(safe-area-inset-top)] pl-[env(safe-area-inset-left)] pr-[env(safe-area-inset-right)]">
        <div className="flex h-12 items-center gap-5 border-b border-border bg-card px-3 md:px-5">
          {/* Logo */}
          <button
            type="button"
            className="flex items-center gap-2 cursor-pointer shrink-0 focus-visible:outline-none"
            onClick={() => navigate('/')}
            aria-label={t('header.logoHome')}
            title="WhatIfIBought"
          >
            {/* 标志走 h-5：再小右侧浅色端糊成一团，再大要挤 1024 段导航的宽度预算 */}
            <img src="/logo.png" alt="" className="h-5 w-auto" />
            <span className="text-sm font-extrabold tracking-wide">
              WIIB<span className="text-primary">.</span>
            </span>
          </button>

          {/* Desktop Nav */}
          {/* 桌面/移动分界卡在 lg 不是 md：两侧固定开销(logo+内边距+右侧操作区)在 1024 段是 422px，
              1280 起涨到 534px（GitHub 图标回来 + 用户名从 56 放宽到 128）。收着 More 的 8 项
              自身要 522px，768 视口连它都填不进——这是算术不是取舍，768–1023 因此走底栏 5 格。
              1536 起改摊开 11 项（要 787px），见 MORE_ITEMS。
              gap 也从 4 收到 3：1024 段要给 GitHub 让位后的用户名腾出 34px，
              每项自带 px-1，字到字仍有 21px，不挤 */}
          <nav className="hidden lg:flex items-center gap-3 h-full whitespace-nowrap">
            <HeaderNavItem to="/" label={t('nav.home')} />
            <NavDropdown
              label={t('nav.markets')}
              isActive={isMarketActive}
              items={[
                { to: '/bstock', icon: <List className="w-4 h-4" />, label: t('marketMenu.stocks') },
                { to: '/coin', icon: <DollarSign className="w-4 h-4" />, label: t('marketMenu.crypto') },
                { to: '/commodity', icon: <Gem className="w-4 h-4" />, label: t('marketMenu.commodity') },
                { to: '/tradfi', icon: <Globe className="w-4 h-4" />, label: t('marketMenu.tradfi') },
              ]}
            />
            <HeaderNavItem to="/portfolio" label={t('nav.portfolio')} />
            {/* 竞技场紧跟持仓：自己的仓位与 AI 的仓位是同一件事的两面，挨着看 */}
            <HeaderNavItem to="/arena" label={t('nav.arena')} />
            {/* 配置＝BYOK 模型端点，竞技场里的 trader 全靠它，所以紧跟竞技场 */}
            <HeaderNavItem to="/ai" label={t('nav.config')} />
            <HeaderNavItem to="/ranking" label={t('nav.ranking')} />
            <HeaderNavItem to="/games" label={t('nav.games')} />
            {/* 低频四项两种形态，同一份 MORE_ITEMS：宽屏摊开、窄屏收进下拉。
                display:none 的那一份不参与 flex gap，两边间距都对 */}
            <div className="hidden 2xl:flex items-center gap-3 h-full">
              {MORE_ITEMS.map(({ to, labelKey, external }) => (
                <HeaderNavItem key={to} to={to} label={t(labelKey)} external={external} />
              ))}
            </div>
            <NavDropdown
              className="2xl:hidden"
              label={t('nav.more')}
              isActive={isMoreActive}
              items={MORE_ITEMS.map(({ to, icon, labelKey, external }) => ({ to, icon, label: t(labelKey), external }))}
            />
          </nav>

          {/* Actions */}
          <div className="hidden lg:flex items-center gap-2 ml-auto whitespace-nowrap">
            <SystemLeds />

            <GitHubLink className="hidden xl:inline-flex" />

            <LanguageSwitcher />

            <Button
              variant="ghost"
              size="icon"
              onClick={toggleTheme}
              className="w-8 h-8"
              aria-label={isDark ? t('header.toLight') : t('header.toDark')}
            >
              {isDark ? <Sun className="w-4 h-4" /> : <Moon className="w-4 h-4" />}
            </Button>

            {/* 登录按钮只给真游客（/intro 是唯一免登录页）。有 token 但 user 还没拉回来时
                两边都不显示，否则每次刷新都要闪一下"登录"再变回用户名 */}
            {user ? (
              <div className="flex items-center gap-2">
                {/* lg 段(1024–1279)横向只剩几十像素，用户名给上限+省略号：它是登录态的可见凭据，
                    宁可截断也别整个藏掉；xl 起 GitHub 回来了空间仍够，放宽到 32 */}
                <span className="text-xs font-semibold text-muted-foreground hidden lg:block max-w-14 xl:max-w-32 truncate">{user.username}</span>
                <NotificationBell />
                <Button variant="ghost" size="icon" className="w-8 h-8" onClick={handleLogout}>
                  <LogOut className="w-4 h-4" />
                </Button>
              </div>
            ) : !token && (
              <Button size="sm" onClick={() => navigate('/login')}>
                <LogIn className="w-3.5 h-3.5" />
                {t('header.login')}
              </Button>
            )}
          </div>

          {/* 移动端右侧：GitHub + 主题切换，其余入口在底部 Tab 和「我的」页 */}
          <div className="flex lg:hidden items-center ml-auto">
            <GitHubLink className="inline-flex" />
            <LanguageSwitcher />
            <Button
              variant="ghost"
              size="icon"
              onClick={toggleTheme}
              className="w-8 h-8"
              aria-label={isDark ? t('header.toLight') : t('header.toDark')}
            >
              {isDark ? <Sun className="w-4 h-4" /> : <Moon className="w-4 h-4" />}
            </Button>
          </div>
        </div>

        <TickerStrip />
        <OfflineBanner />
      </header>

      {/* Main */}
      <main className="flex-1 pb-24 lg:pb-6 pt-4 pl-[env(safe-area-inset-left)] pr-[env(safe-area-inset-right)]">
        {children}
      </main>

      {/* ===== 移动端底部 Tab：贴边实条 ===== */}
      <nav className="fixed bottom-0 inset-x-0 lg:hidden z-50 flex items-stretch border-t border-border bg-card pb-[env(safe-area-inset-bottom)] pl-[env(safe-area-inset-left)] pr-[env(safe-area-inset-right)]">
        <BottomNavItem to="/" icon={<Home className="w-5 h-5" />} label={t('nav.home')} />
        <BottomNavItem to="/bstock" icon={<BarChart3 className="w-5 h-5" />} label={t('nav.markets')} forceActive={isMarketActive} />
        <BottomNavItem to="/portfolio" icon={<Briefcase className="w-5 h-5" />} label={t('nav.portfolio')} />
        <BottomNavItem to="/me" icon={<User className="w-5 h-5" />} label={t('nav.me')} />
        <BottomNavItem to="/ai" icon={<Settings2 className="w-5 h-5" />} label={t('nav.config')} />
      </nav>

      {/* 全站悬浮研判对话（BYOK）：对话要登录，游客不给气泡 */}
      {user && <ChatDock />}
    </div>
  );
}

const HEADER_NAV_BASE = "flex items-center h-12 px-1 text-[13px] transition-colors";

/** 顶栏导航项：激活 = 文字加重 + 底部 2px 橙色指示线（贴顶栏底边） */
function HeaderNavItem({ to, label, external }: { to: string; label: string; external?: boolean }) {
  // 外链没有激活态可言，直接一个 a：末尾的小箭头告诉用户这一下会跳出站
  if (external) {
    return (
      <a href={to} target="_blank" rel="noopener noreferrer"
         className={cn(HEADER_NAV_BASE, "gap-1 text-muted-foreground font-medium hover:text-foreground")}>
        {label}
        <ExternalLink className="w-3 h-3" />
      </a>
    );
  }
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        cn(
          HEADER_NAV_BASE,
          isActive
            ? "text-foreground font-semibold shadow-[inset_0_-2px_0_var(--color-primary)]"
            : "text-muted-foreground font-medium hover:text-foreground"
        )
      }
    >
      {label}
    </NavLink>
  );
}

interface NavDropdownItem { to: string; icon: React.ReactNode; label: string; external?: boolean }

/**
 * 顶栏下拉壳子：市场与 More 共用一份，面板宽度/激活态/指示线逐字同款，别再复制一遍。
 *
 * @param className 给调用方控 display 用（More 只在 2xl 以下出现）。合在根节点上，
 *                  藏的时候整个下拉连同定位上下文一起消失
 */
function NavDropdown({ label, isActive, items, className }:
  { label: string; isActive: boolean; items: NavDropdownItem[]; className?: string }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useClickOutside(ref, () => setOpen(false));

  return (
    <div ref={ref} className={cn("relative h-full", className)}>
      <button
        onClick={() => setOpen(v => !v)}
        className={cn(
          "flex items-center gap-1 h-12 px-1 text-[13px] transition-colors cursor-pointer",
          isActive
            ? "text-foreground font-semibold shadow-[inset_0_-2px_0_var(--color-primary)]"
            : "text-muted-foreground font-medium hover:text-foreground"
        )}
      >
        {label}
        <ChevronDown className={cn("w-3.5 h-3.5 transition-transform", open && "rotate-180")} />
      </button>

      {open && (
        // z-50 不能省：NumberFlow 的 transform 会创建层叠上下文，副条数字会盖到面板上
        <div className="absolute top-full left-0 mt-1 w-44 rounded-lg pt-card shadow-lg py-1 z-50 animate-in fade-in slide-in-from-top-2">
          {items.map(({ to, icon, label: itemLabel, external }) => external ? (
            <a
              key={to}
              href={to}
              target="_blank"
              rel="noopener noreferrer"
              onClick={() => setOpen(false)}
              className="flex items-center gap-2.5 px-3.5 py-2 text-sm font-medium text-muted-foreground hover:text-foreground hover:bg-surface-hover transition-colors"
            >
              {icon}
              {itemLabel}
              <ExternalLink className="w-3 h-3 ml-auto opacity-60" />
            </a>
          ) : (
            <NavLink
              key={to}
              to={to}
              onClick={() => setOpen(false)}
              className={({ isActive: a }) =>
                cn(
                  "flex items-center gap-2.5 px-3.5 py-2 text-sm font-medium transition-colors",
                  a
                    ? "text-foreground bg-surface-hover shadow-[inset_2px_0_0_var(--color-primary)]"
                    : "text-muted-foreground hover:text-foreground hover:bg-surface-hover"
                )
              }
            >
              {icon}
              {itemLabel}
            </NavLink>
          ))}
        </div>
      )}
    </div>
  );
}

/** 底部 Tab 项：激活 = 橙色图标 + 顶部 2px 橙线 */
function BottomNavItem({ to, icon, label, forceActive }: { to: string; icon: React.ReactNode; label: string; forceActive?: boolean }) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        cn(
          "flex-1 flex flex-col items-center gap-0.5 py-1.5 transition-colors",
          (forceActive || isActive)
            ? "text-primary shadow-[inset_0_2px_0_var(--color-primary)]"
            : "text-muted-foreground"
        )
      }
    >
      {icon}
      <span className="text-[10px] font-semibold">{label}</span>
    </NavLink>
  );
}
