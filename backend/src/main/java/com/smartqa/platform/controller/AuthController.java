package com.smartqa.platform.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.smartqa.platform.common.BusinessException;
import com.smartqa.platform.common.Result;
import com.smartqa.platform.dto.LoginDTO;
import com.smartqa.platform.dto.RegisterDTO;
import com.smartqa.platform.entity.SysUser;
import com.smartqa.platform.service.SysUserService;
import com.smartqa.platform.vo.LoginVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 身份认证接口（学生 / 教师统一入口）。
 *
 * <p>⚠️ 本期官方范围无注册接口（DEV_SPECIFICATION.md 明确「本期不实现注册」）。
 * {@code /api/auth/register} 为成员 C 本地分支 {@code feature/c-register} 的扩展，
 * 未经组长更新规范前不得合并到 dev。</p>
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

    /**
     * 注册（本地分支扩展，非官方范围）。
     *
     * <p>设计要点：</p>
     * <ul>
     *   <li>不接受 role 入参，一律落 STUDENT —— 防止自助注册教师账号越权进入教师后台；</li>
     *   <li>用户名查重先走 SELECT 给出友好提示，再靠数据库 UNIQUE 约束兜底并发；</li>
     *   <li>注册成功即登录并返回 LoginVO，前端免二次登录。</li>
     * </ul>
     */
    @PostMapping("/register")
    @Operation(summary = "用户注册（仅学生，本地分支扩展接口）")
    public Result<LoginVO> register(@Valid @RequestBody RegisterDTO dto) {
        if (userService.getByUsername(dto.getUsername()) != null) {
            throw new BusinessException("用户名已存在");
        }

        SysUser user = SysUser.builder()
                .username(dto.getUsername().trim())
                .password(passwordEncoder.encode(dto.getPassword()))
                .nickname(dto.getNickname().trim())
                .role("STUDENT")
                .avatarUrl("")
                .build();

        try {
            userService.save(user);
        } catch (DuplicateKeyException e) {
            // 并发下两个请求同时通过查重：由 sys_user.username 的 UNIQUE 约束兜底
            throw new BusinessException("用户名已存在");
        }

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
