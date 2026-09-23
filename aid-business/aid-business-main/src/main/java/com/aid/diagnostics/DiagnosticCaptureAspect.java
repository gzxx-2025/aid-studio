package com.aid.diagnostics;

import com.aid.common.core.domain.AjaxResult;
import com.aid.media.provider.ProviderSubmitResult;
import com.aid.media.provider.ProviderTaskResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.*;

/** 将业务失败和供应商原始调用关联到同一诊断上下文。 */
@Aspect
@Component
@RequiredArgsConstructor
public class DiagnosticCaptureAspect {
    private final DiagnosticCapture capture;

    @Around("@annotation(org.springframework.web.bind.annotation.ExceptionHandler)")
    public Object handledException(ProceedingJoinPoint point) throws Throwable {
        if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes))return point.proceed();
        HttpServletRequest request=attributes.getRequest();
        if(!request.getRequestURI().startsWith("/api/") || request.getRequestURI().contains("/diagnostics/"))return point.proceed();
        String id=requestId(request);
        if(attributes.getResponse()!=null)attributes.getResponse().setHeader("X-AID-Request-ID",id);
        Throwable original=null;for(Object arg:point.getArgs())if(arg instanceof Throwable error){original=error;break;}
        if(!Boolean.TRUE.equals(request.getAttribute("aid.diagnostics.captured"))) {
            DiagnosticCapture.Context context=capture.begin(id,Map.of("path",request.getRequestURI(),"method",request.getMethod(),"parameters",request.getParameterMap()));
            capture.finish(context,null,original,true,"server");
        }
        Object result=point.proceed();if(result instanceof AjaxResult ajax){ajax.put("requestId",id);Object originalMessage=request.getAttribute("aid.diagnostics.originalError");Object safeMessage=request.getAttribute("aid.diagnostics.safeError");if(originalMessage!=null && originalMessage.equals(ajax.get("msg")))ajax.put("msg",safeMessage);}
        request.removeAttribute("aid.diagnostics.originalError");request.removeAttribute("aid.diagnostics.safeError");return result;
    }

    @Around("@within(org.springframework.web.bind.annotation.RestController) && !within(com.aid.diagnostics..*)")
    public Object request(ProceedingJoinPoint point) throws Throwable {
        if(!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) return point.proceed();
        HttpServletRequest request=attributes.getRequest();
        String path=request.getRequestURI();
        if(!path.startsWith("/api/") || path.contains("/diagnostics/")) return point.proceed();
        String requestId=requestId(request);
        HttpServletResponse response=attributes.getResponse();
        if(response!=null)response.setHeader("X-AID-Request-ID",requestId);
        List<Object> args=new ArrayList<>();
        for(Object arg:point.getArgs()) if(!(arg instanceof jakarta.servlet.ServletRequest || arg instanceof jakarta.servlet.ServletResponse || arg instanceof org.springframework.web.multipart.MultipartFile))args.add(arg);
        DiagnosticCapture.Context context=capture.begin(requestId,Map.of("method",request.getMethod(),"path",path,"parameters",args));
        try {Map<String,List<String>> headers=new HashMap<>();for(String name:Collections.list(request.getHeaderNames()))headers.put(name,Collections.list(request.getHeaders(name)));DiagnosticCapture.registerHeaders(headers);}catch(Exception ignored){capture.captureFailures.incrementAndGet();}
        Object result=null;Throwable error=null;
        try {
            result=point.proceed();
            if(result instanceof AjaxResult ajax){ajax.put("requestId",requestId);Object code=ajax.get("code");if(context!=null && context.failed || code instanceof Number number && number.intValue()!=200 && number.intValue()!=0)for(String key:new ArrayList<>(ajax.keySet()))ajax.put(key,DiagnosticCapture.publicFailureValue(ajax.get(key)));}
            return result;
        } catch(Throwable thrown) {error=thrown;String safe=DiagnosticCapture.redactKnownSecrets(thrown.getMessage());if(!Objects.equals(safe,thrown.getMessage())){request.setAttribute("aid.diagnostics.originalError",thrown.getMessage());request.setAttribute("aid.diagnostics.safeError",safe);}throw thrown;}
        finally {
            boolean failed=result instanceof Map<?,?> map && map.get("code") instanceof Number code && code.intValue()!=200 && code.intValue()!=0;
            capture.finish(context,result,error,failed,"server");
            if(context!=null)request.setAttribute("aid.diagnostics.captured",Boolean.TRUE);
        }
    }

    private String requestId(HttpServletRequest request) {
        Object existing=request.getAttribute("aid.diagnostics.requestId");
        if(existing instanceof String id)return id;
        String id=DiagnosticCapture.validId(request.getHeader("X-AID-Request-ID"));
        request.setAttribute("aid.diagnostics.requestId",id);return id;
    }

    @Around("execution(* com.aid.media.provider..*.submit(..)) || execution(* com.aid.media.provider..*.query(..)) || execution(* com.aid.media.provider..*.chatSync(..)) || execution(* com.aid.media.provider..*.streamChat(..)) || execution(* com.aid.tokendance.provider..*.submit(..)) || execution(* com.aid.tokendance.provider..*.query(..)) || execution(* com.aid.tokendance.provider..*.streamChat(..))")
    public Object provider(ProceedingJoinPoint point) throws Throwable {
        DiagnosticCapture.Context parent=DiagnosticCapture.current();
        DiagnosticCapture.Context context=capture.beginProvider(parent==null?null:parent.requestId,Map.of("operation",point.getSignature().toShortString(),"parameters",Arrays.asList(point.getArgs())));
        Object result=null;Throwable error=null;
        try {result=point.proceed();return result;}
        catch(Throwable thrown) {error=thrown;throw thrown;}
        finally {
            boolean failed=false;
            if(result instanceof ProviderSubmitResult submitted)failed=submitted.getProviderTaskId()==null && submitted.getDirectUrl()==null && submitted.getDirectText()==null && submitted.getOssUrl()==null && submitted.getAudioBase64()==null && (submitted.getResultUrls()==null || submitted.getResultUrls().isEmpty()) && submitted.getToolMessage()==null;
            if(failed && result instanceof ProviderSubmitResult submitted){submitted.setRawResponse(DiagnosticCapture.publicFailureJson(submitted.getRawResponse()));submitted.setErrorDetailJson(DiagnosticCapture.publicFailureJson(submitted.getErrorDetailJson()));}
            if(context!=null && result instanceof ProviderSubmitResult submitted && submitted.getProviderTaskId()!=null)context.submitted=true;
            if(result instanceof ProviderTaskResult queried)failed="FAILED".equals(queried.getStatus()) || Boolean.FALSE.equals(queried.getQuerySuccessful());
            if(failed && result instanceof ProviderTaskResult queried){queried.setErrorMessage(DiagnosticCapture.redactKnownSecrets(queried.getErrorMessage()));queried.setRawErrorMessage(DiagnosticCapture.redactKnownSecrets(queried.getRawErrorMessage()));if(queried.getTaskError()!=null){queried.getTaskError().setUserMessage(DiagnosticCapture.redactKnownSecrets(queried.getTaskError().getUserMessage()));queried.getTaskError().setRawMessage(DiagnosticCapture.redactKnownSecrets(queried.getTaskError().getRawMessage()));}}
            capture.finish(context,result,error,failed,"provider");
        }
    }
}
