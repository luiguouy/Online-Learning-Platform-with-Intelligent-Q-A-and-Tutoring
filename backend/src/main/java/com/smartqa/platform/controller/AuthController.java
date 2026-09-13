package com.smartqa.platform.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.smartqa.platform.common.BusinessException;
import com.smartqa.platform.common.Result;
import com.smartqa.platform.dto.LoginDTO;
import com.smartqa.platform.entity.SysUser;
import com.smartqa.platform.service.SysUserService;
import com.smartqa.platform.vo.LoginVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 身份认证接口（学生 / 教师统一入口）。
 *
 * <p>⚠️ 本期无注册接口，不要添加 {@code /api/auth/register}。</p>
 *
 * @author 成员 B
 */
@Tag(name = "身份认证模块")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final SysUserService userService;

    /** 由 {@link com.smartqa.platform.config.PasswordEncoderConfig} 提供，便于调整强度或替换算法 */
    private final PasswordEncoder passwordEncoder;

    @PostMapping("/login")
    @Operation(summary = "用户登录（学生/教师统一入口）")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        SysUser user = userService.getByUsername(dto.getUsername());

        // 用户不存在与密码错误返回同一句提示，避免账号枚举
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }

        // Sa-Token 登录注标：后续 /api/qa/** 走 checkLogin()，/api/teacher/** 走 checkRole("TEACHER")
        StpUtil.login(user.getId());
        StpUtil.getSession().set("role", user.getRole());

        LoginVO vo = LoginVO.builder()
                .token(StpUtil.getTokenValue())
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .role(user.getRole())
                .avatarUrl(user.getAvatarUrl())
                .build();

        return Result.success(vo);
    }
}
