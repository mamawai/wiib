import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

/**
 * 列表空态占位（圆形图标 + 文案）。
 *
 * i18n 约定（共享组件的样板）：text 收的是**已经翻译好的字符串**，不是 key。
 * 空态文案各页各说各的（"暂无持仓"/"该条件下暂无成交"…），归调用方业务域的词表；
 * 组件收 key 就得同时知道该查哪个 ns，等于把十来个域的命名空间全绑到这一个文件上。
 * 组件自带的兜底文案才归自己查，走 common。
 */
export function EmptyState({ icon, text }: { icon: ReactNode; text?: string }) {
  const { t } = useTranslation();
  return (
    <div className="p-12 text-center text-muted-foreground">
      <div className="w-12 h-12 mx-auto mb-3 rounded-lg border border-border bg-card-2 flex items-center justify-center">
        {icon}
      </div>
      {text ?? t('noData')}
    </div>
  );
}
