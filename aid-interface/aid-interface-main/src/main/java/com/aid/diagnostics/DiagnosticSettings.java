package com.aid.diagnostics;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.aid.aid.service.IAidConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.UUID;

/** 专属页面的诊断配置，秘密字段不经通用配置接口返回。 */
@Service
@RequiredArgsConstructor
public class DiagnosticSettings {
    public static final String CATEGORY="error_diagnostics";
    private final IAidConfigService configs;
    private final DiagnosticSecretStore secrets;
    private volatile JSONObject cached;
    private volatile long readAt;

    public synchronized JSONObject read() {
        if(cached != null && System.currentTimeMillis()-readAt<5000) return JSON.parseObject(cached.toJSONString());
        String value=configs.getConfigValue(CATEGORY,"protected_settings");
        JSONObject state = value == null || value.isBlank() ? new JSONObject() : JSON.parseObject(secrets.open(value));
        cached=state; readAt=System.currentTimeMillis();
        return JSON.parseObject(state.toJSONString());
    }
    public boolean enabled() {
        try { return read().getBooleanValue("enabled"); } catch(Exception ignored) { return false; }
    }
    public synchronized void write(JSONObject state) {
        configs.upsertConfigValue(CATEGORY,"protected_settings",secrets.seal(state.toJSONString()));
        cached=JSON.parseObject(state.toJSONString()); readAt=System.currentTimeMillis();
    }
    public synchronized void mutate(java.util.function.Consumer<JSONObject> change) {
        JSONObject state=read();change.accept(state);write(state);
    }
    public synchronized String instanceId() {
        JSONObject state=read();
        if(!state.containsKey("instanceId")) { state.put("instanceId",UUID.randomUUID().toString());write(state); }
        return state.getString("instanceId");
    }
    public synchronized JSONObject update(boolean enabled,String aesKey) {
        JSONObject state=read(); state.put("enabled",enabled);
        if(aesKey!=null && !aesKey.isBlank()) { DiagnosticCrypto.key(aesKey);state.put("aesKey",aesKey.trim()); }
        write(state);return publicView();
    }
    public synchronized JSONObject updateEnabled(boolean enabled) {
        JSONObject state=read();state.put("enabled",enabled);write(state);return publicView();
    }
    public JSONObject publicView() {
        JSONObject state=read();
        JSONObject view=new JSONObject();
        view.put("enabled",state.getBooleanValue("enabled"));
        view.put("keyConfigured",state.containsKey("aesKey"));
        view.put("retentionDays",7);
        view.put("application",state.getJSONObject("application"));
        return view;
    }
}
