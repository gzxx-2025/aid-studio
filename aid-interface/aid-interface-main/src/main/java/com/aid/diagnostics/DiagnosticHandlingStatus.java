package com.aid.diagnostics;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded server-side lookup of public handling progress; receipt tokens never reach the admin browser. */
@Service
@RequiredArgsConstructor
public class DiagnosticHandlingStatus {
    private static final long FRESH_MILLIS=20_000;
    private static final long FAILURE_MILLIS=5_000;
    private static final Set<String> STATUSES=Set.of("pending","processing","done","refused","unable");
    private final DiagnosticRepository repository;
    private final DiagnosticSecretStore secrets;
    private final DiagnosticMetadata metadata;
    private final ConcurrentHashMap<String,Entry> cache=new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8),task->{Thread thread=new Thread(task,"diagnostic-handling-status");thread.setDaemon(true);return thread;},
            new ThreadPoolExecutor.AbortPolicy());

    private static final class Entry {
        final AtomicBoolean loading=new AtomicBoolean();
        volatile String status;
        volatile String note;
        volatile String updatedAt;
        volatile long expiresAt;
        volatile long lastSeen;
    }
    private record Credential(String reportId,String instanceId,String queryToken) { }

    @PreDestroy void stop(){workers.shutdownNow();}

    public void attach(List<Map<String,Object>> rows) {
        Set<String> ids=new LinkedHashSet<>();
        for(Map<String,Object> row:rows)if(row.get("reportId")!=null)ids.add(String.valueOf(row.get("reportId")));
        if(ids.isEmpty())return;
        Map<String,Credential> credentials=new HashMap<>();
        List<Map<String,Object>> storedRows;
        try {storedRows=repository.statusCredentials(ids);}
        catch(Exception unavailable) {
            for(Map<String,Object> row:rows)if(row.get("reportId")!=null)row.put("handlingStatus","unknown");
            return;
        }
        Set<String> unreadable=new HashSet<>();
        for(Map<String,Object> stored:storedRows) {
            String reportId=Objects.toString(stored.get("reportId"),"");
            String instanceId=Objects.toString(stored.get("instanceId"),"");
            Object protectedReceipt=stored.get("protectedReceipt");
            if(protectedReceipt==null)continue;
            if(instanceId.isBlank()){unreadable.add(reportId);continue;}
            try {
                String token=secrets.open(String.valueOf(protectedReceipt));
                if(token.matches("[0-9a-fA-F]{64}"))credentials.put(reportId,new Credential(reportId,instanceId,token));
                else unreadable.add(reportId);
            } catch(Exception ignored) {unreadable.add(reportId);}
        }
        long now=System.currentTimeMillis();
        List<Credential> due=new ArrayList<>();
        for(String id:ids) {
            if(!credentials.containsKey(id))continue;
            Entry entry=cache.computeIfAbsent(id,ignored->new Entry());
            entry.lastSeen=now;
            if(entry.expiresAt<=now && entry.loading.compareAndSet(false,true))due.add(credentials.get(id));
        }
        if(!due.isEmpty()) {
            try {workers.execute(()->refresh(due));}
            catch(RejectedExecutionException busy) {for(Credential credential:due)fail(credential.reportId());}
        }
        for(Map<String,Object> row:rows) {
            String id=Objects.toString(row.get("reportId"),"");
            if(unreadable.contains(id)){row.put("handlingStatus","unknown");continue;}
            if(!credentials.containsKey(id))continue;
            Entry entry=cache.get(id);
            row.put("handlingStatus",entry==null || entry.status==null?"loading":entry.status);
            row.put("handlingNote",entry==null?null:entry.note);
            row.put("handlingUpdatedAt",entry==null?null:entry.updatedAt);
        }
        if(cache.size()>2000) {
            cache.entrySet().stream().filter(item->!item.getValue().loading.get())
                    .sorted(Comparator.comparingLong(item->item.getValue().lastSeen))
                    .limit(cache.size()-1000L).forEach(item->cache.remove(item.getKey(),item.getValue()));
        }
    }

    private void refresh(List<Credential> credentials) {
        try {
            String reportsUrl=metadata.get().getString("reportUrl");
            if(reportsUrl==null || !reportsUrl.endsWith("/reports"))throw new IllegalStateException("Invalid signed report URL");
            String endpoint=reportsUrl.substring(0,reportsUrl.length()-"/reports".length())+"/report-statuses";
            JSONArray requested=new JSONArray();
            for(Credential credential:credentials) {
                JSONObject item=new JSONObject();
                item.put("reportId",credential.reportId());item.put("instanceId",credential.instanceId());item.put("queryToken",credential.queryToken());
                requested.add(item);
            }
            JSONObject body=new JSONObject();body.put("reports",requested);
            try(HttpResponse response=HttpRequest.post(endpoint).timeout(8000).setFollowRedirects(false)
                    .header("Accept","application/json").body(body.toJSONString(),"application/json").execute()) {
                String text=response.body();
                if(!response.isOk() || text==null || text.length()>1024*1024)throw new IllegalStateException("Status lookup unavailable");
                JSONObject result=JSON.parseObject(text);
                if(result.getIntValue("code")!=0)throw new IllegalStateException("Status lookup unavailable");
                JSONArray reported=result.getJSONObject("data").getJSONArray("reports");
                if(reported==null)throw new IllegalStateException("Status lookup unavailable");
                Map<String,JSONObject> byId=new HashMap<>();
                for(Object value:reported) {
                    JSONObject item=(JSONObject)value;
                    if(item!=null && item.getString("reportId")!=null)byId.put(item.getString("reportId"),item);
                }
                for(Credential credential:credentials) {
                    JSONObject item=byId.get(credential.reportId());
                    if(item==null || !credential.instanceId().equals(item.getString("instanceId")) || !item.getBooleanValue("available")
                            || !STATUSES.contains(item.getString("handlingStatus"))) {fail(credential.reportId());continue;}
                    Entry entry=cache.get(credential.reportId());
                    if(entry==null)continue;
                    entry.status=item.getString("handlingStatus");
                    String note=item.getString("handlingNote");entry.note=note==null?null:note.substring(0,Math.min(4000,note.length()));
                    String updatedAt=item.getString("handlingUpdatedAt");entry.updatedAt=updatedAt==null?null:updatedAt.substring(0,Math.min(64,updatedAt.length()));
                    entry.expiresAt=System.currentTimeMillis()+FRESH_MILLIS;
                    entry.loading.set(false);
                }
            }
        } catch(Exception ignored) {for(Credential credential:credentials)fail(credential.reportId());}
    }
    private void fail(String reportId) {
        Entry entry=cache.get(reportId);if(entry==null)return;
        entry.status="unknown";entry.note=null;entry.updatedAt=null;
        entry.expiresAt=System.currentTimeMillis()+FAILURE_MILLIS;
        entry.loading.set(false);
    }
}
