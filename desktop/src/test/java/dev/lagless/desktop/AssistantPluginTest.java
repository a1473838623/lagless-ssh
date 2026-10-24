package dev.lagless.desktop;

import com.sun.net.httpserver.HttpServer;
import dev.lagless.Json;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AssistantPluginTest {
    @TempDir Path dir;
    @Test void onlyExplicitActionsSendReviewedContextAndReturnDraft() throws Exception {
        AtomicInteger requests=new AtomicInteger();AtomicReference<Object> body=new AtomicReference<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange -> {
            requests.incrementAndGet();body.set(Json.parse(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));
            byte[] response="{\"choices\":[{\"message\":{\"content\":\"git status\\n只读草稿\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });server.start();
        try(PluginHost host=new PluginHost(dir)) {
            Files.createDirectories(dir.resolve("desktop"));Files.writeString(dir.resolve("desktop/assistant.properties"),"endpoint=http://127.0.0.1:"+server.getAddress().getPort()+"/chat/completions\nmodel=local-test\ntoken-env=LAGLESS_TEST_UNUSED_TOKEN\n");
            host.output("session","remote text must stay local");assertEquals(0,requests.get());
            String result=host.plugins().get(0).assist("command","查看仓库状态","reviewed context only");
            assertTrue(result.contains("git status"));assertEquals(1,requests.get());assertEquals("local-test",Json.string(body.get(),"model"));assertTrue(Json.string(body.get(),"messages",1,"content").contains("reviewed context only"));
        }finally{server.stop(0);}
    }
    @Test void nonLocalCleartextEndpointIsRejectedBeforeNetwork() throws Exception {
        Files.createDirectories(dir.resolve("desktop"));Files.writeString(dir.resolve("desktop/assistant.properties"),"endpoint=http://example.invalid/chat/completions\nmodel=test\n");
        assertThrows(IllegalArgumentException.class,() -> new AssistantPlugin(dir).assist("ask","text","context"));
    }
    @Test void streamsMarkdownAndSendsImageAttachments() throws Exception {
        AtomicReference<Object> body=new AtomicReference<>();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{body.set(Json.parse(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));byte[] response=("data: {\"choices\":[{\"delta\":{\"content\":\"# Answer\\n\"}}]}\n\n"+"data: {\"choices\":[{\"delta\":{\"content\":\"**ready**\"}}]}\n\n"+"data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();});server.start();
        try{Files.createDirectories(dir.resolve("desktop"));Files.writeString(dir.resolve("desktop/assistant.properties"),"endpoint=http://127.0.0.1:"+server.getAddress().getPort()+"/chat/completions\nmodel=vision-local\n");
            StringBuilder streamed=new StringBuilder();new AssistantPlugin(dir).assistStreamFor(AssistantSettings.load(dir).current().id(),"vision-local","ask","inspect","tail", "auto", "English",List.of(new AssistantPlugin.Attachment("screen.png","image/png",new byte[]{1,2,3},null)),streamed::append);
            assertTrue(streamed.toString().contains("# Answer"));assertTrue(streamed.toString().contains("**ready**"));assertTrue(Json.stringify(body.get()).contains("data:image/png;base64,AQID"));assertEquals(Boolean.TRUE,Json.get(body.get(),"stream"));
        }finally{server.stop(0);}
    }

    @Test void extractsTextFromSearchablePdfAttachments() throws Exception {
        Path pdf=dir.resolve("brief.pdf");
        try(PDDocument document=new PDDocument()){
            PDPage page=new PDPage();document.addPage(page);
            try(PDPageContentStream content=new PDPageContentStream(document,page)){
                content.beginText();content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                content.newLineAtOffset(72,700);content.showText("Lagless PDF attachment support");content.endText();
            }
            document.save(pdf.toFile());
        }
        AssistantPlugin.Attachment attachment=AssistantAttachments.read(pdf);
        assertEquals("application/pdf",attachment.mime());
        assertTrue(attachment.text().contains("Lagless PDF attachment support"));
        assertNull(attachment.bytes());
    }
    @Test void readsTxtAndMarkdownReferencesAsUtf8Text() throws Exception {
        Path txt=dir.resolve("note.txt"),md=dir.resolve("guide.md");
        Files.writeString(txt,"中文说明",StandardCharsets.UTF_8);Files.writeString(md,"# 中文标题",StandardCharsets.UTF_8);
        assertEquals("text/plain",AssistantAttachments.read(txt).mime());
        assertEquals("中文说明",AssistantAttachments.read(txt).text());
        assertEquals("text/markdown",AssistantAttachments.read(md).mime());
        assertEquals("# 中文标题",AssistantAttachments.read(md).text());
    }
}
