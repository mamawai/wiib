package com.mawai.wiibquant.agent.learning;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NoteBudget.clip：预算内原样；超限退到句读收笔；段标题行无句读会被整段收掉；
 * 半角小数点不算句读；无句读或句读太靠前时硬截断，且不劈代理对。
 */
class NoteBudgetTest {

    @Test
    void 预算内原样返回() {
        assertThat(NoteBudget.clip("一句话。", 10)).isEqualTo("一句话。");
    }

    @Test
    void 超限退到最后一个句读收笔() {
        // 24 字上限切在尾段正文中间：回退到第 13 字的句号收笔，不吐半句话
        String text = "开头一句话。第二句话也在。【前车之鉴】后面正文足够长足够长";
        assertThat(NoteBudget.clip(text, 24)).isEqualTo("开头一句话。第二句话也在。");
    }

    @Test
    void 只剩标题没正文的尾段整段收掉() {
        // 线上真实病形：截点落在段标题正后方——标题行不含句读，回退自然把光杆标题一起丢掉
        String text = "不学降门槛凑开仓——三笔全亏，不是方法。\n【前车之鉴】\nid=2 的触发链还没写完";
        String clipped = NoteBudget.clip(text, 24);
        assertThat(clipped).isEqualTo("不学降门槛凑开仓——三笔全亏，不是方法。");
        assertThat(clipped).doesNotContain("【前车之鉴】");
    }

    @Test
    void 半角小数点不算句读() {
        String text = "See the level 2463.39 held first time around. Then closed at 2500.75 up";
        int cap = text.indexOf("75 up") + 2;   // 截点让 2500. 的小数点落进回退扫描区
        assertThat(NoteBudget.clip(text, cap))
                .isEqualTo("See the level 2463.39 held first time around.");
    }

    @Test
    void 无句读硬截断到上限() {
        assertThat(NoteBudget.clip("长".repeat(30), 20)).isEqualTo("长".repeat(20));
    }

    @Test
    void 句读不足预算一半时硬截断() {
        // 句号在第 3 字、上限 20：回退到它会丢掉大半预算，不如硬切
        String text = "短句。" + "长".repeat(30);
        assertThat(NoteBudget.clip(text, 20)).hasSize(20);
    }

    @Test
    void 硬截断不劈代理对() {
        String text = "长".repeat(9) + "🐟🐟";   // 🐟 占两个 char，截点 10 落在代理对中间
        assertThat(NoteBudget.clip(text, 10)).isEqualTo("长".repeat(9));
    }
}
