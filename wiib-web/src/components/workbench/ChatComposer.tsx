import { useCallback, useLayoutEffect, useRef, useState } from 'react';
import { Send } from 'lucide-react';
import { chatStore } from './chatStore';
import { HUB_NAME } from './chatView';

/** 输入区上方的快捷提问（点击直发）：行情/新闻/交易员/深研判四能力各一条 */
const SUGGESTS = ['BTC 现在的市场结构怎么样？', '最近有什么值得注意的加密新闻？', '我的 AI 交易员最近表现如何？', '对 ETH 做一次深度研判'];

/**
 * 输入区：快捷提问 + 多行自适应输入 + 状态行。
 * <p>
 * 敲字只动这一小块的本地 state，不带着上面整条消息列表重画；
 * 同时同步一份到 store —— 关面板会把整个面板卸载，只留本地 state 的话草稿就没了。
 */
export function ChatComposer({ loading, onSend }: {
  loading: boolean;
  onSend: (text: string) => void;
}) {
  const [input, setInput] = useState(chatStore.getDraft);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  /** 内容撑到哪就多高，最多 96px（rows=1 是起始高度，靠这个函数往上长） */
  const fitHeight = (el: HTMLTextAreaElement) => {
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 96)}px`;
  };

  // 挂载时把恢复回来的草稿撑开：只灌 value 不调高度的话，多行草稿回来只剩一行
  useLayoutEffect(() => {
    if (inputRef.current && inputRef.current.value) fitHeight(inputRef.current);
  }, []);

  const edit = useCallback((text: string) => {
    setInput(text);
    chatStore.setDraft(text);
  }, []);

  const submit = useCallback((text?: string) => {
    const msg = (text ?? input).trim();
    if (!msg) return;
    edit('');
    if (inputRef.current) inputRef.current.style.height = 'auto';
    onSend(msg);
  }, [input, edit, onSend]);

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
          onChange={e => edit(e.target.value)}
          onInput={e => fitHeight(e.currentTarget)}
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
