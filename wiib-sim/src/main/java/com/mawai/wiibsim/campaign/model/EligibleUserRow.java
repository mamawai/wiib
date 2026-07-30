package com.mawai.wiibsim.campaign.model;

import lombok.Data;

/** 参与积分与榜单的用户。机器人与管理员已在 SQL 里排除 */
@Data
public class EligibleUserRow {

    private Long userId;

    private String username;

    /** 纯数字的 LinuxDo ID —— SQL 已保证，这里不会是 null 或非数字 */
    private String linuxDoId;

    /**
     * 能否领取 LDC。
     * <p>
     * 在现行"名单即纯数字账号"的规则下这个方法<b>恒为 true</b>（listEligibleUsers
     * 的正则已经把非数字全挡在外面）。留着不是为了现在过滤谁，而是给分配那一侧留个兜底：
     * 分发接口要的 user_id 必须是数字，真发钱之前再自证一次，比信任上游便宜。
     */
    public boolean claimable() {
        return linuxDoId != null && linuxDoId.matches("\\d+");
    }
}
