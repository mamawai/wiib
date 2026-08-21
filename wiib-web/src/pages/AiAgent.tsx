import { useTranslation } from 'react-i18next';
import { ModelConfig } from '../components/ModelConfig';
import { KeyRound } from 'lucide-react';

/**
 * 「配置」页：只剩 BYOK 模型端点这一件事，所以没有 tab 壳。
 * <p>
 * 原来同页还有「行为分析」tab，已搬进对话——它现在是 analyze_my_behavior 工具，
 * 报告以卡片形式出现在对话流里（见 workbench/BehaviorReportCard），
 * 顺带把平台那个 behavior 功能位一起退休了，这次调用记在用户自己的 key 上。
 * 「市场研判」tab 更早下线（研判只在对话里触发时看）。
 */
export function AiAgent() {
  const { t } = useTranslation('ai');

  return (
    // 内容是单列窄块，整页居中一个 3xl 列，不然全贴左边
    <div className="page-shell p-4 md:p-6">
      <div className="max-w-3xl mx-auto space-y-4">
        <div className="flex items-center gap-2 text-sm font-black">
          <KeyRound className="w-4 h-4 text-primary" />
          {t('tab.config')}
        </div>
        {/* 模型配置（BYOK）：对话 agent + 交易员 agent 两份端点 */}
        <ModelConfig />
      </div>
    </div>
  );
}
