/**
 * 思考档位的快捷填充芯片：空串=不传，与后端 normalizeEffort 的"留空一律 null"对齐。
 * 输入框才是真值，这里只是常见值的快捷键——各家档位名字自己定（xhigh/minimal…），不是白名单。
 * 用户端（BYOK 端点表单）与管理端（ai_runtime_config）共用这一份。
 */
export const EFFORT_PRESETS: { value: string; label: string }[] = [
  { value: '', label: '默认' },
  { value: 'none', label: 'none' },
  { value: 'low', label: 'low' },
  { value: 'medium', label: 'medium' },
  { value: 'high', label: 'high' },
];
