package com.aid.diagnostics;

import com.aid.common.core.domain.AjaxResult;
import com.aid.common.exception.ServiceException;
import com.aid.common.utils.SecurityUtils;
import com.alibaba.fastjson2.JSONObject;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 仅接收当前登录用户的浏览器补充诊断。 */
@RestController
@RequestMapping("/api/user/diagnostics")
@RequiredArgsConstructor
public class DiagnosticClientController {
    private final DiagnosticSettings settings;
    private final DiagnosticRepository repository;
    private final DiagnosticSanitizer sanitizer;
    private final ConcurrentHashMap<Long,long[]> captureWindows=new ConcurrentHashMap<>();
    @PostMapping("/status")
    @Operation(summary="读取当前实例是否开启错误采集",description="无业务参数。data.enabled 为布尔值；不返回任何配置秘密。")
    public AjaxResult status(){SecurityUtils.getUserId();return AjaxResult.success(Map.of("enabled",settings.enabled()));}
    @PostMapping("/capture")
    @Operation(summary="补充浏览器实际可见错误",description="请求包括 UUID eventId、UUID requestId、ISO8601 requestTime 和 diagnostic 对象。仅附加当前用户浏览器证据，不覆盖服务端原文。")
    public AjaxResult capture(@RequestBody JSONObject body) {
        Long userId=SecurityUtils.getUserId();
        if(!settings.enabled())return AjaxResult.success(Map.of("accepted",false));
        String eventId,requestId,time;
        try {eventId=UUID.fromString(body.getString("eventId")).toString();requestId=UUID.fromString(body.getString("requestId")).toString();time=Instant.parse(body.getString("requestTime")).toString();}
        catch(Exception invalid){throw new ServiceException("诊断编码或请求时间格式错误");}
        if(body.getJSONObject("diagnostic")==null)throw new ServiceException("缺少诊断内容");
        if(body.toJSONString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>DiagnosticCrypto.MAX_PLAIN_BYTES)throw new ServiceException("浏览器诊断超过容量限制");
        long now=System.currentTimeMillis();
        if(captureWindows.size()>10000)captureWindows.entrySet().removeIf(entry->now-entry.getValue()[0]>60000);
        long[] window=captureWindows.computeIfAbsent(userId,key->new long[]{now,0});
        synchronized(window) {
            if(now-window[0]>60000) {window[0]=now;window[1]=0;}
            if(window[1]++>=60)return AjaxResult.success(Map.of("accepted",false));
        }
        if(repository.storedBytes()>=1024L*1024*1024)return AjaxResult.success(Map.of("accepted",false));
        repository.browser(eventId,requestId,time,sanitizer.clean(body.getJSONObject("diagnostic")));
        return AjaxResult.success(Map.of("accepted",true));
    }
}
