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
 * <p>登录与学生注册都是官方功能：注册只面向学生（{@code /api/auth/register}），
 * 服务端固定落 STUDENT 角色、不接受 role 入参，教师账号只由 {@code data.sql} 种子数据预置。
 * 请求与返回体契约见 {@code backend/README-成员B后端代码说明.md} §3.1 / §3.1.1，改动前必读。</p>
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
     * 学生注册。
     *
     * <p>以下三条是安全与数据约束，改动前先读 README §3.1.1：</p>
     * <ul>
     *   <li>不接受 role 入参，一律落 STUDENT —— 防止自助注册教师账号越权进入教师后台；</li>
     *   <li>用户名查重先走 SELECT 给出友好提示，再靠数据库 UNIQUE 约束兜底并发；</li>
     *   <li>注册成功即登录并返回 LoginVO，前端免二次登录。</li>
     * </ul>
     */
    @PostMapping("/register")
    @Operation(summary = "用户注册（仅学生）")
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

    /**
     * 退出登录（注销当前令牌的会话）。
     *
     * <p><b>为何必须有服务端接口</b>：sa-token 配置为 {@code timeout=7 天}（application.yml），
     * 若前端只清 {@code localStorage}，令牌在服务端仍然有效七天——公用电脑 / 令牌被复制 /
     * XSS 窃取后用户无任何手段主动吊销。本接口调 {@code StpUtil.logout()} 使服务端会话立即失效，
     * 拿着旧令牌再请求会回 401。</p>
     *
     * <p>注意：本接口<b>不在</b>鉴权白名单里（必须已登录才调得到）。已过期再调会得到 401，
     * 前端应将“登出”视为幂等：无论接口成败都要清本地令牌并跳登录页。</p>
     */
    @PostMapping("/logout")
    @Operation(summary = "退出登录（注销当前令牌）")
    public Result<Void> logout() {
        StpUtil.logout();
        return Result.success();
    }
}
