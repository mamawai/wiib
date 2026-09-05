import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Bot } from 'lucide-react';
import { userApi } from '../api';
import { Card, CardContent } from './ui/card';
import { useToast } from './ui/use-toast';
import { cn } from '../lib/utils';
import type { Lang } from '../i18n';

/** 语言名用本族语写死，同 LanguageSwitcher */
const LANG_LABEL: Record<Lang, string> = { zh: '中文', en: 'English' };

/**
 * agent 提示词语言：落 user.lang，只在这里改，顶栏的界面语言切换碰不到它。
 * 交互抄 ProfilePublicToggle：进页拉一次，点整行乐观切换，失败弹回。
 */
export function AgentLangSetting() {
  const { t } = useTranslation('ai');
  const { toast } = useToast();
  // null = 还没拉回来，先禁用，免得先渲染成一门再跳到另一门
  const [lang, setLang] = useState<Lang | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    userApi.getLang().then(setLang).catch(() => setLang(null));
  }, []);

  const toggle = async () => {
    if (lang == null || saving) return;
    const next: Lang = lang === 'zh' ? 'en' : 'zh';
    setSaving(true);
    setLang(next);
    try {
      await userApi.setLang(next);
    } catch (e) {
      setLang(lang);
      toast((e as Error).message || t('agentLang.failed'), 'error');
    } finally {
      setSaving(false);
    }
  };

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
          onClick={() => void toggle()}
          disabled={lang == null || saving}
          className="flex items-start gap-3 w-full text-left cursor-pointer disabled:opacity-60 disabled:cursor-not-allowed"
        >
          <div className="w-8 h-8 rounded-lg bg-surface-hover flex items-center justify-center shrink-0">
            <Bot className="w-4 h-4 text-primary" />
          </div>
          <div className="flex-1 min-w-0">
            <div className="text-sm font-medium">{t('agentLang.title')}</div>
            <p className="mt-0.5 text-xs text-muted-foreground leading-relaxed">{t('agentLang.desc')}</p>
          </div>
          <div className="flex rounded-md border border-border overflow-hidden divide-x divide-border shrink-0 mt-0.5">
            <span className={seg(lang === 'zh')}>{LANG_LABEL.zh}</span>
            <span className={seg(lang === 'en')}>{LANG_LABEL.en}</span>
          </div>
        </button>
      </CardContent>
    </Card>
  );
}
