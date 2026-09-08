import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
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

/**
 * 长文折叠：默认两行纯文本，展开成 markdown。展开/收起与「过程」并排在同一行链接里。
 * segmentable=false 关掉币种分段渲染：段标题（如英文 [REVIEW]）与币码同形状时用得上。
 */
export function ReasoningFold({ reasoning, segmentable = true, children }: {
  reasoning: string | null;
  segmentable?: boolean;
  /** 挂在展开/收起旁边的链接（决策卡的「过程」） */
  children?: ReactNode;
}) {
  const { t } = useTranslation(['ai', 'common']);
  const [open, setOpen] = useState(false);
  const text = reasoning?.trim() || '';
  if (!text && !children) return null;
  // 折叠态是纯文本，去掉 markdown 符号免得满屏井号；截到几行交给 line-clamp
  const preview = text.replace(/[#*`]/g, '').replace(/\s+/g, ' ');
  const blocks = segmentable ? splitBlocks(text) : [];
  const segmented = blocks.some(b => b.symbol != null);
  return (
    <>
      {text && (
        <div className="text-[15px] leading-[1.7] mt-3 max-w-[80ch]">
          {!open ? <p className="line-clamp-2">{preview}</p>
            : segmented ? (
              <div className="space-y-2">
                {blocks.map((b, i) => (
                  <div key={i}>
                    {b.symbol != null && <span className="chip mute num mb-1">{b.symbol}</span>}
                    <Markdown content={b.body} />
                  </div>
                ))}
              </div>
            ) : <Markdown content={text} />}
        </div>
      )}
      <div className="flex gap-4 mt-2 text-[13px]">
        {text && (
          <button type="button" onClick={() => setOpen(!open)} className="underline underline-offset-[3px]">
            {open ? t('common:collapse') : t('detail.expandFull')}
          </button>
        )}
        {children}
      </div>
    </>
  );
}
