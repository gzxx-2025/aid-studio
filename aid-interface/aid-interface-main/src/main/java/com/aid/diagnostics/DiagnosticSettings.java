package com.aid.diagnostics;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.aid.aid.domain.AidConfig;
import com.aid.aid.mapper.AidConfigMapper;
import com.aid.aid.service.IAidConfigService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.UUID;

/** 专属页面的诊断配置，秘密字段不经通用配置接口返回。 */
@Service
@RequiredArgsConstructor
public class DiagnosticSettings {
    public static final String CATEGORY="error_diagnostics";
    private final IAidConfigService configs;
    private final DiagnosticSecretStore secrets;
    private final AidConfigMapper mapper;
    private final TransactionTemplate transactions;
    private volatile JSONObject cached;
    private volatile long readAt;

    public synchronized JSONObject read() {
        if(cached != null && System.currentTimeMillis()-readAt<5000) return JSON.parseObject(cached.toJSONString());
        JSONObject state=fresh();
        cached=state; readAt=System.currentTimeMillis();
        return JSON.parseObject(state.toJSONString());
    }
    private JSONObject fresh() {
        String value=configs.getConfigValue(CATEGORY,"protected_settings");
        return value==null || value.isBlank()?new JSONObject():JSON.parseObject(secrets.open(value));
    }
    private void lockAdmission() {
        AidConfig lock=mapper.selectOne(Wrappers.<AidConfig>query().select("id")
                .eq("category",CATEGORY).eq("config_name","queue_lock").last("FOR UPDATE"));
        if(lock==null)throw new IllegalStateException("诊断数据库尚未升级");
    }
    public boolean enabled() {
        try { return read().getBooleanValue("enabled"); } catch(Exception ignored) { return false; }
    }
    public synchronized void write(JSONObject state) {
        configs.upsertConfigValue(CATEGORY,"protected_settings",secrets.seal(state.toJSONString()));
        cached=JSON.parseObject(state.toJSONString()); readAt=System.currentTimeMillis();
        if(TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if(status!=STATUS_COMMITTED) synchronized(DiagnosticSettings.this){cached=null;readAt=0;}
                }
            });
    }
    public void mutate(java.util.function.Consumer<JSONObject> change) {
        transactions.executeWithoutResult(tx->{
            lockAdmission();
            synchronized(this){JSONObject state=fresh();change.accept(state);write(state);}
        });
    }
    /** Called while the diagnostic admission row is locked in the caller's transaction. */
    public synchronized String ensureKey() {
        JSONObject state=fresh();
        String key=state.getString("aesKey");
        if(key==null || key.isBlank()) {
            key=DiagnosticCrypto.generateKey();
            state.put("aesKey",key);
            write(state);
        } else {
            DiagnosticCrypto.key(key);
            cached=JSON.parseObject(state.toJSONString());readAt=System.currentTimeMillis();
        }
        return key;
    }
    public String instanceId() {
        return transactions.execute(tx->{
            lockAdmission();
            synchronized(this){
                JSONObject state=fresh();
                if(!state.containsKey("instanceId")){state.put("instanceId",UUID.randomUUID().toString());write(state);}
                return state.getString("instanceId");
            }
        });
    }
    public JSONObject update(boolean enabled,String aesKey) {
        if(aesKey!=null && !aesKey.isBlank())DiagnosticCrypto.key(aesKey);
        mutate(state->{state.put("enabled",enabled);if(aesKey!=null && !aesKey.isBlank())state.put("aesKey",aesKey.trim());});
        return publicView();
    }
    public JSONObject updateEnabled(boolean enabled) {
        mutate(state->state.put("enabled",enabled));return publicView();
    }
    public JSONObject publicView() {
        JSONObject state=read();
        JSONObject view=new JSONObject();
        view.put("enabled",state.getBooleanValue("enabled"));
        view.put("keyConfigured",state.getString("aesKey")!=null && !state.getString("aesKey").isBlank());
        view.put("retentionDays",DiagnosticRetention.LOCAL_DAYS);
        view.put("application",state.getJSONObject("application"));
        return view;
    }
}
