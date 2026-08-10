import { useEffect, useState, useSyncExternalStore } from 'react';
import { KeyRound, Loader2 } from 'lucide-react';
import { Dialog, DialogHeader, DialogContent, DialogFooter } from '../ui/dialog';
import { Button } from '../ui/button';
import { useToast } from '../ui/use-toast';
import { llmConfigApi } from '../../api';
import { chatStore } from './chatStore';
import { LlmEndpointForm, type LlmEndpointValue } from '../LlmEndpointForm';

interface Props {
  /** 已配置=球安静；未配置=挂警示灯，一进页面就知道这儿有东西要弄 */
  configured: boolean;
  keyTail?: string;
  /** 服务端最新值。引用必须稳定（Workbench 侧 useMemo），否则下面的 effect 每帧重置表单 */
  initial: LlmEndpointValue;
  onSaved: () => void;
}

export function LlmConfigBall({ configured, keyTail, initial, onSaved }: Props) {
  const { toast } = useToast();
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState<LlmEndpointValue>(initial);
  const [saving, setSaving] = useState(false);
  // 本项目的 store 是手写的外部 store（同 ChatPanel 的用法），没有 zustand 那套选择器
  const { needsConfig } = useSyncExternalStore(chatStore.subscribe, chatStore.getSnapshot);

  // 后端说"没配/配错"时自动把弹窗顶出来——否则用户只看到一行红字，
  // 得自己去猜右下角那个球是干什么的。顶出来之后立刻清标记，不然关掉会被反复顶开
  useEffect(() => {
    if (needsConfig) {
      setOpen(true);
      chatStore.clearNeedsConfig();
    }
  }, [needsConfig]);

  // 打开时用最新的服务端值重置：useState(initial) 只在首次挂载求值，
  // onSaved() 触发的 loadConfig() 更新了 config/keyTail，form 不会跟着变
  useEffect(() => {
    if (open) setForm(initial);
  }, [open, initial]);

  const save = async () => {
    setSaving(true);
    try {
      await llmConfigApi.save(form);
      setForm(f => ({ ...f, apiKey: '' }));   // 明文 key 用完就扔，不在前端内存里过夜
      toast('配置已保存', 'success');
      setOpen(false);
      onSaved();
    } catch (e) {
      toast((e as Error).message || '保存失败', 'error');
    } finally {
      setSaving(false);
    }
  };

  return (
    <>
      {/* bottom-20 是项目里避让移动端底部导航的既有写法；z-90 夹在 GuidedTour 之上、toast 之下 */}
      <button
        type="button"
        onClick={() => setOpen(true)}
        title={configured ? '模型配置' : '还没配置 API Key'}
        className="fixed right-4 bottom-20 md:bottom-6 z-[90] w-12 h-12 rounded-full pt-card
                   flex items-center justify-center hover:bg-surface-hover transition-colors"
      >
        <KeyRound className="w-5 h-5 text-primary" />
        {/* 没配 key 时右上角挂一盏警示灯。.led 是 6px 的灯珠（含尺寸和圆角），
            .led-warn 只换颜色——单独把 led-warn 加在 48px 的球上，
            只会把 pt-card 的背景整个刷成 warning 色 + 外发光 */}
        {!configured && <span className="led led-warn absolute top-1.5 right-1.5" />}
      </button>

      <Dialog open={open} onClose={() => setOpen(false)}>
        <DialogHeader>
          <h2 className="text-lg font-bold">对话模型配置</h2>
          <p className="text-xs text-muted-foreground mt-1">
            对话走你自己的端点，费用记在你的账上，平台不经手
          </p>
        </DialogHeader>
        <DialogContent>
          <LlmEndpointForm
            value={form}
            onChange={patch => setForm(f => ({ ...f, ...patch }))}
            exists={configured}
            keyTail={keyTail}
            withLightModel
            withReasoningEffort
            onDetect={() => llmConfigApi.listModels(form)}
            onTest={() => llmConfigApi.test(form)}
          />
        </DialogContent>
        <DialogFooter>
          <Button variant="ghost" size="sm" onClick={() => setOpen(false)}>取消</Button>
          <Button size="sm" disabled={saving} onClick={() => void save()}>
            {saving && <Loader2 className="w-3.5 h-3.5 animate-spin mr-1" />}
            保存
          </Button>
        </DialogFooter>
      </Dialog>
    </>
  );
}
