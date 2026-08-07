import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';

/**
 * LLM 输出的 markdown 统一渲染：工作台对话与竞技场决策日志共用。
 * 模型爱用 #/##/**——裸文本一屏井号没法看；字号贴精密终端体系（正文 12px，标题只是加黑微升）。
 */
export function Markdown({ content }: { content: string }) {
  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      components={{
        p: (props) => <p className="leading-relaxed mb-1.5 last:mb-0" {...props} />,
        h1: (props) => <h4 className="text-xs font-black mt-2.5 mb-1 first:mt-0" {...props} />,
        h2: (props) => <h4 className="text-xs font-black mt-2.5 mb-1 first:mt-0" {...props} />,
        h3: (props) => <h5 className="text-[11px] font-black mt-2 mb-0.5 first:mt-0" {...props} />,
        h4: (props) => <h5 className="text-[11px] font-black mt-2 mb-0.5 first:mt-0" {...props} />,
        strong: (props) => <strong className="font-black" {...props} />,
        ul: (props) => <ul className="list-disc pl-4 space-y-0.5 mb-1.5" {...props} />,
        ol: (props) => <ol className="list-decimal pl-4 space-y-0.5 mb-1.5" {...props} />,
        blockquote: (props) => <blockquote className="border-l-2 border-border pl-2.5 text-muted-foreground mb-1.5" {...props} />,
        hr: () => <hr className="border-border my-2" />,
        code: (props) => <code className="px-1 py-0.5 rounded bg-muted text-[11px] font-mono" {...props} />,
        pre: (props) => <pre className="p-2.5 rounded-lg bg-muted text-[11px] font-mono overflow-x-auto mb-1.5" {...props} />,
        table: (props) => <div className="overflow-x-auto mb-1.5"><table className="text-[11px] border-collapse [&_th]:border [&_th]:px-2 [&_th]:py-1 [&_td]:border [&_td]:px-2 [&_td]:py-1" {...props} /></div>,
        a: (props) => <a className="text-primary underline" target="_blank" rel="noopener noreferrer" {...props} />,
      }}
    >
      {content}
    </ReactMarkdown>
  );
}
