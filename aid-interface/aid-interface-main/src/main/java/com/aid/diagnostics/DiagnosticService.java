package com.aid.diagnostics;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.aid.common.exception.ServiceException;
import com.aid.aid.domain.AidDiagnosticDelivery;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 错误处理页面、白名单申请和有界异步发送服务。 */
@Service
@RequiredArgsConstructor
public class DiagnosticService {
    private final DiagnosticSettings settings;
    private final DiagnosticSecretStore secrets;
    private final DiagnosticMetadata metadata;
    private final DiagnosticRepository mapper;
    private final DiagnosticCapture capture;
    private final TransactionTemplate transactions;
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor(task->{Thread t=new Thread(task,"diagnostic-scheduler");t.setDaemon(true);return t;});
    private final ThreadPoolExecutor sender=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),task->{Thread t=new Thread(task,"diagnostic-sender");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final AtomicBoolean polling=new AtomicBoolean();
    private long lastCleanup;

    @PostConstruct void start() { scheduler.scheduleWithFixedDelay(this::dispatch,5,5,TimeUnit.SECONDS); }
    @PreDestroy void stop() { scheduler.shutdownNow();sender.shutdownNow(); }

    public JSONObject view() {
        JSONObject view=settings.publicView();
        view.put("captureFailures",capture.captureFailures.get());
        try {JSONObject config=metadata.get();view.put("metadata",config);}
        catch(Exception unavailable) {view.put("metadataError",unavailable.getMessage());}
        return view;
    }
    public JSONObject save(JSONObject request) {
        if(request==null || !request.containsKey("enabled") || !(request.get("enabled") instanceof Boolean))
            throw new ServiceException("采集开关参数错误");
        if(request.size()==1)return settings.updateEnabled(request.getBooleanValue("enabled"));
        if(request.size()!=2 || !request.containsKey("aesKey"))throw new ServiceException("诊断配置参数错误");
        requireApproved();
        return settings.update(request.getBooleanValue("enabled"),request.getString("aesKey"));
    }
    public JSONObject generateKey() {
        requireApproved();requireEnabled();String key=DiagnosticCrypto.generateKey();
        settings.update(true,key);JSONObject value=new JSONObject();value.put("aesKey",key);return value;
    }
    public Map<String,Object> page(int page,int size) {
        requireApproved();requireEnabled();int safeSize=Math.min(100,Math.max(1,size));
        return Map.of("rows",mapper.page((Math.max(1,page)-1)*safeSize,safeSize),"total",mapper.count());
    }
    public Map<String,Object> detail(String eventId) {
        requireApproved();requireEnabled();Map<String,Object> event=mapper.detail(eventId);
        if(event==null)throw new ServiceException("错误记录不存在或已按保留期限清理");
        event.put("diagnostic",JSON.parse(String.valueOf(event.remove("diagnosticJson"))));return event;
    }
    public synchronized JSONObject apply(JSONObject input) {
        JSONObject currentAccess=access(true);
        if("unavailable".equals(currentAccess.getString("status")))throw new ServiceException("授权状态暂不可用",403);
        if(currentAccess.getBooleanValue("canOperate") || "pending".equals(currentAccess.getString("status")))return currentAccess;
        if(!currentAccess.getBooleanValue("canApply"))throw new ServiceException("当前不可重新申请",403);
        if(input==null)throw new ServiceException("请填写申请资料");
        JSONObject request=new JSONObject();request.put("instanceId",settings.instanceId());
        request.put("applicationId",UUID.randomUUID().toString());
        request.put("platformName",required(input,"platformName",100));
        request.put("contact",required(input,"contact",250));
        request.put("description",required(input,"description",2000));
        JSONObject result=remote("POST",metadata.get().getString("whitelistApplicationUrl"),request,null);
        String queryToken=result.getString("queryToken");result.remove("queryToken");
        if(queryToken==null || queryToken.isBlank())throw new ServiceException("申请查询凭证缺失");
        settings.mutate(current->{archiveApplication(current);current.put("applicationQueryToken",queryToken);current.put("application",result);});
        return mergeAccess(currentAccess,result,true);
    }
    public synchronized JSONObject refreshApplication() {
        return access(true);
    }
    public synchronized JSONObject access() { return access(false); }
    public synchronized JSONObject access(boolean force) {
        JSONObject config;
        try {config=metadata.get(force);}
        catch(Exception unavailable) {return unavailable("官方诊断配置暂不可用");}
        JSONObject actual;
        try {actual=remote("GET",config.getString("accessStatusUrl")+"?instanceId="+settings.instanceId(),null,null);}
        catch(Exception unavailable) {return unavailable("暂时无法查询授权状态");}
        String observed=actual.getString("observedIp");
        if(observed==null || observed.isBlank())return unavailable("未取得服务器出口 IP");
        JSONObject state=settings.read();
        JSONObject application=state.getJSONObject("application");
        String invalidApplicationReason=null;
        if(application!=null && !observed.equals(application.getString("observedIp"))) {
            settings.mutate(current->{archiveApplication(current);clearApplication(current);});
            application=null;
            invalidApplicationReason="服务器出口 IP 已变化，请重新申请";
        }
        if(application!=null) {
            String id=application.getString("applicationId");
            String token=state.getString("applicationQueryToken");
            try {
                if(id==null || token==null)throw new RemoteFailure(403);
                application=remote("GET",config.getString("whitelistApplicationUrl")+"/"+DiagnosticCapture.validId(id),null,token);
                application.remove("queryToken");
                JSONObject verifiedApplication=application;
                settings.mutate(current->current.put("application",verifiedApplication));
            } catch(RemoteFailure missing) {
                if(missing.status!=403 && missing.status!=404)return unavailable("暂时无法查询申请状态");
                settings.mutate(current->{archiveApplication(current);clearApplication(current);});
                application=null;
                invalidApplicationReason="原申请在当前授权服务不可用，请重新申请";
            } catch(Exception unavailable) {return unavailable("暂时无法查询申请状态");}
        }
        boolean approved="approved".equals(actual.getString("status")) && actual.getBooleanValue("authorized");
        settings.mutate(current->{current.put("accessStatus",approved?"approved":"none");current.put("accessCheckedAt",System.currentTimeMillis());current.put("accessObservedIp",observed);});
        JSONObject result=mergeAccess(actual,application,settings.read().getString("applicationQueryToken")!=null);
        if(!approved && invalidApplicationReason!=null)result.put("reason",invalidApplicationReason);
        return result;
    }
    private JSONObject mergeAccess(JSONObject actual,JSONObject application,boolean tokenSaved) {
        boolean approved="approved".equals(actual.getString("status")) && actual.getBooleanValue("authorized")
                && actual.getString("observedIp")!=null && !actual.getString("observedIp").isBlank();
        JSONObject result=new JSONObject();
        result.put("observedIp",actual.getString("observedIp"));
        result.put("authorized",approved);result.put("canOperate",approved);
        result.put("queryCredentialSaved",application!=null && tokenSaved);
        if(application!=null) {
            result.put("applicationId",application.getString("applicationId"));
            result.put("reason",application.getString("reason"));
        }
        String status=approved?"approved":application==null?"none":application.getString("status");
        if(!approved && "approved".equals(status)) {
            status="revoked";result.put("reason","当前服务器出口 IP 已无有效授权，请重新申请");
        }
        result.put("status",status);
        result.put("canApply",!approved && !"pending".equals(status));
        return result;
    }
    private JSONObject unavailable(String reason) {
        JSONObject result=new JSONObject();result.put("status","unavailable");result.put("authorized",false);
        result.put("canOperate",false);result.put("canApply",false);result.put("queryCredentialSaved",settings.read().getString("applicationQueryToken")!=null);
        result.put("reason",reason);
        JSONObject application=settings.read().getJSONObject("application");
        if(application!=null)result.put("applicationId",application.getString("applicationId"));
        return result;
    }
    private void archiveApplication(JSONObject state) {
        JSONObject application=state.getJSONObject("application");if(application==null)return;
        com.alibaba.fastjson2.JSONArray history=state.getJSONArray("applicationHistory");
        if(history==null)history=new com.alibaba.fastjson2.JSONArray();
        history.add(application);while(history.size()>20)history.remove(0);
        state.put("applicationHistory",history);
    }
    private void clearApplication(JSONObject state) {
        for(String field:List.of("application","applicationQueryToken","verificationId","verificationQueryToken","verificationStartedAt","supportVerificationToken"))state.remove(field);
    }
    private void requireApproved() {
        JSONObject access=access();
        if(!access.getBooleanValue("canOperate"))throw new ServiceException("当前出口 IP 未获授权",403);
    }
    public synchronized JSONObject verify(JSONObject request) {
        requireApproved();requireEnabled();
        if(!request.getBooleanValue("consent"))throw new ServiceException("请确认本次远程只读排查授权");
        JSONObject server=request.getJSONObject("server");
        if(server==null)throw new ServiceException("请填写服务器连接资料");
        required(server,"host",253);required(server,"username",100);required(server,"password",1024);
        int port=server.getIntValue("port");if(port<1 || port>65535)throw new ServiceException("端口须为 1 至 65535");
        JSONObject payload=new JSONObject();payload.put("server",server);payload.put("consent",true);
        JSONObject config=metadata.get();JSONObject result=remote("POST",config.getString("supportVerificationUrl"),envelope(payload,UUID.randomUUID().toString(),config),null);
        String queryToken=result.getString("queryToken");
        settings.mutate(state->{state.put("verificationQueryToken",queryToken);state.put("verificationId",result.getString("verificationId"));state.put("verificationStartedAt",System.currentTimeMillis());state.remove("supportVerificationToken");});result.remove("queryToken");return result;
    }
    public synchronized JSONObject verification() {
        requireApproved();requireEnabled();JSONObject state=settings.read();String id=state.getString("verificationId");
        if(id==null || System.currentTimeMillis()-state.getLongValue("verificationStartedAt")>15*60*1000L)throw new ServiceException("连接验证已过期，请重新验证");
        JSONObject result=remote("GET",metadata.get().getString("supportVerificationUrl")+"/"+DiagnosticCapture.validId(id),null,state.getString("verificationQueryToken"));
        if("verified".equals(result.getString("status")))settings.mutate(current->{if(id.equals(current.getString("verificationId")))current.put("supportVerificationToken",result.getString("verificationToken"));});
        result.remove("queryToken");result.remove("verificationToken");return result;
    }
    public Map<String,Object> send(JSONObject input) {
        requireApproved();requireEnabled();JSONObject state=settings.read();
        if(state.getString("aesKey")==null)throw new ServiceException("请先生成或填写 AES 密钥");
        List<String> eventIds=input.getList("eventIds",String.class);
        if(eventIds==null || eventIds.isEmpty() || eventIds.size()>50)throw new ServiceException("请选择 1 至 50 条错误");
        JSONObject options=new JSONObject();
        JSONObject optional=input.getJSONObject("optionalSupport");
        if(optional!=null && !optional.isEmpty())options.put("optionalSupport",optionalSupport(optional));
        if(input.getBooleanValue("includeServer")) {
            if(!state.containsKey("supportVerificationToken") || System.currentTimeMillis()-state.getLongValue("verificationStartedAt")>15*60*1000L)throw new ServiceException("请先验证当前服务器资料");
            options.put("supportVerificationToken",state.getString("supportVerificationToken"));
        }
        options.put("expiresAt",System.currentTimeMillis()+24*60*60*1000L);
        List<String> reportIds=new ArrayList<>();
        transactions.executeWithoutResult(transaction->{
            mapper.lockAdmission();
            long available=500-mapper.pending();
            for(String id:new LinkedHashSet<>(eventIds)) {
                Map<String,Object> event=mapper.reservationInfo(id);
                if(event==null)throw new ServiceException("所选错误已不存在，请刷新列表");
                if(event.get("reportId")!=null) {reportIds.add(String.valueOf(event.get("reportId")));continue;}
                if(available--<=0)throw new ServiceException("发送队列已满，请稍后再试");
                String reportId=UUID.randomUUID().toString();
                if(mapper.reserve(id,reportId)==1) {mapper.enqueue(reportId,id,secrets.seal(options.toJSONString()));reportIds.add(reportId);}
                else throw new ServiceException("错误记录已变更，请刷新列表");
            }
        });
        return Map.of("reportIds",reportIds,"message","已提交发送，无需重复操作");
    }
    private Map<String,Object> envelope(JSONObject payload,String reportId,JSONObject config) {
        String aesKey=settings.read().getString("aesKey");
        if(aesKey==null)throw new ServiceException("请先生成或填写 AES 密钥");
        return DiagnosticCrypto.encrypt(payload.toJSONString(),aesKey,config.getString("rsaPublicKey"),config.getString("rsaKeyId"),reportId,settings.instanceId());
    }
    private void dispatch() {
        if(!polling.compareAndSet(false,true))return;
        try {
            if(System.currentTimeMillis()-lastCleanup>3600000) {mapper.recover();mapper.cleanupEvents();mapper.cleanupDeliveries();lastCleanup=System.currentTimeMillis();}
            if(!settings.enabled())return;
            for(Map<String,Object> row:mapper.pendingRows()) {
                if(sender.getQueue().remainingCapacity()==0)break;
                sender.execute(()->{if("received".equals(row.get("status")))checkReceipt(String.valueOf(row.get("reportId")));else deliver(row);});
            }
        } catch(Exception ignored) { }
        finally {polling.set(false);}
    }
    private void deliver(Map<String,Object> row) {
        String id=String.valueOf(row.get("reportId"));if(mapper.claim(id)!=1)return;
        try {
            requireApproved();requireEnabled();AidDiagnosticDelivery delivery=mapper.delivery(id);
            String packet=delivery.getEnvelopeJson();
            JSONObject config=metadata.get();
            if(packet==null) {
            Map<String,Object> event=mapper.detail(String.valueOf(row.get("eventId")));
            if(event==null)throw new ServiceException("错误原文已不可用");
            JSONObject item=new JSONObject();item.put("eventId",event.get("eventId"));item.put("requestId",event.get("requestId"));item.put("requestTime",event.get("requestTime"));item.put("diagnostic",JSON.parse(String.valueOf(event.get("diagnosticJson"))));
            JSONObject payload=new JSONObject();payload.put("events",List.of(item));
            JSONObject options=delivery.getProtectedOptions()==null?new JSONObject():JSON.parseObject(secrets.open(delivery.getProtectedOptions()));
            if(System.currentTimeMillis()<options.getLongValue("expiresAt")) {
                if(options.containsKey("optionalSupport"))payload.put("optionalSupport",options.get("optionalSupport"));
                if(options.containsKey("supportVerificationToken"))payload.put("supportVerificationToken",options.get("supportVerificationToken"));
            }
            packet=JSON.toJSONString(envelope(payload,id,config));mapper.envelope(id,packet);
            }
            JSONObject receipt=remote("POST",config.getString("reportUrl"),JSON.parseObject(packet),null);
            if(receipt.getString("queryToken")==null)throw new ServiceException("官方接收回执缺少查询凭证");
            mapper.receipt(id,secrets.seal(receipt.getString("queryToken")));
        } catch(Exception error) {
            String message=error instanceof IllegalArgumentException ? error.getMessage() : "发送未完成，未自动重发";
            mapper.finish(id,error instanceof IllegalArgumentException?"failed":"unknown",message);
        }
    }
    public void retry(String id) {
        requireApproved();requireEnabled();AidDiagnosticDelivery delivery=mapper.delivery(id);
        if(delivery==null)throw new ServiceException("报告不存在或已清理");
        boolean queryOnly=delivery.getProtectedReceipt()!=null;
        if(!queryOnly && delivery.getEnvelopeJson()!=null) {
            long createdAt=JSON.parseObject(delivery.getEnvelopeJson()).getLongValue("createdAt");
            if(System.currentTimeMillis()-createdAt>24*60*60*1000L)throw new ServiceException("原发送包已超过 24 小时准入期限，需要新建报告，不能继续重发旧包");
        }
        transactions.executeWithoutResult(transaction->{mapper.lockAdmission();if(mapper.pending()>=500)throw new ServiceException("发送队列已满，请稍后再试");mapper.retry(id,queryOnly);});
    }
    private void checkReceipt(String id) {
        if(mapper.claimReceipt(id)!=1)return;
        try {
            requireApproved();
            AidDiagnosticDelivery delivery=mapper.delivery(id);JSONObject packet=JSON.parseObject(delivery.getEnvelopeJson());
            JSONObject state=remote("GET",metadata.get().getString("reportUrl")+"/"+id+"?instanceId="+packet.getString("instanceId"),null,secrets.open(delivery.getProtectedReceipt()));
            String status=state.getString("status");
            if("ready".equals(status))mapper.finish(id,"delivered","官方已完成接收和解密");
            else if("failed".equals(status))mapper.finish(id,"failed","官方未能处理报告，可查看接收站错误编码："+Objects.toString(state.getString("errorCode"),"未知"));
            else if(System.currentTimeMillis()-delivery.getCreateTime().getTime()>60*60*1000L)mapper.finish(id,"unknown","官方处理仍未确认，可手动重新查询");
            else mapper.finish(id,"received","官方正在处理报告");
        }catch(Exception ignored){mapper.finish(id,"unknown","暂未取得官方终态，可手动重新查询");}
    }
    private JSONObject remote(String method,String url,Object body,String token) {
        HttpRequest request="GET".equals(method)?HttpRequest.get(url):HttpRequest.post(url);
        request.timeout(15000).setFollowRedirects(false).header("Accept","application/json");
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(body!=null)request.body(JSON.toJSONString(body),"application/json");
        try(HttpResponse response=request.execute()) {
            if(response.getStatus()>=300 && response.getStatus()<400)throw new ServiceException("官方接口重定向已拒绝");
            String text=response.body();
            if(text==null || text.length()>1024*1024)throw new ServiceException("官方接口响应格式错误");
            JSONObject result=JSON.parseObject(text);
            if(!response.isOk() || result.getIntValue("code")!=0)throw new RemoteFailure(response.getStatus());
            JSONObject data=result.getJSONObject("data");return data==null?new JSONObject():data;
        } catch(RemoteFailure known) {throw known;}
        catch(ServiceException known) {throw known;}
        catch(Exception unavailable) {throw new ServiceException("暂时无法连接官方诊断服务");}
    }
    private static final class RemoteFailure extends RuntimeException {
        private final int status;
        private RemoteFailure(int status) {this.status=status;}
    }
    private void requireEnabled() {if(!settings.enabled())throw new ServiceException("请先开启错误采集");}
    private JSONObject optionalSupport(JSONObject input) {
        JSONObject result=new JSONObject();
        for(String type:List.of("testAccount","adminAccount")) {
            JSONObject account=input.getJSONObject(type);
            if(account!=null) {
                JSONObject safe=new JSONObject();
                for(String field:List.of("username","password")) {
                    String value=account.getString(field);
                    if(value!=null && !value.isBlank()) {if(value.length()>("username".equals(field)?100:1024))throw new ServiceException("选填账号资料过长");safe.put(field,value);}
                }
                if(!safe.isEmpty())result.put(type,safe);
            }
        }
        String notes=input.getString("notes");
        if(notes!=null && !notes.isBlank()) {if(notes.length()>4000)throw new ServiceException("问题补充说明过长");result.put("notes",notes);}
        return result;
    }
    private String required(JSONObject input,String key,int max) {
        String value=input.getString(key);if(value==null || value.isBlank() || value.length()>max)throw new ServiceException("请检查字段 "+key+" 的内容与长度");return value.trim();
    }
}
