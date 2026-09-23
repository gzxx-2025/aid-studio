package com.aid.aid.mapper;
import com.aid.aid.domain.AidDiagnosticEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
/** 诊断事件映射。 */
@Mapper
public interface AidDiagnosticMapper extends BaseMapper<AidDiagnosticEvent> { }
