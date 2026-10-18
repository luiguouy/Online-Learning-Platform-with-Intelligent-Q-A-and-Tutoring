package com.smartqa.platform.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smartqa.platform.common.BusinessException;
import com.smartqa.platform.common.Result;
import com.smartqa.platform.dto.UpdateProfileDTO;
import com.smartqa.platform.entity.QaRecord;
import com.smartqa.platform.entity.QaSession;
import com.smartqa.platform.entity.SysUser;
import com.smartqa.platform.service.QaRecordService;
import com.smartqa.platform.service.QaSessionService;
import com.smartqa.platform.service.SysUserService;
import com.smartqa.platform.vo.UserProfileVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 用户主页与个人资料控制器。
 */
@Slf4j
@Tag(name = "用户个人资料")
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private static final Pattern IMAGE_EXT = Pattern.compile("(?i).+\\.(jpg|jpeg|png|gif|webp)$");
    private static final long MAX_AVATAR_BYTES = 5 * 1024 * 1024; // 5MB

    @Value("${file.upload-dir}")
    private String uploadDir;

    private final SysUserService userService;
    private final QaSessionService qaSessionService;
    private final QaRecordService qaRecordService;

    /**
     * 获取当前登录用户个人资料及学情统计
     */
    @GetMapping("/profile")
    @Operation(summary = "获取当前登录用户资料")
    public Result<UserProfileVO> getProfile() {
        Long userId = StpUtil.getLoginIdAsLong();
        SysUser user = userService.getById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }

        long sessionCount = qaSessionService.count(Wrappers.<QaSession>lambdaQuery()
                .eq(QaSession::getUserId, userId));
        long recordCount = qaRecordService.count(Wrappers.<QaRecord>lambdaQuery()
                .eq(QaRecord::getUserId, userId));

        UserProfileVO vo = UserProfileVO.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .role(user.getRole())
                .avatarUrl(user.getAvatarUrl())
                .bio(StringUtils.hasText(user.getBio()) ? user.getBio() : "")
                .createdAt(user.getCreatedAt())
                .sessionCount(sessionCount)
                .recordCount(recordCount)
                .build();

        return Result.success(vo);
    }

    /**
     * 修改当前登录用户个人资料
     */
    @PutMapping("/profile")
    @Operation(summary = "修改当前登录用户资料")
    public Result<UserProfileVO> updateProfile(@Valid @RequestBody UpdateProfileDTO dto) {
        Long userId = StpUtil.getLoginIdAsLong();
        SysUser user = userService.getById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }

        user.setNickname(dto.getNickname().trim());
        if (dto.getAvatarUrl() != null) {
            user.setAvatarUrl(dto.getAvatarUrl().trim());
        }
        if (dto.getBio() != null) {
            user.setBio(dto.getBio().trim());
        }
        user.setUpdatedAt(LocalDateTime.now());
        userService.updateById(user);

        log.info("[User] 用户个人资料更新成功, userId={}, nickname={}", userId, user.getNickname());
        return getProfile();
    }

    /**
     * 上传头像图片
     */
    @PostMapping("/avatar")
    @Operation(summary = "上传用户头像图片")
    public Result<Map<String, String>> uploadAvatar(@RequestParam("file") MultipartFile file) {
        StpUtil.checkLogin();

        if (file == null || file.isEmpty()) {
            throw new BusinessException("请选择要上传的头像图片");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new BusinessException("头像图片不能超过 5MB");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null || !IMAGE_EXT.matcher(originalName.toLowerCase(Locale.ROOT)).matches()) {
            throw new BusinessException("仅支持 JPG、PNG、WEBP、GIF 格式的图片文件");
        }

        String ext = "";
        int dotIdx = originalName.lastIndexOf('.');
        if (dotIdx >= 0) {
            ext = originalName.substring(dotIdx).toLowerCase(Locale.ROOT);
        }

        String uniqueFileName = "avatar_" + UUID.randomUUID().toString().replace("-", "") + ext;
        File avatarDir = new File(uploadDir, "avatars");
        if (!avatarDir.exists() && !avatarDir.mkdirs()) {
            log.error("[User] 创建头像目录失败: {}", avatarDir.getAbsolutePath());
            throw new BusinessException("服务器内部存储初始化失败");
        }

        File targetFile = new File(avatarDir, uniqueFileName);
        try {
            file.transferTo(targetFile);
        } catch (IOException e) {
            log.error("[User] 头像文件保存失败", e);
            throw new BusinessException("头像文件保存失败，请稍后重试");
        }

        String avatarUrl = "/uploads/avatars/" + uniqueFileName;
        log.info("[User] 头像上传成功: {}", avatarUrl);

        return Result.success(Map.of("avatarUrl", avatarUrl));
    }
}
