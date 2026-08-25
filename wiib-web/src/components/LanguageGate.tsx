import { useEffect, useState } from 'react';
import { Languages } from 'lucide-react';
import { cn } from '../lib/utils';
import { applyLanguage } from '../lib/language';
import type { Lang } from '../i18n';

/**
 * 首次进门的语言选择。挂在 App 的路由外层（不是 Layout）——/login 在 Layout 之外，
 * 挂 Layout 的话游客在登录页看不到。
 *
 * <b>"选没选过"另立一个键</b>：i18n 的检测器开了 {@code caches: ['localStorage']}，
 * 首次加载就把<b>自动检测</b>的结果写进 wiib-lang 了。那个键有值只说明"检测过"，
 * 不说明"人选过"，拿它当判据这个框永远不会弹。
 */
const CHOSEN_KEY = 'wiib-lang-chosen';

/**
 * 两侧文案硬编码在这里，<b>不进词表</b>。全站唯一该这么做的地方：这个框要
 * <b>同时</b>并排显示两种语言，进了词表只会渲染出当前那一门，功能直接失效。
 * 别顺手"修一下"给挪进 locales。
 */
const CHOICES: { lang: Lang; name: string; body: string; footer: string }[] = [
  {
    lang: 'en',
    name: 'English',
    body: 'The interface and everything the AI writes — decision logs, reviews, chat answers — come back in English.',
    footer: 'You can change this any time from the top bar.',
  },
  {
    lang: 'zh',
    name: '中文',
    body: '界面与 AI 产出都用中文——决策日志、复盘、研判回答，都是中文写的。',
    footer: '随时可以在顶栏切换。',
  },
];

function hasChosen() {
  // 隐私模式/禁用存储时读写都会抛：那就当没选过，最坏是每次进来问一次，不能因此白屏
  try {
    return localStorage.getItem(CHOSEN_KEY) === '1';
  } catch {
    return false;
  }
}

export function LanguageGate() {
  const [open, setOpen] = useState(() => !hasChosen());

  // 选择期间锁滚动：背后的页面能滚会显得这框可以绕过去，而它是必须选的
  useEffect(() => {
    if (!open) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { document.body.style.overflow = prev; };
  }, [open]);

  if (!open) return null;

  const choose = (lang: Lang) => {
    applyLanguage(lang);
    try {
      localStorage.setItem(CHOSEN_KEY, '1');
    } catch { /* 存不下就下次再问一遍，不影响这次的选择生效 */ }
    setOpen(false);
  };

  return (
    // 没有关闭键、不响应 ESC、点遮罩也不关：两个选项等权且都是一步到位，
    // 不存在"没看懂需要逃生"的情况；也只有这样"选过一次"才真的是选过
    <div className="fixed inset-0 z-[300] flex items-center justify-center p-4 bg-black/70 backdrop-blur-sm">
      <div className="pt-card rounded-lg w-full max-w-2xl overflow-hidden">
        <div className="flex items-center gap-2 px-5 py-3 border-b border-border">
          <Languages className="w-3.5 h-3.5 text-primary" />
          <span className="microlabel">LANGUAGE / 语言</span>
        </div>

        {/* 桌面左右并排，移动端上下堆叠；中缝那条线跟着断点换方向 */}
        <div className="grid sm:grid-cols-2 divide-y sm:divide-y-0 sm:divide-x divide-border">
          {CHOICES.map(c => (
            <button
              key={c.lang}
              type="button"
              onClick={() => choose(c.lang)}
              lang={c.lang === 'zh' ? 'zh-CN' : 'en'}
              className={cn(
                'group text-left px-5 py-6 flex flex-col gap-2 min-h-[9.5rem]',
                'hover:bg-surface-hover focus-visible:bg-surface-hover',
                'focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-inset focus-visible:ring-primary',
              )}
            >
              <span className="text-lg font-black tracking-tight group-hover:text-primary">
                {c.name}
              </span>
              <span className="text-xs leading-relaxed text-muted-foreground">{c.body}</span>
              <span className="mt-auto text-[10px] text-muted-foreground/70">{c.footer}</span>
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
