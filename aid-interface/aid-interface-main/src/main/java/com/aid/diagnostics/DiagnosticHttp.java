package com.aid.diagnostics;

import javax.net.ssl.SSLSession;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.stream.Stream;

/** 保持原 HTTP 返回类型，同时在业务读取处收集完整诊断。 */
public final class DiagnosticHttp {
    private DiagnosticHttp() { }
    public static <T> HttpResponse<T> send(HttpClient client,HttpRequest request,HttpResponse.BodyHandler<T> handler) throws IOException,InterruptedException {
        if(DiagnosticCapture.current()==null) return client.send(request,handler);
        DiagnosticCapture.registerHeaders(request.headers().map());
        DiagnosticCapture.transportMetadata(request.uri().toString(),request.method());
        HttpResponse<T> response;
        try {response=client.send(request,handler);}
        catch(IOException | InterruptedException error){DiagnosticCapture.transportFailure(error);throw error;}
        Object body=response.body();
        String upstream=response.headers().firstValue("x-request-id").orElse(null);
        if(body instanceof String text)DiagnosticCapture.transportResponse(response.statusCode(),text,upstream);
        if(body instanceof Stream<?> stream) {
            DiagnosticCapture.transportResponse(response.statusCode(),"[流式响应，见 receivedLines]",upstream);
            java.util.Iterator<?> iterator=stream.iterator();
            body=java.util.stream.StreamSupport.stream(new java.util.Spliterators.AbstractSpliterator<String>(Long.MAX_VALUE,java.util.Spliterator.ORDERED) {
                public boolean tryAdvance(java.util.function.Consumer<? super String> action) {
                    try {if(!iterator.hasNext()){DiagnosticCapture.transportComplete(true);return false;}action.accept(DiagnosticCapture.streamLine(String.valueOf(iterator.next())));return true;}
                    catch(RuntimeException error){DiagnosticCapture.transportFailure(error);throw error;}
                }
            },false).onClose(stream::close);
        } else if(body instanceof InputStream input) {
            DiagnosticCapture.transportResponse(response.statusCode(),"[流式响应，读取完成后保存]",upstream);
            body=new FilterInputStream(input) {
                final ByteArrayOutputStream received=new ByteArrayOutputStream();
                boolean complete;
                @Override public int read() throws IOException {try{int value=in.read();if(value>=0)received.write(value);else complete=true;return value;}catch(IOException error){DiagnosticCapture.transportFailure(error);throw error;}}
                @Override public int read(byte[] b,int off,int len)throws IOException {try{int count=in.read(b,off,len);if(count>0)received.write(b,off,count);else if(count<0)complete=true;return count;}catch(IOException error){DiagnosticCapture.transportFailure(error);throw error;}}
                @Override public void close() throws IOException {
                    try {
                        if(response.statusCode()>=400 && !complete) {byte[] buffer=new byte[8192];while(read(buffer,0,buffer.length)>=0) { }}
                    } finally {
                        DiagnosticCapture.transportResponse(response.statusCode(),received.toString(StandardCharsets.UTF_8),upstream);
                        DiagnosticCapture.transportComplete(complete);
                        super.close();
                    }
                }
            };
        }
        T captured=(T)body;
        return new HttpResponse<>() {
            public int statusCode(){return response.statusCode();}
            public HttpRequest request(){return response.request();}
            public Optional<HttpResponse<T>> previousResponse(){return response.previousResponse();}
            public HttpHeaders headers(){return response.headers();}
            public T body(){return captured;}
            public Optional<SSLSession> sslSession(){return response.sslSession();}
            public URI uri(){return response.uri();}
            public HttpClient.Version version(){return response.version();}
        };
    }
}
