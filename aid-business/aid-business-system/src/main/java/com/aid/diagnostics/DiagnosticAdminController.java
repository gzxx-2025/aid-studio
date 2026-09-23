package com.aid.diagnostics;

import com.aid.common.core.domain.AjaxResult;
import com.aid.common.exception.ServiceException;
import com.alibaba.fastjson2.JSONObject;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 后台单页错误处理接口。 */
@RestController
@RequestMapping("/aid/diagnostics")
@RequiredArgsConstructor
public class DiagnosticAdminController {
    private final DiagnosticService service;
    @GetMapping("/settings") @PreAuthorize("@ss.hasPermi('aid:diagnostics:view')")
    @Operation(summary="读取采集配置与经过签名验证的官方配置")
    public AjaxResult settings(){return AjaxResult.success(service.view());}
    @PutMapping("/settings") @PreAuthorize("@ss.hasPermi('aid:diagnostics:manage')")
    @Operation(summary="修改采集开关与 AES 密钥")
    public AjaxResult settings(@RequestBody JSONObject body){return AjaxResult.success(service.save(body));}
    @PostMapping("/key") @PreAuthorize("@ss.hasPermi('aid:diagnostics:manage')")
    @Operation(summary="安全生成并保存 AES 密钥")
    public AjaxResult key(){return AjaxResult.success(service.generateKey());}
    @GetMapping("/events") @PreAuthorize("@ss.hasPermi('aid:diagnostics:view')")
    @Operation(summary="分页读取错误索引，不返回诊断正文")
    public AjaxResult events(@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size){return AjaxResult.success(service.page(page,size));}
    @GetMapping("/events/{id}") @PreAuthorize("@ss.hasPermi('aid:diagnostics:view')")
    @Operation(summary="读取指定错误完整诊断 JSON")
    public AjaxResult event(@PathVariable String id){return AjaxResult.success(service.detail(id));}
    @PostMapping("/applications") @PreAuthorize("@ss.hasPermi('aid:diagnostics:manage')")
    @Operation(summary="以服务端实际出口 IP 提交人工白名单申请")
    public AjaxResult apply(@RequestBody JSONObject body){return AjaxResult.success(service.apply(body));}
    @GetMapping("/applications/status") @PreAuthorize("@ss.hasPermi('aid:diagnostics:view')")
    @Operation(summary="查询本实例白名单申请状态")
    public AjaxResult application(@RequestParam(defaultValue="true") boolean force){return AjaxResult.success(service.access(force));}
    @GetMapping("/access") @PreAuthorize("@ss.hasPermi('aid:diagnostics:view')")
    @Operation(summary="核对当前服务端实际出口授权")
    public AjaxResult access(@RequestParam(defaultValue="true") boolean force){return AjaxResult.success(service.access(force));}
    @PostMapping("/support-verifications") @PreAuthorize("@ss.hasPermi('aid:diagnostics:manage')")
    @Operation(summary="加密提交可选服务器资料进行连接验证")
    public AjaxResult verify(@RequestBody JSONObject body){return AjaxResult.success(service.verify(body));}
    @GetMapping("/support-verifications/status") @PreAuthorize("@ss.hasPermi('aid:diagnostics:manage')")
    @Operation(summary="查询当前连接验证结果")
    public AjaxResult verification(){return AjaxResult.success(service.verification());}
    @PostMapping("/reports") @PreAuthorize("@ss.hasPermi('aid:diagnostics:send')")
    @Operation(summary="原子提交异步发送任务，同一错误不重复发送")
    public AjaxResult send(@RequestBody JSONObject body){return AjaxResult.success(service.send(body));}
    @PostMapping("/reports/{id}/retry") @PreAuthorize("@ss.hasPermi('aid:diagnostics:send')")
    @Operation(summary="手动查询未知报告，或以首次保存密文重试未确认发送")
    public AjaxResult retry(@PathVariable String id){service.retry(id);return AjaxResult.success();}

    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<AjaxResult> serviceError(ServiceException error) {
        if(Integer.valueOf(403).equals(error.getCode()))
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(AjaxResult.error(403,error.getMessage()));
        return ResponseEntity.ok(error.getCode()==null?AjaxResult.error(error.getMessage()):AjaxResult.error(error.getCode(),error.getMessage()));
    }
}
