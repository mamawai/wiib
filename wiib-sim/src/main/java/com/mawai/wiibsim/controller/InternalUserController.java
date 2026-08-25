package com.mawai.wiibsim.controller;

import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibsim.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户属性 internal API（sim 暴露给 quant 调用）。
 * <p>quant 的 agent 要按用户语言出提示词与回答，但 user 表在 sim 这边，quant 不直连它的库表——
 * 与 {@link BehaviorDataController} 同一条通道，鉴权走 {@code InternalApiFilter} 的 X-Internal-Token。
 */
@RestController
@RequestMapping("/internal/user")
@RequiredArgsConstructor
public class InternalUserController {

    private final UserMapper userMapper;

    /**
     * AI 产出语言码（zh/en）。用户不存在或没设过一律返回空串——
     * quant 侧 {@code AgentLang.of} 认不出即回落中文，这里不需要再造一种错误形态。
     */
    @GetMapping("/{userId}/lang")
    public String getLang(@PathVariable Long userId) {
        User user = userMapper.selectById(userId);
        return user == null || user.getLang() == null ? "" : user.getLang();
    }
}
