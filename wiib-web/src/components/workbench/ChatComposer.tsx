import { useCallback, useRef, useState } from 'react';
import { Send } from 'lucide-react';
import { HUB_NAME } from './chatView';

/** 输入区上方的快捷提问（点击直发）：行情/新闻/交易员/深研判四能力各一条 */
const SUGGESTS = ['BTC 现在的市场结构怎么样？', '最近有什么值得注意的加密新闻？', '我的 AI 交易员最近表现如何？', '对 ETH 做一次深度研判'];

/**
 * 输入区：快捷提问 + 多行自适应输入 + 状态行。
 * 草稿留在输入区自己身上：敲字只重渲染这一小块，不带着上面整条消息列表一起重画。
 */
export function ChatComposer({ loading, onSend }: {
  loading: boolean;
  onSend: (text: string) => void;
}) {
  const [input, setInput] = useState('');
  const inputRef = useRef<HTMLTextAreaElement>(null);

  const submit = useCallback((text?: string) => {
    const msg = (text ?? input).trim();
    if (!msg) return;
    setInput('');
    if (inputRef.current) inputRef.current.style.height = 'auto';
    onSend(msg);
  }, [input, onSend]);

  return (
    // 底部 padding 避让 home indicator：移动端面板是 inset-0 铺满视口的（ChatDock 只配了
    // pt 那侧的安全区），10px 顶不住 34px 手势条。用 max() 而不是相加——手势条那块本身就是留白。
    // md 起固定回 10px：env() 是视口级的，iPad 上不贴底的浮窗也会拿到 34px，那是多余的
    <div className="border-t border-border px-3 pt-2 pb-[max(0.625rem,env(safe-area-inset-bottom))] md:pb-2.5 shrink-0 space-y-2">
      <div className="flex gap-1.5 overflow-x-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden">
        {SUGGESTS.map(q => (
          <button
            key={q}
            onClick={() => submit(q)}
            className="shrink-0 border border-border rounded-full px-2.5 py-1 text-[11px] font-medium text-muted-foreground hover:text-foreground hover:bg-surface-hover transition-colors"
          >
            {q}
          </button>
        ))}
      </div>
      <div className="flex items-end gap-2 rounded-xl border border-border bg-card-2 px-3 py-2 transition-colors focus-within:border-primary/50">
        <textarea
          ref={inputRef}
          rows={1}
          value={input}
          onChange={e => setInput(e.target.value)}
          onInput={e => {
            const t = e.currentTarget;
            t.style.height = 'auto';
            t.style.height = `${Math.min(t.scrollHeight, 96)}px`;
          }}
          onKeyDown={e => {
            if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
              e.preventDefault();
              submit();
            }
          }}
          placeholder={loading ? `${HUB_NAME} 工作中——可继续发消息插话` : '问行情、查新闻、看你的交易员…'}
          className="flex-1 bg-transparent text-sm leading-relaxed resize-none max-h-24 focus:outline-none placeholder:text-muted-foreground/60"
        />
        <button
          onClick={() => submit()}
          disabled={!input.trim()}
          className="w-8 h-8 rounded-lg bg-primary text-primary-foreground flex items-center justify-center shrink-0 hover:brightness-105 active:scale-95 transition-all disabled:opacity-40 disabled:active:scale-100"
          aria-label="发送"
        >
          <Send className="w-4 h-4" />
        </button>
      </div>
      <div className="flex justify-between px-0.5 text-[10px] text-muted-foreground/60">
        <span>Enter 发送 · Shift+Enter 换行</span>
        <span className="num">{loading ? `${HUB_NAME} 工作中 · 可直接插话` : `${HUB_NAME} 就绪`}</span>
      </div>
    </div>
  );
}
