package com.aid.aid.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.aid.aid.domain.media.AidMediaResult;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Insert;

@Mapper
public interface AidMediaResultMapper extends BaseMapper<AidMediaResult> {

    /** 重复回调只刷新原始地址，不覆盖已经持久化完成的 OSS 字段。 */
    @Insert("INSERT INTO aid_media_result "
            + "(task_id,result_index,media_type,origin_url,create_by,create_time,update_by,update_time) "
            + "VALUES (#{taskId},#{resultIndex},#{mediaType},#{originUrl},#{operator},NOW(),#{operator},NOW()) "
            + "ON DUPLICATE KEY UPDATE media_type=VALUES(media_type),origin_url=VALUES(origin_url),"
            + "update_by=VALUES(update_by),update_time=VALUES(update_time)")
    int upsertTaskResult(@Param("taskId") Long taskId,
                         @Param("resultIndex") Integer resultIndex,
                         @Param("mediaType") String mediaType,
                         @Param("originUrl") String originUrl,
                         @Param("operator") String operator);
}
