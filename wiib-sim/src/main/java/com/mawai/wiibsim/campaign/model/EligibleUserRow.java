package com.mawai.wiibsim.campaign.model;

import lombok.Data;

/** 参与积分与榜单的用户。机器人与管理员已在 SQL 里排除 */
@Data
public class EligibleUserRow {

    private Long userId;

    private String username;

    /** null = 邀请码注册用户：算分、上榜，但收不到 LDC，不参与分配 */
    private String linuxDoId;

    /** 能否领取 LDC */
    public boolean claimable() {
        return linuxDoId != null && !linuxDoId.isBlank();
    }
}
