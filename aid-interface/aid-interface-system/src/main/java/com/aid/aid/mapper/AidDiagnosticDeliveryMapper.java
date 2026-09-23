package com.aid.aid.mapper;
import com.aid.aid.domain.AidDiagnosticDelivery;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.Date;
/** 诊断发送任务映射。 */
@Mapper
public interface AidDiagnosticDeliveryMapper extends BaseMapper<AidDiagnosticDelivery> {
    @Delete("DELETE d FROM aid_diagnostic_delivery d LEFT JOIN aid_diagnostic_event e ON e.event_id=d.event_id WHERE d.create_time <= #{cutoff} OR e.create_time <= #{cutoff} OR e.event_id IS NULL")
    int deleteExpiredOrOrphan(@Param("cutoff") Date cutoff);
}
