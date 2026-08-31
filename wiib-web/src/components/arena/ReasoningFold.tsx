import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronDown, ChevronUp } from 'lucide-react';
import { Markdown } from '../Markdown';

/** 结论分段标记 [BTCUSDT]（独占一行），与后端 ReviewMaterialAssembler.SEGMENT_TAG 同一形状 */
const SEG_LINE = /^\s*\[([A-Z0-9]{2,20})\]\s*$/;

interface Block {
  symbol: string | null;
  body: string;
}

/** 按分段标记行切块：标记前的引子（行情铺垫/总评）归 symbol=null 块；无标记时整篇一块 */
function splitBlocks(text: string): Block[] {
  const blocks: Block[] = [];
  let symbol: string | null = null;
  let buf: string[] = [];
  for (const line of text.split('\n')) {
    const m = SEG_LINE.exec(line);
    if (m) {
      if (buf.join('').trim()) blocks.push({ symbol, body: buf.join('\n') });
      symbol = m[1];
      buf = [];
    } else {
      buf.push(line);
    }
  }
  if (buf.join('').trim()) blocks.push({ symbol, body: buf.join('\n') });
  return blocks;
}

/** 长文折叠：默认一行纯文本预览，展开成 markdown——时间线卡、交易记录卡、手机上的笔记块同一套阅读节奏 */
export function ReasoningFold({ reasoning }: { reasoning: string | null }) {
  const { t } = useTranslation(['ai', 'common']);
  const [open, setOpen] = useState(false);
  const text = reasoning?.trim() || '';
  if (!text) return null;
  // 折叠预览是纯文本，去掉 markdown 符号免得满屏井号
  const preview = text.replace(/[#*`]/g, '').replace(/\s+/g, ' ').slice(0, 120) + (text.length > 120 ? '…' : '');
  const blocks = splitBlocks(text);
  const segmented = blocks.some(b => b.symbol != null);
  return (
    <div className="text-xs leading-relaxed text-foreground/90">
      {open ? (
        segmented ? (
          <div className="space-y-1.5">
            {blocks.map((b, i) => (
              <div key={i}>
                {b.symbol != null && (
                  <span className="inline-flex items-center num text-[10px] font-bold px-1.5 py-0.5 mb-0.5 rounded border border-border bg-card-2 text-foreground/80">
                    {b.symbol}
                  </span>
                )}
                <Markdown content={b.body} />
              </div>
            ))}
          </div>
        ) : (
          <Markdown content={text} />
        )
      ) : (
        <p>{preview}</p>
      )}
      {text.length > 120 && (
        <button onClick={() => setOpen(!open)} className="mt-1 text-[10px] font-bold text-primary flex items-center gap-0.5">
          {open ? <>{t('common:collapse')} <ChevronUp className="w-3 h-3" /></> : <>{t('detail.expandFull')} <ChevronDown className="w-3 h-3" /></>}
        </button>
      )}
    </div>
  );
}
