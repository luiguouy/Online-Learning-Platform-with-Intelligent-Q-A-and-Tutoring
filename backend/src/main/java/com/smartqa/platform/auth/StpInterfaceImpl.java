package com.smartqa.platform.auth;

import cn.dev33.satoken.stp.StpInterface;
import com.smartqa.platform.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Sa-Token 角色 / 权限数据源。
 *
 * <p>⚠️ 必须实现本类，否则 {@code StpUtil.checkRole()} 不知道任何用户的角色，
 * 教师端 {@code /api/teacher/**} 所有接口联调第一天就会被 403 拦死。</p>
 *
 * @author 成员 B
 */
@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private final SysUserService userService;

    /**
     * 本项目仅做角色级控制，权限点一律留空。
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return Collections.emptyList();
    }

    /**
     * 从数据库读取当前登录用户的角色列表。
     *
     * @return 单元素列表（TEACHER / STUDENT）；用户不存在或 ID 非法时返回空列表
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        if (loginId == null) {
            return Collections.emptyList();
        }
        try {
            return userService.listRoleCodes(Long.valueOf(loginId.toString()));
        } catch (NumberFormatException e) {
            return Collections.emptyList();
        }
    }
}
