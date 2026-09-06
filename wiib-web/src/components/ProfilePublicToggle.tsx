import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Eye, EyeOff } from 'lucide-react';
import { userApi } from '../api';
import { useToast } from './ui/use-toast';
import { cn } from '../lib/utils';

/**
 * 详情页公开开关。/me 与 /portfolio 各摆一处，
 * 两处摆同一个组件而不是各写一份——两份状态逻辑迟早只改一边。
 */
export function ProfilePublicToggle() {
  const { t } = useTranslation('account');
  const { toast } = useToast();
  // null = 还没拉回来。此时禁用开关，免得先渲染成"关"再跳回"开"闪一下
  const [profilePublic, setProfilePublic] = useState<boolean | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    userApi.getProfilePublic().then(setProfilePublic).catch(() => setProfilePublic(null));
  }, []);

  const toggle = async () => {
    if (profilePublic == null || saving) return;
    const next = !profilePublic;
    setSaving(true);
    // 乐观更新：开关是即时反馈的控件，等一个来回再动会让人觉得点了没反应；失败再弹回去
    setProfilePublic(next);
    try {
      await userApi.setProfilePublic(next);
      toast(next ? t('toggle.on') : t('toggle.off'), 'success');
    } catch (e) {
      setProfilePublic(!next);
      toast((e as Error).message || t('toggle.failed'), 'error');
    } finally {
      setSaving(false);
    }
  };

  const off = profilePublic === false;

  return (
    <section className="sec">
      <div className="sec-h">
        <h2>{t('toggle.title')}</h2>
      </div>
      <button
        onClick={() => void toggle()}
        disabled={profilePublic == null || saving}
        className="flex items-center flex-wrap gap-8 w-full text-left cursor-pointer disabled:opacity-60 disabled:cursor-not-allowed"
      >
        <p className="m-0 text-[14px] mute max-w-[70ch] leading-[1.6]">{t('toggle.desc')}</p>
        <span className="ml-auto shrink-0 inline-flex items-center gap-2.5">
          {off ? <EyeOff className="ic mute" /> : <Eye className="ic text-primary" />}
          {/* 方形开关：关=空框墨滑块，开=墨底纸色滑块 */}
          <span className={cn('relative w-11 h-6 border border-foreground transition-colors', off ? 'bg-transparent' : 'bg-foreground')}>
            <span className={cn(
              'absolute top-[3px] left-[3px] w-4 h-4 transition-transform',
              off ? 'bg-foreground' : 'bg-background translate-x-5',
            )} />
          </span>
        </span>
      </button>
    </section>
  );
}
