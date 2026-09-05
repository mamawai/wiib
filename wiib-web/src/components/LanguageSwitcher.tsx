import { useTranslation } from 'react-i18next';
import { Languages } from 'lucide-react';
import { Button } from './ui/button';
import { Card, CardContent } from './ui/card';
import { cn } from '../lib/utils';
import i18n, { currentLang, type Lang } from '../i18n';

/**
 * 语言名一律用本族语写死，不跟着界面语言翻译——中文在英文界面里也该是"中文"，
 * 不然切错语言的人反而找不着回来的那一格。同理不进词表。
 */
const LANG_LABEL: Record<Lang, string> = { zh: '中文', en: 'English' };

/**
 * 当前语言与"点一下切到哪"。只有两门语言，点按直切，不做下拉。
 * 只切界面，不碰服务端：agent 提示词语言是另一个开关（配置页 AgentLangSetting）。
 */
function useLangToggle() {
  const current = currentLang();
  const next: Lang = current === 'zh' ? 'en' : 'zh';
  return { current, next, toggle: () => void i18n.changeLanguage(next) };
}

/**
 * 顶栏/登录页用的紧凑图标按钮。类名与交互跟旁边的主题切换按钮逐字对齐，
 * 顶栏那排图标必须长成一家人。
 */
export function LanguageSwitcher({ className }: { className?: string }) {
  const { t } = useTranslation();
  const { next, toggle } = useLangToggle();

  return (
    <Button
      variant="ghost"
      size="icon"
      onClick={toggle}
      className={cn('w-8 h-8', className)}
      aria-label={t('language.switch')}
      title={LANG_LABEL[next]}
    >
      <Languages className="w-4 h-4" />
    </Button>
  );
}

/**
 * 「我的」页里的正式设置项，抄同页主题切换那一行：整行是按钮，左图标盒 + 标签，右边显示当前值。
 * 右边那对分段格是"看"的不是"点"的（按钮里不能再套按钮），点整行切换。
 */
export function LanguageSettingRow() {
  const { t } = useTranslation();
  const { current, toggle } = useLangToggle();

  const seg = (active: boolean) =>
    cn(
      'px-3 py-1.5 text-xs font-semibold transition-colors',
      active
        ? 'bg-card-2 text-foreground shadow-[inset_0_2px_0_var(--color-primary)]'
        : 'text-muted-foreground',
    );

  return (
    <Card>
      <CardContent className="pt-5">
        <button
          onClick={toggle}
          className="flex items-center gap-3 w-full text-left cursor-pointer"
          aria-label={t('language.switch')}
        >
          <div className="w-8 h-8 rounded-lg bg-surface-hover flex items-center justify-center">
            <Languages className="w-4 h-4 text-sky-400" />
          </div>
          <span className="flex-1 text-sm font-medium">{t('language.label')}</span>
          <div className="flex rounded-md border border-border overflow-hidden divide-x divide-border shrink-0">
            <span className={seg(current === 'zh')}>{LANG_LABEL.zh}</span>
            <span className={seg(current === 'en')}>{LANG_LABEL.en}</span>
          </div>
        </button>
      </CardContent>
    </Card>
  );
}
