package com.mawai.wiibsim.campaign;

import com.mawai.wiibcommon.annotation.CurrentUserId;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibsim.campaign.entity.Campaign;
import com.mawai.wiibsim.campaign.model.VoteBoard;
import com.mawai.wiibsim.campaign.service.CampaignCheckinService;
import com.mawai.wiibsim.campaign.service.CampaignService;
import com.mawai.wiibsim.campaign.service.CampaignVoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    private final CampaignVoteService voteService;

    @Data
    public static class VoteRequest {
        /** BTCUSDT / XAUUSDT */
        private String symbol;
        /** UP / DOWN */
        private String direction;
    }

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

    @PostMapping("/vote")
    @Operation(summary = "每日多空投票（每标的多空二选一，UTC 0 点前）")
    public Result<Void> vote(@CurrentUserId Long userId, @RequestBody VoteRequest req) {
        voteService.vote(userId, req.getSymbol(), req.getDirection());
        return Result.ok(null);
    }

    @GetMapping("/vote/board")
    @Operation(summary = "今日投票看板（双方票数 + 我的票）")
    public Result<List<VoteBoard>> voteBoard(@CurrentUserId Long userId) {
        return Result.ok(voteService.board(userId));
    }
}
