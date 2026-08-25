import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronDown, ChevronUp } from 'lucide-react';
import { Markdown } from '../Markdown';

/** 长文折叠：默认一行纯文本预览，展开成 markdown——时间线卡、交易记录卡、手机上的笔记块同一套阅读节奏 */
export function ReasoningFold({ reasoning }: { reasoning: string | null }) {
  const { t } = useTranslation(['ai', 'common']);
  const [open, setOpen] = useState(false);
  const text = reasoning?.trim() || '';
  if (!text) return null;
  // 折叠预览是纯文本，去掉 markdown 符号免得满屏井号
  const preview = text.replace(/[#*`]/g, '').replace(/\s+/g, ' ').slice(0, 120) + (text.length > 120 ? '…' : '');
  return (
    <div className="text-xs leading-relaxed text-foreground/90">
      {open ? <Markdown content={text} /> : <p>{preview}</p>}
      {text.length > 120 && (
        <button onClick={() => setOpen(!open)} className="mt-1 text-[10px] font-bold text-primary flex items-center gap-0.5">
          {open ? <>{t('common:collapse')} <ChevronUp className="w-3 h-3" /></> : <>{t('detail.expandFull')} <ChevronDown className="w-3 h-3" /></>}
        </button>
      )}
    </div>
  );
}
