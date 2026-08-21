/**
 * 思考档位的快捷填充芯片：空串=不传，与后端 normalizeEffort 的"留空一律 null"对齐。
 * 输入框才是真值，这里只是常见值的快捷键——各家档位名字自己定（xhigh/minimal…），不是白名单。
 * 用户端（BYOK 端点表单）与管理端（ai_runtime_config）共用这一份。
 *
 * 空串那条没有 label：它显示的是「默认」这个说法本身，属文案要随语言变，
 * 存在模块级常量里切了语言也不会动，所以留给渲染方现给。其余几条是各家协议的原样档位名，不翻。
 */
export const EFFORT_PRESETS: { value: string; label?: string }[] = [
  { value: '' },
  { value: 'none', label: 'none' },
  { value: 'low', label: 'low' },
  { value: 'medium', label: 'medium' },
  { value: 'high', label: 'high' },
];
