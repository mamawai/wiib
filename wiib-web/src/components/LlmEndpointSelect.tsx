import { Link } from 'react-router-dom';
import { cn } from '../lib/utils';
import type { LlmEndpointView } from '../types';

/**
 * 端点下拉：全站需要"选模型"的地方（交易员 / 复盘教练 / 对话用途绑定）都用它，
 * 选项来自 AI 页「模型配置」维护的端点库；value=null 表示"跟随默认端点"。
 * 端点库为空时不渲染 select，给一条去配置的链接——没得选的下拉只会让人困惑。
 */
export function LlmEndpointSelect({
  endpoints, value, onChange, followLabel, className, disabled,
}: {
  endpoints: LlmEndpointView[];
  value: number | null;
  onChange: (id: number | null) => void;
  /** 空值选项的完整文案；不传=「跟随默认（默认端点名 · 模型）」 */
  followLabel?: string;
  className?: string;
  disabled?: boolean;
}) {
  if (endpoints.length === 0) {
    return (
      <span className={cn('text-xs text-muted-foreground', className)}>
        还没有可用的模型端点，
        <Link to="/ai?tab=config" className="text-primary font-bold hover:underline">去 AI 页模型配置里添加</Link>
      </span>
    );
  }
  const dft = endpoints.find(e => e.isDefault) ?? endpoints[0];
  return (
    <select
      value={value == null ? '' : String(value)}
      onChange={e => onChange(e.target.value === '' ? null : Number(e.target.value))}
      disabled={disabled}
      className={cn('h-9 rounded-lg border border-border bg-card-2 px-2.5 text-xs font-bold disabled:opacity-60', className)}
    >
      <option value="">{followLabel ?? `跟随默认（${dft.name} · ${dft.model}）`}</option>
      {endpoints.map(e => (
        <option key={e.id} value={String(e.id)}>
          {e.name} · {e.model}{e.isDefault ? '（默认）' : ''}
        </option>
      ))}
    </select>
  );
}
