package com.smartqa.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartqa.platform.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统用户 Mapper。
 *
 * @author 成员 B
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
