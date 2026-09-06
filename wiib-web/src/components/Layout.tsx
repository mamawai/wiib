import { NavLink, Link, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useClickOutside } from '../hooks/useClickOutside';
import { useState, useRef, useEffect } from 'react';
import { useUserStore } from '../stores/userStore';
import { useTheme } from '../hooks/useTheme';
import { useSystemHealth, type HealthLevel } from '../hooks/useSystemHealth';
import { NotificationBell } from './NotificationBell';
import { useLangToggle } from '../hooks/useLangToggle';
import { TickerStrip } from './TickerStrip';
import { OfflineBanner } from './OfflineBanner';
import { ChatDock } from './workbench/ChatDock';
import { cn } from '../lib/utils';
import {
  Home, Briefcase, Sun, Moon,
  BarChart3, User, ChevronDown, List, DollarSign,
  Settings2, Gem, Globe,
  LineChart, FlaskConical, MessageSquare, Info, ExternalLink,
} from 'lucide-react';

interface Props { children: React.ReactNode }

const MARKET_PATHS = ['/bstock', '/coin', '/commodity', '/tradfi'];

/** 下拉里的图标一律 15px：外面 .nav .ic 是 13px 的，面板里那个尺寸太小 */
const MENU_IC = 'size-[15px]';

/** 低频四项，一直收在「更多」下拉里。加入口只改这里。存 key 不存文案，理由同 LED_LABEL_KEY */
type MoreItem = { to: string; icon: React.ReactNode; labelKey: string; external?: boolean };
const MORE_ITEMS: MoreItem[] = [
  { to: '/strategies', icon: <LineChart className={MENU_IC} />, labelKey: 'nav.strategies' },
  { to: '/backtest', icon: <FlaskConical className={MENU_IC} />, labelKey: 'nav.backtest' },
  { to: '/comments', icon: <MessageSquare className={MENU_IC} />, labelKey: 'nav.comments' },
  // 介绍站是单独部署的静态站，不是本应用的路由，只能走外链
  { to: 'https://intro.wtfibought.com', icon: <Info className={MENU_IC} />, labelKey: 'nav.intro', external: true },
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
    <span
      className="leds"
      // feed / quant / sim 是服务名，不翻
      title={`feed ${t(LED_LABEL_KEY[feed])} · quant ${t(LED_LABEL_KEY[quant])} · sim ${t(LED_LABEL_KEY[sim])}`}
    >
      <i className={cls(feed)} />
      <i className={cls(quant)} />
      <i className={cls(sim)} />
    </span>
  );
}

export function Layout({ children }: Props) {
  const location = useLocation();
  const { user, token } = useUserStore();
  const { toggleTheme, isDark } = useTheme();
  const { t, i18n } = useTranslation('layout');
  const { toggle: toggleLang } = useLangToggle();

  const isMarketActive = matchPaths(location.pathname, MARKET_PATHS);
  const isMoreActive = matchPaths(location.pathname, MORE_PATHS);

  const navRef = useRef<HTMLElement>(null);
  const dotRef = useRef<HTMLSpanElement>(null);
  // 路由/语言变了要重新对位，但监听不想重挂，把当前的归位函数留一份在这
  const homeRef = useRef<(() => void) | null>(null);

  // 橙方块停在激活项左侧 15px；悬停到别的项就滑过去，移开滑回。首帧掐掉过渡，免得从最左飞过来
  useEffect(() => {
    const nav = navRef.current, dot = dotRef.current;
    if (!nav || !dot) return;
    // 用 rect 差而不是 offsetLeft：下拉那一项外面套了个 relative 壳，offsetLeft 会算成 0
    const moveTo = (el: HTMLElement) => {
      dot.style.left = `${el.getBoundingClientRect().left - nav.getBoundingClientRect().left - 15}px`;
      dot.style.opacity = '1';
    };
    // 当前页不在导航里（/me、/login 这些）就把方块收掉
    const home = () => {
      const on = nav.querySelector<HTMLElement>('[data-nav-item].on');
      if (on) moveTo(on); else dot.style.opacity = '0';
    };
    homeRef.current = home;
    dot.style.transition = 'none';
    home();
    void dot.offsetWidth;
    dot.style.transition = '';

    // 委托到 nav 上：下拉面板里的项不带 data-nav-item，扫过去不会把方块带跑
    const over = (e: MouseEvent) => {
      const item = (e.target as HTMLElement).closest<HTMLElement>('[data-nav-item]');
      if (item) moveTo(item);
    };
    nav.addEventListener('mouseover', over);
    nav.addEventListener('mouseleave', home);
    window.addEventListener('resize', home);
    // 字体换成 Archivo 后每项宽度会变，等字体就位再对一次
    void document.fonts.ready.then(home);
    return () => {
      nav.removeEventListener('mouseover', over);
      nav.removeEventListener('mouseleave', home);
      window.removeEventListener('resize', home);
    };
  }, []);

  useEffect(() => { homeRef.current?.(); }, [location.pathname, i18n.language]);

  // 语言/主题两颗，桌面和手机顶栏共用
  const langButton = (
    <button type="button" onClick={toggleLang} aria-label={t('common:language.switch')}>
      中 / EN
    </button>
  );
  const themeButton = (
    <button type="button" onClick={toggleTheme} aria-label={isDark ? t('header.toLight') : t('header.toDark')}>
      {isDark ? <Sun className="ic" /> : <Moon className="ic" />}
    </button>
  );

  return (
    <div className="min-h-screen flex flex-col bg-background">
      {/* ===== 顶栏 + 行情副条 ===== */}
      {/* 安全区：装成 PWA 后页面顶到屏幕边缘，顶栏自己让开刘海和横屏圆角 */}
      <header className="pt-[env(safe-area-inset-top)] pl-[env(safe-area-inset-left)] pr-[env(safe-area-inset-right)]">
        <div className="wrap">
          {/* 桌面：logo + 文字导航 + 右侧工具 */}
          <div className="top hidden lg:flex">
            <Link to="/" className="logo" aria-label={t('header.logoHome')}>WIIB<i>.</i></Link>

            <nav ref={navRef} className="nav">
              <span ref={dotRef} className="nav-dot" style={{ opacity: 0 }} />
              <HeaderNavItem to="/" label={t('nav.home')} />
              <NavDropdown
                label={t('nav.markets')}
                isActive={isMarketActive}
                items={[
                  { to: '/bstock', icon: <List className={MENU_IC} />, label: t('marketMenu.stocks') },
                  { to: '/coin', icon: <DollarSign className={MENU_IC} />, label: t('marketMenu.crypto') },
                  { to: '/commodity', icon: <Gem className={MENU_IC} />, label: t('marketMenu.commodity') },
                  { to: '/tradfi', icon: <Globe className={MENU_IC} />, label: t('marketMenu.tradfi') },
                ]}
              />
              <HeaderNavItem to="/portfolio" label={t('nav.portfolio')} />
              {/* 竞技场紧跟持仓：自己的仓位与 AI 的仓位是同一件事的两面，挨着看 */}
              <HeaderNavItem to="/arena" label={t('nav.arena')} />
              {/* 配置＝BYOK 模型端点，竞技场里的 trader 全靠它，所以紧跟竞技场 */}
              <HeaderNavItem to="/ai" label={t('nav.config')} />
              <HeaderNavItem to="/ranking" label={t('nav.ranking')} />
              <HeaderNavItem to="/games" label={t('nav.games')} />
              <NavDropdown
                label={t('nav.more')}
                isActive={isMoreActive}
                items={MORE_ITEMS.map(({ to, icon, labelKey, external }) => ({ to, icon, label: t(labelKey), external }))}
              />
            </nav>

            <div className="tools">
              <SystemLeds />
              {langButton}
              {themeButton}
              {/* 有 token 但 user 还没拉回来时两边都不显示，否则每次刷新都要闪一下"登录"再变回用户名 */}
              {user ? (
                <>
                  <NotificationBell />
                  {/* 退出登录在「我的」页里，顶栏只留个入口 */}
                  <Link to="/me">{user.username}</Link>
                </>
              ) : !token && (
                <Link to="/login" className="btn sm">{t('header.login')}</Link>
              )}
            </div>
          </div>

          {/* 手机：logo + 语言 + 主题，其余入口在底部 Tab 和「我的」页 */}
          <div className="flex lg:hidden items-center h-14">
            <Link to="/" className="logo text-[20px]" aria-label={t('header.logoHome')}>WIIB<i>.</i></Link>
            <div className="tools">
              {langButton}
              {themeButton}
            </div>
          </div>
        </div>

        <div className="rule" />
        <TickerStrip />
        <OfflineBanner />
      </header>

      {/* pb-24 是给底部 Tab 让位，桌面端不留内边距，页内自己定 */}
      <main className="flex-1 pb-24 lg:pb-0 pl-[env(safe-area-inset-left)] pr-[env(safe-area-inset-right)]">
        {children}
      </main>

      {/* w-full 不能省：外层是 flex-col，.wrap 的 margin:auto 会让它缩成内容宽 */}
      <div className="wrap w-full hidden lg:block">
        <footer className="pt">
          <span>{t('footer.brand')}</span>
          <span>{t('footer.disclaimer')}</span>
        </footer>
      </div>

      {/* ===== 手机端底部 Tab：贴边实条 ===== */}
      <nav className="fixed bottom-0 inset-x-0 lg:hidden z-50 flex items-stretch border-t-2 border-foreground bg-background pb-[env(safe-area-inset-bottom)] pl-[env(safe-area-inset-left)] pr-[env(safe-area-inset-right)]">
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

/** 顶栏导航项：样式全在 .nav a 里，这儿只负责激活时挂 on，外加给橙方块留个抓手 */
function HeaderNavItem({ to, label }: { to: string; label: string }) {
  return (
    <NavLink to={to} data-nav-item className={({ isActive }) => (isActive ? 'on' : '')}>
      {label}
    </NavLink>
  );
}

interface NavDropdownItem { to: string; icon: React.ReactNode; label: string; external?: boolean }

/** 顶栏下拉壳子：市场与更多共用一份，面板宽度/激活态逐字同款，别再复制一遍 */
function NavDropdown({ label, isActive, items }:
  { label: string; isActive: boolean; items: NavDropdownItem[] }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useClickOutside(ref, () => setOpen(false));

  return (
    <div ref={ref} className="relative flex items-center">
      {/* .nav a 那套样式在按钮上不生效，照抄一遍：16px 500，激活加重到 700 */}
      <button
        type="button"
        data-nav-item
        onClick={() => setOpen(v => !v)}
        className={cn(
          'inline-flex items-center gap-1 py-1 cursor-pointer transition-colors',
          isActive ? 'on text-foreground font-bold' : 'text-muted-foreground hover:text-foreground',
        )}
      >
        {label}
        <ChevronDown className={cn('ic transition-transform', open && 'rotate-180')} />
      </button>

      {open && (
        // z-50 不能省：NumberFlow 的 transform 会创建层叠上下文，副条数字会盖到面板上
        <div className="absolute top-full left-0 mt-1.5 min-w-44 border border-foreground bg-background py-2 z-50 animate-in fade-in slide-in-from-top-2">
          {items.map(({ to, icon, label: itemLabel, external }) => external ? (
            <a
              key={to}
              href={to}
              target="_blank"
              rel="noopener noreferrer"
              onClick={() => setOpen(false)}
              className="flex items-center gap-2.5 px-3.5 py-2 text-sm font-medium text-muted-foreground hover:text-foreground transition-colors"
            >
              {icon}
              {itemLabel}
              {/* 尾巴上的小箭头告诉用户这一下会跳出站 */}
              <ExternalLink className="w-3 h-3 ml-auto opacity-60" />
            </a>
          ) : (
            <NavLink
              key={to}
              to={to}
              onClick={() => setOpen(false)}
              className={({ isActive: a }) =>
                cn(
                  'flex items-center gap-2.5 px-3.5 py-2 text-sm transition-colors',
                  a ? 'text-foreground font-bold' : 'text-muted-foreground font-medium hover:text-foreground',
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

/** 底部 Tab 项：激活 = 墨色字 + 图标上方一颗橙方块 */
function BottomNavItem({ to, icon, label, forceActive }: { to: string; icon: React.ReactNode; label: string; forceActive?: boolean }) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        cn(
          'flex-1 flex flex-col items-center gap-0.5 py-1.5 transition-colors',
          (forceActive || isActive) ? 'text-foreground' : 'text-muted-foreground',
        )
      }
    >
      {({ isActive }) => (
        <>
          {/* 不激活也占着这 7px，免得切格子时整列跳一下 */}
          <span className={cn('w-[7px] h-[7px]', (forceActive || isActive) && 'bg-primary')} />
          {icon}
          <span className="text-[10px] font-semibold">{label}</span>
        </>
      )}
    </NavLink>
  );
}
