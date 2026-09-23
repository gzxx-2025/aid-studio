package com.aid.diagnostics;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.aid.upgrade.constant.UpgradeConfigKeys;
import com.aid.upgrade.util.ManifestSignatureVerifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.time.Instant;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import jakarta.annotation.PostConstruct;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** 只接受经过现有发布公钥验签的诊断元数据。 */
@Service
public class DiagnosticMetadata {
    @Value("${aid.upgrade.manifest-public-key:}") private String publicKey;
    @Value("${aid.diagnostics.manifest-primary:}" ) private String primary;
    @Value("${aid.diagnostics.manifest-secondary:https://raw.githubusercontent.com/gzxx-2025/aid-studio/master/release/latest.json}") private String secondary;
    @Value("${aid.diagnostics.manifest-cache-file:${user.home}/.aid-private/diagnostics-manifest.json}") private String cacheFile;
    private volatile JSONObject cache;
    private volatile long checkedAt;

    @PostConstruct void restore() {
        try {
            Path path=Path.of(cacheFile);
            if(Files.exists(path) && Files.size(path)<=256*1024) {
                String raw=Files.readString(path,StandardCharsets.UTF_8);
                if(ManifestSignatureVerifier.verify(raw,publicKey)) {JSONObject candidate=JSON.parseObject(raw).getJSONObject("diagnostics");if(candidate!=null && candidate.getLongValue("configRevision")>0)cache=candidate;}
            }
        }catch(Exception ignored) { }
    }

    public synchronized JSONObject get() { return get(false); }

    public synchronized JSONObject get(boolean force) {
        long now=System.currentTimeMillis();
        if(!force && cache!=null && now-checkedAt<12*60*60*1000L && valid(cache)) return active(cache);
        if(!force && checkedAt>0 && now-checkedAt<60000)throw new IllegalStateException("官方诊断配置暂不可用，请稍后刷新");
        JSONObject newest=null;
        String newestRaw=null;
        for(String url:new String[]{primary.isBlank()?UpgradeConfigKeys.DEFAULT_MANIFEST_URL:primary,secondary}) {
            if(!https(url)) continue;
            try(HttpResponse response=HttpRequest.get(url).timeout(5000).setFollowRedirects(true).execute()) {
                String raw=response.body();
                if(!response.isOk() || raw==null || raw.length()>256*1024 || !ManifestSignatureVerifier.verify(raw,publicKey)) continue;
                JSONObject candidate=JSON.parseObject(raw).getJSONObject("diagnostics");
                if(candidate!=null && valid(candidate) && candidate.getLongValue("configRevision")>0
                        && (cache==null || candidate.getLongValue("configRevision")>cache.getLongValue("configRevision")
                            || candidate.getLongValue("configRevision")==cache.getLongValue("configRevision") && candidate.equals(cache))
                        && (newest==null || candidate.getLongValue("configRevision")>newest.getLongValue("configRevision"))) {
                    newest=candidate;newestRaw=raw;
                }
            } catch(Exception ignored) { }
        }
        checkedAt=now;
        if(newest!=null) {cache=newest;persist(newestRaw);}
        // An explicit refresh must never silently accept an old signed address after a failed fetch.
        if((newest!=null || !force) && valid(cache)) return active(cache);
        throw new IllegalStateException("官方诊断配置暂不可用或已过期，请稍后刷新");
    }
    private JSONObject active(JSONObject value) {
        if(!value.getBooleanValue("enabled"))throw new IllegalStateException("官方已暂停诊断服务，本地错误采集不受影响");
        return JSON.parseObject(value.toJSONString());
    }
    private void persist(String raw) {
        try {
            Path path=Path.of(cacheFile).toAbsolutePath();Files.createDirectories(path.getParent());
            Path temporary=Files.createTempFile(path.getParent(),"diagnostics-manifest-",".tmp");
            try {Files.writeString(temporary,raw,StandardCharsets.UTF_8);Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            finally {Files.deleteIfExists(temporary);}
        }catch(Exception ignored) { }
    }
    private boolean valid(JSONObject value) {
        try {
            if(value==null || !value.containsKey("enabled") || value.getIntValue("protocolVersion")!=1 || value.getLongValue("configRevision")<1) return false;
            Instant now=Instant.now();
            if(!Instant.parse(value.getString("expiresAt")).isAfter(now) || !Instant.parse(value.getString("rsaKeyExpiresAt")).isAfter(now)
                || Instant.parse(value.getString("issuedAt")).isAfter(now.plusSeconds(300))) return false;
            URI origin=URI.create(value.getString("reportUrl"));
            for(String key:new String[]{"reportUrl","whitelistApplicationUrl","accessStatusUrl","supportVerificationUrl"}) {
                String url=value.getString(key);
                URI uri=URI.create(url);
                if(!https(url) || !origin.getHost().equalsIgnoreCase(uri.getHost()) || origin.getPort()!=uri.getPort() || uri.getUserInfo()!=null || uri.getFragment()!=null) return false;
            }
            String pem=value.getString("rsaPublicKey").replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----","").replaceAll("\\s","");
            RSAPublicKey rsa=(RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
            return value.getString("rsaKeyId")!=null && rsa.getModulus().bitLength()>=3072;
        } catch(Exception ignored) { return false; }
    }
    private boolean https(String value) { return value!=null && value.startsWith("https://"); }
}
