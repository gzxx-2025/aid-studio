package com.aid.diagnostics;

import com.aid.aid.domain.*;
import com.aid.aid.mapper.*;
import com.aid.common.utils.SecurityUtils;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;

/** 诊断索引查询、原子发送领取和限期清理。 */
@Service
@RequiredArgsConstructor
public class DiagnosticRepository {
    private static final long RETENTION_MILLIS=DiagnosticRetention.LOCAL_MILLIS;
    private static final DateTimeFormatter REQUEST_TIME_FORMAT=new DateTimeFormatterBuilder().appendInstant(6).toFormatter();
    private static Date cutoff(){return new Date(System.currentTimeMillis()-RETENTION_MILLIS);}
    private final AidDiagnosticMapper events;
    private final AidDiagnosticDeliveryMapper deliveries;
    private final AidDiagnosticTraceMapper traces;
    private final AidConfigMapper configs;
    private volatile long capacityCheckedAt;
    private volatile long capacityBytes;
    public int insert(String id,String requestId,String time,String source,String json) {
        Long userId=null;try{userId=SecurityUtils.getUserId();}catch(Exception ignored) { }
        return insert(id,requestId,time,source,json,userId);
    }
    public int insert(String id,String requestId,String time,String source,String json,Long userId) {
        AidDiagnosticEvent row=new AidDiagnosticEvent();row.setEventId(id);row.setRequestId(requestId);row.setRequestTime(REQUEST_TIME_FORMAT.format(Instant.parse(time)));row.setSource(source);row.setDiagnosticJson(json);row.setCreateTime(new Date());row.setCreateBy("system");
        row.setUserId(userId);
        if("server".equals(source)||"browser".equals(source))row.setCorrelationKey(cn.hutool.crypto.SecureUtil.sha256(Objects.toString(userId,"anonymous")+":"+requestId));
        try {int inserted=events.insert(row);capacityBytes+=json.length()*3L;return inserted;}
        catch(DuplicateKeyException duplicate) {
            if("server".equals(source) && row.getCorrelationKey()!=null) {
                AidDiagnosticEvent existing=events.selectOne(Wrappers.<AidDiagnosticEvent>lambdaQuery().eq(AidDiagnosticEvent::getCorrelationKey,row.getCorrelationKey()));
                if(existing!=null && "browser".equals(existing.getSource())) {
                    JSONObject authoritative=JSON.parseObject(json);JSONObject browser=JSON.parseObject(existing.getDiagnosticJson());
                    authoritative.put("browser",browser.get("browser"));
                    return events.update(null,Wrappers.<AidDiagnosticEvent>lambdaUpdate().eq(AidDiagnosticEvent::getEventId,existing.getEventId()).eq(AidDiagnosticEvent::getSource,"browser").set(AidDiagnosticEvent::getSource,"server").set(AidDiagnosticEvent::getDiagnosticJson,authoritative.toJSONString()));
                }
            }
            if("browser".equals(source) && row.getCorrelationKey()!=null) {
                AidDiagnosticEvent existing=events.selectOne(Wrappers.<AidDiagnosticEvent>lambdaQuery().eq(AidDiagnosticEvent::getCorrelationKey,row.getCorrelationKey()));
                if(existing!=null && "server".equals(existing.getSource())) {
                    JSONObject current=JSON.parseObject(existing.getDiagnosticJson());
                    if(!current.containsKey("browser")) {current.put("browser",JSON.parseObject(json).get("browser"));return events.update(null,Wrappers.<AidDiagnosticEvent>lambdaUpdate().eq(AidDiagnosticEvent::getEventId,existing.getEventId()).eq(AidDiagnosticEvent::getDiagnosticJson,existing.getDiagnosticJson()).set(AidDiagnosticEvent::getDiagnosticJson,current.toJSONString()));}
                }
            }
            return 0;
        }
    }
    /** 列表不读取诊断正文。 */
    public List<Map<String,Object>> page(int offset,int size) {
        List<Map<String,Object>> rows=events.selectMaps(Wrappers.<AidDiagnosticEvent>query().select("event_id AS eventId","request_id AS requestId","request_time AS requestTime","source","report_id AS reportId","create_time AS createTime").gt("create_time",cutoff()).orderByDesc("request_time","create_time","event_id").last("LIMIT "+offset+","+size));
        List<String> ids=rows.stream().map(row->row.get("reportId")).filter(Objects::nonNull).map(String::valueOf).toList();
        if(!ids.isEmpty()) {
            List<AidDiagnosticDelivery> states=deliveries.selectList(Wrappers.<AidDiagnosticDelivery>lambdaQuery().select(AidDiagnosticDelivery::getReportId,AidDiagnosticDelivery::getStatus,AidDiagnosticDelivery::getResultMessage).in(AidDiagnosticDelivery::getReportId,ids));
            for(Map<String,Object> row:rows)for(AidDiagnosticDelivery state:states)if(state.getReportId().equals(row.get("reportId"))){row.put("deliveryStatus",state.getStatus());row.put("deliveryMessage",state.getResultMessage());}
        }
        return rows;
    }
    public long count() {return events.selectCount(Wrappers.<AidDiagnosticEvent>lambdaQuery().gt(AidDiagnosticEvent::getCreateTime,cutoff()));}
    /** Return only the server-side receipt credential and envelope instance reference for visible reports. */
    public List<Map<String,Object>> statusCredentials(Collection<String> reportIds) {
        if(reportIds.isEmpty())return List.of();
        return deliveries.selectMaps(Wrappers.<AidDiagnosticDelivery>query()
                .select("report_id AS reportId","CASE WHEN JSON_VALID(envelope_json) THEN JSON_UNQUOTE(JSON_EXTRACT(envelope_json,'$.instanceId')) END AS instanceId","protected_receipt AS protectedReceipt")
                .in("report_id",reportIds).gt("create_time",cutoff()));
    }
    public Map<String,Object> reservationInfo(String id) {
        AidDiagnosticEvent row=events.selectOne(Wrappers.<AidDiagnosticEvent>lambdaQuery().select(AidDiagnosticEvent::getEventId,AidDiagnosticEvent::getRequestId,AidDiagnosticEvent::getReportId).eq(AidDiagnosticEvent::getEventId,id).gt(AidDiagnosticEvent::getCreateTime,cutoff()).last("FOR UPDATE"));
        if(row==null)return null;Map<String,Object> result=new HashMap<>();result.put("eventId",row.getEventId());result.put("requestId",row.getRequestId());result.put("reportId",row.getReportId());return result;
    }
    public Map<String,Object> detail(String id) {
        AidDiagnosticEvent row=events.selectOne(Wrappers.<AidDiagnosticEvent>lambdaQuery().eq(AidDiagnosticEvent::getEventId,id).gt(AidDiagnosticEvent::getCreateTime,cutoff()));if(row==null)return null;
        Map<String,Object> result=new HashMap<>();result.put("eventId",row.getEventId());result.put("requestId",row.getRequestId());result.put("requestTime",row.getRequestTime());result.put("source",row.getSource());result.put("reportId",row.getReportId());result.put("diagnosticJson",row.getDiagnosticJson());return result;
    }
    public int reserve(String eventId,String reportId) {return events.update(null,Wrappers.<AidDiagnosticEvent>lambdaUpdate().eq(AidDiagnosticEvent::getEventId,eventId).isNull(AidDiagnosticEvent::getReportId).set(AidDiagnosticEvent::getReportId,reportId));}
    public void enqueue(String reportId,String eventId,String options) {
        AidDiagnosticDelivery row=new AidDiagnosticDelivery();row.setReportId(reportId);row.setEventId(eventId);row.setProtectedOptions(options);row.setStatus("queued");row.setCreateTime(new Date());row.setUpdateTime(new Date());row.setCreateBy("system");row.setUpdateBy("system");deliveries.insert(row);
    }
    /** 所有实例的准入事务先锁定同一行，再读取容量和预留事件。不得在持锁期间访问网络。 */
    public void lockAdmission(){
        AidConfig lock=configs.selectOne(Wrappers.<AidConfig>lambdaQuery().select(AidConfig::getId).eq(AidConfig::getCategory,DiagnosticSettings.CATEGORY).eq(AidConfig::getConfigName,"queue_lock").last("FOR UPDATE"));
        if(lock==null)throw new IllegalStateException("诊断数据库尚未升级");
    }
    public long pending(){return deliveries.selectCount(Wrappers.<AidDiagnosticDelivery>lambdaQuery().gt(AidDiagnosticDelivery::getCreateTime,cutoff()).in(AidDiagnosticDelivery::getStatus,"queued","sending","received","checking"));}
    /** 调度只读取任务定位与加密选填资料。 */
    public List<Map<String,Object>> pendingRows(){return deliveries.selectMaps(Wrappers.<AidDiagnosticDelivery>query().select("report_id AS reportId","event_id AS eventId","status").gt("create_time",cutoff()).and(query->query.eq("status","queued").or(nested->nested.eq("status","received").lt("update_time",new Date(System.currentTimeMillis()-15000)))).orderByAsc("update_time").last("LIMIT 2"));}
    public int claim(String id){return deliveries.update(null,Wrappers.<AidDiagnosticDelivery>lambdaUpdate().eq(AidDiagnosticDelivery::getReportId,id).gt(AidDiagnosticDelivery::getCreateTime,cutoff()).eq(AidDiagnosticDelivery::getStatus,"queued").set(AidDiagnosticDelivery::getStatus,"sending").set(AidDiagnosticDelivery::getUpdateTime,new Date()).set(AidDiagnosticDelivery::getUpdateBy,"system"));}
    public int finish(String id,String status,String message){return deliveries.update(null,Wrappers.<AidDiagnosticDelivery>lambdaUpdate().eq(AidDiagnosticDelivery::getReportId,id).in(AidDiagnosticDelivery::getStatus,"sending","checking").set(AidDiagnosticDelivery::getStatus,status).set(AidDiagnosticDelivery::getResultMessage,message).set(AidDiagnosticDelivery::getUpdateTime,new Date()).set(AidDiagnosticDelivery::getUpdateBy,"system"));}
    public AidDiagnosticDelivery delivery(String id){return deliveries.selectById(id);}
    public void envelope(String id,String json){deliveries.update(null,Wrappers.<AidDiagnosticDelivery>lambdaUpdate().eq(AidDiagnosticDelivery::getReportId,id).isNull(AidDiagnosticDelivery::getEnvelopeJson).set(AidDiagnosticDelivery::getEnvelopeJson,json).set(AidDiagnosticDelivery::getProtectedOptions,null).set(AidDiagnosticDelivery::getUpdateTime,new Date()).set(AidDiagnosticDelivery::getUpdateBy,"system"));}
    public void receipt(String id,String receipt){deliveries.update(null,Wrappers.<AidDiagnosticDelivery>lambdaUpdate().eq(AidDiagnosticDelivery::getReportId,id).eq(AidDiagnosticDelivery::getStatus,"sending").set(AidDiagnosticDelivery::getStatus,"received").set(AidDiagnosticDelivery::getProtectedReceipt,receipt).set(AidDiagnosticDelivery::getUpdateTime,new Date()).set(AidDiagnosticDelivery::getUpdateBy,"system"));}
    public int claimReceipt(String id){return deliveries.update(null,Wrappers.<AidDiagnosticDelivery>lambdaUpdate().eq(AidDiagnosticDelivery::getReportId,id).gt(AidDiagnosticDelivery::getCreateTime,cutoff()).eq(AidDiagnosticDelivery::getStatus,"received").set(AidDiagnosticDelivery::getStatus,"checking").set(AidDiagnosticDelivery::getUpdateTime,new Date()).set(AidDiagnosticDelivery::getUpdateBy,"system"));}
    public int retry(String id,boolean queryOnly){return deliveries.update(null,Wrappers.<AidDiagnosticDelivery>lambdaUpdate().eq(AidDiagnosticDelivery::getReportId,id).gt(AidDiagnosticDelivery::getCreateTime,cutoff()).in(AidDiagnosticDelivery::getStatus,"failed","unknown").set(AidDiagnosticDelivery::getStatus,queryOnly?"received":"queued").set(AidDiagnosticDelivery::getResultMessage,null).set(AidDiagnosticDelivery::getUpdateTime,new Date()).set(AidDiagnosticDelivery::getUpdateBy,"system"));}
    public void recover() {
        deliveries.update(null,Wrappers.<AidDiagnosticDelivery>lambdaUpdate().eq(AidDiagnosticDelivery::getStatus,"sending").lt(AidDiagnosticDelivery::getUpdateTime,new Date(System.currentTimeMillis()-600000)).set(AidDiagnosticDelivery::getStatus,"unknown").set(AidDiagnosticDelivery::getResultMessage,"发送进程中断，未自动重发").set(AidDiagnosticDelivery::getUpdateTime,new Date()).set(AidDiagnosticDelivery::getUpdateBy,"system"));
        deliveries.update(null,Wrappers.<AidDiagnosticDelivery>lambdaUpdate().eq(AidDiagnosticDelivery::getStatus,"checking").lt(AidDiagnosticDelivery::getUpdateTime,new Date(System.currentTimeMillis()-600000)).set(AidDiagnosticDelivery::getStatus,"received").set(AidDiagnosticDelivery::getUpdateTime,new Date()).set(AidDiagnosticDelivery::getUpdateBy,"system"));
    }
    /** Caller holds the admission lock so a new delivery cannot be inserted between deletions. */
    public void cleanupExpired(){
        Date boundary=cutoff();
        deliveries.deleteExpiredOrOrphan(boundary);
        int removed=events.delete(Wrappers.<AidDiagnosticEvent>lambdaQuery().le(AidDiagnosticEvent::getCreateTime,boundary));
        traces.delete(Wrappers.<AidDiagnosticTrace>lambdaQuery().le(AidDiagnosticTrace::getCreateTime,boundary));
        if(removed>0)capacityCheckedAt=0;
    }
    public synchronized long storedBytes(){
        if(System.currentTimeMillis()-capacityCheckedAt>60000){List<Map<String,Object>> rows=events.selectMaps(Wrappers.<AidDiagnosticEvent>query().select("COALESCE(SUM(OCTET_LENGTH(diagnostic_json)),0) AS bytes"));capacityBytes=((Number)rows.get(0).get("bytes")).longValue();capacityCheckedAt=System.currentTimeMillis();}return capacityBytes;
    }
    public void browser(String eventId,String requestId,String time,Object data) {
        Long user=SecurityUtils.getUserId();
        AidDiagnosticEvent existing=events.selectOne(Wrappers.<AidDiagnosticEvent>lambdaQuery().eq(AidDiagnosticEvent::getRequestId,requestId).eq(AidDiagnosticEvent::getUserId,user).eq(AidDiagnosticEvent::getSource,"server").last("LIMIT 1"));
        if(existing==null){insert(eventId,requestId,time,"browser",JSON.toJSONString(Map.of("browser",data,"capture",Map.of("serverResponseAvailable",false))));return;}
        JSONObject current=JSON.parseObject(existing.getDiagnosticJson());
        if(!current.containsKey("browser")){current.put("browser",data);events.update(null,Wrappers.<AidDiagnosticEvent>lambdaUpdate().eq(AidDiagnosticEvent::getEventId,existing.getEventId()).eq(AidDiagnosticEvent::getDiagnosticJson,existing.getDiagnosticJson()).set(AidDiagnosticEvent::getDiagnosticJson,current.toJSONString()));}
    }
    public AidDiagnosticTrace trace(String key){return traces.selectById(key);}
    public void saveTrace(String key,String requestId,String json){AidDiagnosticTrace row=new AidDiagnosticTrace();row.setTaskKey(key);row.setRequestId(requestId);row.setRequestJson(json);row.setCreateTime(new Date());row.setCreateBy("system");try{traces.insert(row);}catch(DuplicateKeyException ignored){traces.update(null,Wrappers.<AidDiagnosticTrace>lambdaUpdate().eq(AidDiagnosticTrace::getTaskKey,key).set(AidDiagnosticTrace::getRequestJson,json));}}
    public void deleteTrace(String key){traces.deleteById(key);}
}
