package dev.lagless.desktop;

import dev.lagless.I18n;
import dev.lagless.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** A runnable native protocol example, not a shell executor. */
public final class ProcessPluginExample {
    private ProcessPluginExample() { }
    public static void run() throws IOException {
        try(Reader in=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8));
            Writer out=new BufferedWriter(new OutputStreamWriter(System.out,StandardCharsets.UTF_8))) {
            String line;
            while((line=ProcessPlugin.readLine(in))!=null){
                Object request=Json.parse(line);if(!"assist".equals(Json.string(request,"type")))continue;
                long id=Json.number(request,-1,"id");String text=Json.string(request,"text");
                String result=text!=null && text.strip().startsWith("git") ? I18n.t("git status\n查看仓库状态；只读草稿，需审阅后手动发送。","git status\nShow repository status; read-only draft, review before sending.")
                        : I18n.t("ls -lah\n查看目录；只读草稿，需审阅后手动发送。","ls -lah\nList the folder; read-only draft, review before sending.");
                out.write(Json.stringify(Map.of("version",1,"id",id,"ok",true,"text",result)));out.write('\n');out.flush();
            }
        }
    }
}
