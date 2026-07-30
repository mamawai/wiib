package com.mawai.wiibsim.campaign;

import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.service.CampaignCheckinService;
import com.mawai.wiibsim.campaign.service.CampaignService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * LDC 瓜分活动接口。整体需登录：/api/campaign/** 不在 SaTokenConfig 放行清单里，
 * 拦截器先挡一道，各接口的 @CurrentUserId 再取一次 loginId。
 */
@Tag(name = "LDC 瓜分活动")
@RestController
@RequestMapping("/api/campaign")
@RequiredArgsConstructor
public class CampaignController {

    private final CampaignService campaignService;
    private final CampaignCheckinService checkinService;

    @GetMapping("/current")
    @Operation(summary = "当前活动信息（无进行中活动返回 null）")
    public Result<Campaign> current() {
        return Result.ok(campaignService.current());
    }

    @PostMapping("/checkin")
    @Operation(summary = "每日签到，返回签到后的最长连续天数")
    public Result<Integer> checkin(@CurrentUserId Long userId) {
        return Result.ok(checkinService.checkin(userId));
    }
}
