import { BookOpen } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Card, CardContent, CardHeader, CardTitle } from './ui/card';

/**
 * 教学条目：概念名 + 白话解释（结合本站玩法），按 交易基础 → 合约风险 → 玩法 → 账户 排布。
 * 数组在组件外拿不到 t，所以存词表 key 不存文案；key 写成字面量才 grep 得到。
 */
const FAQ_ITEMS: { id: string; qKey: string; aKey: string }[] = [
  // ---- 交易基础 ----
  { id: 'spotVsFutures', qKey: 'faq.spotVsFutures.q', aKey: 'faq.spotVsFutures.a' },
  { id: 'leverage', qKey: 'faq.leverage.q', aKey: 'faq.leverage.a' },
  { id: 'crossVsIsolated', qKey: 'faq.crossVsIsolated.q', aKey: 'faq.crossVsIsolated.a' },
  // ---- 合约风险 ----
  { id: 'markPrice', qKey: 'faq.markPrice.q', aKey: 'faq.markPrice.a' },
  { id: 'liqPrice', qKey: 'faq.liqPrice.q', aKey: 'faq.liqPrice.a' },
  { id: 'mmr', qKey: 'faq.mmr.q', aKey: 'faq.mmr.a' },
  { id: 'fundingRate', qKey: 'faq.fundingRate.q', aKey: 'faq.fundingRate.a' },
  { id: 'tpSl', qKey: 'faq.tpSl.q', aKey: 'faq.tpSl.a' },
  { id: 'doubleLiq', qKey: 'faq.doubleLiq.q', aKey: 'faq.doubleLiq.a' },
  { id: 'bankrupt', qKey: 'faq.bankrupt.q', aKey: 'faq.bankrupt.a' },
  // ---- 玩法 ----
  { id: 'prediction', qKey: 'faq.prediction.q', aKey: 'faq.prediction.a' },
  { id: 'dailyVote', qKey: 'faq.dailyVote.q', aKey: 'faq.dailyVote.a' },
  { id: 'wallets', qKey: 'faq.wallets.q', aKey: 'faq.wallets.a' },
  { id: 'coupon', qKey: 'faq.coupon.q', aKey: 'faq.coupon.a' },
  { id: 'campaign', qKey: 'faq.campaign.q', aKey: 'faq.campaign.a' },
  // ---- 账户 ----
  { id: 'resetAccount', qKey: 'faq.resetAccount.q', aKey: 'faq.resetAccount.a' },
];

/** 首页 FAQ：新手教学手风琴（原生 details，无 JS 状态） */
export function HomeFaq() {
  const { t } = useTranslation('home');

  return (
    <Card>
      <CardHeader className="pb-2">
        <CardTitle className="flex items-center gap-2">
          <BookOpen className="w-3.5 h-3.5 text-primary" />
          {t('faq.title')}
        </CardTitle>
      </CardHeader>
      <CardContent className="pt-0">
        <div className="divide-y divide-border/60">
          {FAQ_ITEMS.map(item => (
            <details key={item.id} className="group py-1">
              <summary className="flex items-center gap-2 py-2 text-sm font-semibold cursor-pointer list-none select-none hover:text-primary transition-colors [&::-webkit-details-marker]:hidden">
                <span className="text-primary text-xs transition-transform group-open:rotate-90">▸</span>
                {t(item.qKey)}
              </summary>
              <p className="pb-3 pl-5 text-[13px] leading-relaxed text-muted-foreground">{t(item.aKey)}</p>
            </details>
          ))}
        </div>
      </CardContent>
    </Card>
  );
}
