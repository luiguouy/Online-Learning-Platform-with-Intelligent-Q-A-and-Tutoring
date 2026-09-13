package com.smartqa.platform.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.smartqa.platform.entity.SysUser;
import com.smartqa.platform.mapper.SysUserMapper;
import com.smartqa.platform.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

/**
 * 用户服务实现。
 *
 * @author 成员 B
 */
@Service
@RequiredArgsConstructor
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    @Override
    public SysUser getByUsername(String username) {
        if (!StringUtils.hasText(username)) {
            return null;
        }
        return getOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, username.trim()), false);
    }

    @Override
    public List<String> listRoleCodes(Long userId) {
        if (userId == null) {
            return Collections.emptyList();
        }
        SysUser user = getById(userId);
        if (user == null || !StringUtils.hasText(user.getRole())) {
            return Collections.emptyList();
        }
        return List.of(user.getRole());
    }
}
