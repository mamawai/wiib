package com.mawai.wiibservice.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.mawai.wiibcommon.dto.UserDTO;
import com.mawai.wiibcommon.entity.User;
import com.mawai.wiibcommon.enums.ErrorCode;
import com.mawai.wiibcommon.exception.BizException;
import com.mawai.wiibservice.service.AuthService;
import com.mawai.wiibservice.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 认证服务实现
 * LinuxDo OAuth 2.0登录流程
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserService userService;

    @Value("${linuxdo.client-id}")
    private String clientId;

    @Value("${linuxdo.client-secret}")
    private String clientSecret;

    @Value("${linuxdo.redirect-uri}")
    private String redirectUri;

    // LinuxDo OAuth接口地址
    private static final String LINUXDO_AUTH_URL = "https://connect.linux.do/oauth/authorize";
    private static final String LINUXDO_TOKEN_URL = "https://connect.linux.do/oauth/token";
    private static final String LINUXDO_USER_URL = "https://connect.linux.do/api/user";

    /**
     * 获取LinuxDo授权URL
     */
    @Override
    public String getLinuxDoAuthUrl() {
        return LINUXDO_AUTH_URL + "?client_id=" + clientId
                + "&redirect_uri=" + redirectUri
                + "&response_type=code"
                + "&scope=read";
    }

    /**
     * 处理LinuxDo回调，完成登录
     * 流程：code换token -> token获取用户信息 -> 创建/更新用户 -> 登录
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public String handleLinuxDoCallback(String code) {
        try {
            // 1. 用code换取access_token
            String accessToken = exchangeToken(code);

            // 2. 用token获取用户信息
            JSONObject userInfo = getUserInfo(accessToken);

            String linuxDoId = userInfo.getStr("id");
            String username = userInfo.getStr("username");
            String avatar = userInfo.getStr("avatar_url");

            // 3. 查找或创建用户
            User user = userService.findByLinuxDoId(linuxDoId);
            if (user == null) {
                // 首次登录，创建用户并赠送10万初始资金
                user = new User();
                user.setLinuxDoId(linuxDoId);
                user.setUsername(username);
                user.setAvatar(avatar);
                user.setBalance(new BigDecimal("100000"));
                userService.save(user);
                log.info("新用户注册: {} LinuxDoId={}", username, linuxDoId);
            } else {
                // 更新用户信息
                user.setUsername(username);
                user.setAvatar(avatar);
                userService.updateById(user);
            }

            // 4. 登录（Sa-Token）
            StpUtil.login(user.getId());
            String token = StpUtil.getTokenValue();

            log.info("用户登录成功: {} UserId={}", username, user.getId());

            return token;
        } catch (Exception e) {
            log.error("LinuxDo登录失败", e);
            throw new BizException(ErrorCode.SYSTEM_ERROR.getCode(), "登录失败: " + e.getMessage());
        }
    }

    /**
     * 获取当前登录用户信息
     */
    @Override
    public UserDTO getCurrentUser() {
        Long userId = StpUtil.getLoginIdAsLong();
        return userService.getUserPortfolio(userId);
    }

    /**
     * 退出登录
     */
    @Override
    public void logout() {
        StpUtil.logout();
        log.info("用户退出登录");
    }

    /**
     * 用授权码换取access_token
     */
    private String exchangeToken(String code) {
        String response = HttpRequest.post(LINUXDO_TOKEN_URL)
                .form("grant_type", "authorization_code")
                .form("code", code)
                .form("client_id", clientId)
                .form("client_secret", clientSecret)
                .form("redirect_uri", redirectUri)
                .execute()
                .body();

        JSONObject json = JSONUtil.parseObj(response);
        if (json.getStr("access_token") == null) {
            throw new BizException("获取access_token失败: " + response);
        }

        return json.getStr("access_token");
    }

    /**
     * 用access_token获取用户信息
     */
    private JSONObject getUserInfo(String accessToken) {
        String response = HttpRequest.get(LINUXDO_USER_URL)
                .header("Authorization", "Bearer " + accessToken)
                .execute()
                .body();

        JSONObject json = JSONUtil.parseObj(response);
        if (json.getStr("id") == null) {
            throw new BizException("获取用户信息失败: " + response);
        }

        return json;
    }
}
