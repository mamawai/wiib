package com.mawai.wiibservice.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.mawai.wiibcommon.dto.PositionDTO;
import com.mawai.wiibcommon.dto.UserDTO;
import com.mawai.wiibcommon.util.Result;
import com.mawai.wiibservice.service.PositionService;
import com.mawai.wiibservice.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户Controller
 */
@Tag(name = "用户接口")
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final PositionService positionService;

    @GetMapping("/portfolio")
    @Operation(summary = "获取用户资产概览")
    public Result<UserDTO> getUserPortfolio() {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(userService.getUserPortfolio(userId));
    }

    @GetMapping("/positions")
    @Operation(summary = "获取用户持仓列表")
    public Result<List<PositionDTO>> getUserPositions() {
        Long userId = StpUtil.getLoginIdAsLong();
        return Result.ok(positionService.getUserPositions(userId));
    }
}
