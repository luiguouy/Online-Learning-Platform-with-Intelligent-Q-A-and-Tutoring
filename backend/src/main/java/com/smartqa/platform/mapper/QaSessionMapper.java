package com.smartqa.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartqa.platform.entity.QaSession;
import org.apache.ibatis.annotations.Mapper;

/**
 * 问答会话 Mapper。
 *
 * @author 成员 B
 */
@Mapper
public interface QaSessionMapper extends BaseMapper<QaSession> {
}
