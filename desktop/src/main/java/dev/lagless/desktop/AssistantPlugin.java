package dev.lagless.desktop;

import dev.lagless.Json;
import java.net.URI;
import java.net.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

/** Configurable chat-completions protocol; no provider traffic until an explicit action. */
public final class AssistantPlugin implements LaglessPlugin {
    public record Attachment(String name,String mime,byte[] bytes,String text) { }
    private final java.nio.file.Path home;
    public AssistantPlugin(java.nio.file.Path home) { this.home = home; }
    @Override public String name() { return "AI助手"; }
    @Override public String assist(String action, String text, String context) throws Exception {
        AssistantSettings settings=AssistantSettings.load(home);AssistantSettings.Service current=settings.current();
        if(current==null)throw new IllegalStateException("请先在「AI 设置」添加服务配置");
        if(current.models().isEmpty())throw new IllegalStateException("请先在 AI 设置中为当前服务添加模型");
        return assistFor(current.id(),current.models().getFirst(),action,text,context,"自动检测","简体中文");
    }
    String assistFor(String serviceId,String model,String action,String text,String context,String source,String target) throws Exception {
        StringBuilder result=new StringBuilder();
        assistStreamFor(serviceId,model,action,text,context,source,target,List.of(),delta->result.append(delta));
        return result.toString();
    }
    void assistStreamFor(String serviceId,String model,String action,String text,String context,String source,String target,List<Attachment> attachments,Consumer<String> onDelta) throws Exception {
        AssistantSettings settings=AssistantSettings.load(home);AssistantSettings.Service service=settings.byId(serviceId);
        if(service==null||!service.models().contains(model))throw new IllegalStateException("所选 AI 配置或模型已被删除");
        AssistantSettings.validate(service);
        URI endpoint=URI.create(AssistantSettings.normalizeEndpoint(service.endpoint()));
        String task=settings.prompt(action,source,target);
        if(text.length()>1_000_000||context.length()>1_000_000)throw new IOException("请求或上下文超过 1,000,000 个字符；请缩小终端输出范围或移除部分附件。");
        StringBuilder userText=new StringBuilder("Context:\n").append(context).append("\n\nRequest:\n").append(text);
        List<Object> multimodal=new ArrayList<>();
        for(Attachment attachment:attachments){
            if(attachment.text()!=null)userText.append("\n\nAttached file: ").append(attachment.name()).append("\n").append(attachment.text());
            else if(attachment.bytes()!=null){String encoded=Base64.getEncoder().encodeToString(attachment.bytes());multimodal.add(Map.of("type","image_url","image_url",Map.of("url","data:"+attachment.mime()+";base64,"+encoded)));}
        }
        List<Object> messages=new ArrayList<>();messages.add(Map.of("role","system","content",task));
        if(multimodal.isEmpty())messages.add(Map.of("role","user","content",userText.toString()));
        else{multimodal.add(0,Map.of("type","text","text",userText.toString()));messages.add(Map.of("role","user","content",multimodal));}
        String body=Json.stringify(Map.of("model",model,"messages",messages,"stream",true));
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(60)).header("Content-Type", "application/json");
        String token = service.key();
        // Existing environment-based configuration remains usable until edited.
        if(token.isBlank()&&!service.legacyEnv().isBlank())token=System.getenv(service.legacyEnv());
        if (token != null && !token.isBlank()) request.header("Authorization", "Bearer " + token);
        var response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                .send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofInputStream());
        try (var in = response.body()) {
            if (response.statusCode() != 200) {String detail=new String(in.readNBytes(4096),StandardCharsets.UTF_8).replaceAll("\\s+"," ").trim();throw new IOException("AI 服务返回 HTTP "+response.statusCode()+
                    (response.statusCode()==404?"；实际请求地址："+endpoint+"。请确认服务支持 OpenAI 兼容的 POST chat/completions 路由":"")+(detail.isBlank()?"":"；"+detail));}
            String contentType=response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            if(contentType.contains("text/event-stream"))readEvents(in,onDelta);
            else readJson(in,onDelta);
        }
    }
    private static void readJson(InputStream in,Consumer<String> onDelta)throws IOException{
        byte[] bytes=in.readNBytes(8*1024*1024+1);if(bytes.length>8*1024*1024)throw new IOException("AI 响应超过 8 MiB");
        String text=Json.string(Json.parse(new String(bytes,StandardCharsets.UTF_8)),"choices",0,"message","content");
        if(text==null)throw new IOException("AI 响应中缺少文本");if(!text.isEmpty())onDelta.accept(text);
    }
    private static void readEvents(InputStream in,Consumer<String> onDelta)throws IOException{
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null){
            if(!line.startsWith("data:"))continue;String data=line.substring(5).trim();if(data.equals("[DONE]"))return;if(data.isEmpty())continue;
            Object event;try{event=Json.parse(data);}catch(RuntimeException error){throw new IOException("AI 流式响应格式无效",error);}
            String delta=Json.string(event,"choices",0,"delta","content");if(delta!=null&&!delta.isEmpty())onDelta.accept(delta);
            String failure=Json.string(event,"error","message");if(failure!=null)throw new IOException("AI 流式响应错误："+failure);
        }}
    }
    @Override public String toString() { return name(); }
}
