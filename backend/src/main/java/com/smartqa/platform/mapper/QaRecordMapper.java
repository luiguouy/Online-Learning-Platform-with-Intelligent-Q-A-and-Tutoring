package com.smartqa.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartqa.platform.entity.QaRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 问答记录 Mapper。
 *
 * @author 成员 B
 */
@Mapper
public interface QaRecordMapper extends BaseMapper<QaRecord> {
}
