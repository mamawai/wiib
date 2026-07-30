package com.mawai.wiibsim.campaign.model;

import lombok.Data;

/** userId → 计数，各类"次数"查询的通用返回行 */
@Data
public class CountRow {

    private Long userId;

    private Integer cnt;
}
