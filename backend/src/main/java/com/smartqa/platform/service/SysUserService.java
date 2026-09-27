package com.smartqa.platform.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.smartqa.platform.entity.SysUser;

import java.util.List;

/**
 * 用户服务。
 *
 * @author 成员 B
 */
public interface SysUserService extends IService<SysUser> {

    /**
     * 按登录账号查用户（已物理/逻辑过滤）。
     *
     * @param username 学号 / 工号
     * @return 用户，不存在返回 null
     */
    SysUser getByUsername(String username);

    /**
     * 取用户角色码列表，供 Sa-Token 的 StpInterface 使用。
     *
     * @param userId 用户 ID
     * @return 单元素列表（TEACHER / STUDENT），用户不存在返回空列表
     */
    List<String> listRoleCodes(Long userId);
}
