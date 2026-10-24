package dev.lagless.desktop;

import dev.lagless.Json;
import org.apache.sshd.server.*;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.util.*;
import java.util.concurrent.*;

/** Build-only loopback SSH/AI fixture; excluded from every distributed artifact. */
public final class NativeTestServer {
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]).toAbsolutePath();Files.createDirectories(root);List<String> commands=new CopyOnWriteArrayList<>();
        SshServer server=SshServer.setUpDefaultServer();server.setHost("127.0.0.1");server.setPort(0);
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);server.setKeyPairProvider(KeyPairProvider.wrap(generator.generateKeyPair()));
        server.setPasswordAuthenticator((u,p,s)->!u.equals("key-tester")&&p.equals("test-password"));server.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        server.setPublickeyAuthenticator((u,k,s)->u.equals("key-tester"));
        var identity=com.jcraft.jsch.KeyPair.genKeyPair(new com.jcraft.jsch.JSch(),com.jcraft.jsch.KeyPair.RSA,2048);
        try{identity.writePrivateKey(root.resolve("identity").toString(),"test-password".getBytes(StandardCharsets.UTF_8));}finally{identity.dispose();}
        server.setFileSystemFactory(new org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory(root));server.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder().build()));server.setShellFactory(channel->new Shell(commands));server.start();
        HttpServer http=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        http.createContext("/",exchange->{String path=exchange.getRequestURI().getPath();String text="";if(path.equals("/drop")){server.getActiveSessions().forEach(s->s.close(true));}
            else if(path.equals("/commands"))text=String.join("\n",commands);
            else if(path.equals("/ai")){exchange.getRequestBody().readAllBytes();text="{\"choices\":[{\"message\":{\"content\":\"你好\"}}]}";}
            byte[] bytes=text.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length==0?-1:bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}});http.start();
        Files.writeString(root.resolve("fixture.json"),Json.stringify(Map.of("ssh-port",server.getPort(),"identity",root.resolve("identity").toString(),"http","http://127.0.0.1:"+http.getAddress().getPort())));
        Runtime.getRuntime().addShutdownHook(new Thread(()->{http.stop(0);try{server.stop(true);}catch(Exception ignored){}}));new CountDownLatch(1).await();
    }
    private static final class Shell implements Command,Runnable {
        private final List<String> commands;private InputStream input;private OutputStream output;private ExitCallback exit;private Thread thread;
        Shell(List<String> commands){this.commands=commands;}
        public void setInputStream(InputStream in){input=in;}public void setOutputStream(OutputStream out){output=out;}public void setErrorStream(OutputStream out){}public void setExitCallback(ExitCallback e){exit=e;}
        public void start(ChannelSession c,Environment e){thread=new Thread(this,"native-fixture-shell");thread.setDaemon(true);thread.start();}
        public void destroy(ChannelSession c)throws Exception{input.close();thread.interrupt();}
        public void run(){try{write("test$ ");StringBuilder line=new StringBuilder();int b;while((b=input.read())!=-1){if(b==21){line.setLength(0);continue;}if(b==3){line.setLength(0);write("^C\r\ntest$ ");continue;}if(b==4)break;if(b=='\r'){String command=line.toString();line.setLength(0);commands.add(command);Thread.sleep(600);write(command+"\r\n[executed] "+command+"\r\ntest$ ");}else line.append((char)b);}}catch(Exception ignored){}finally{exit.onExit(0);}}
        private void write(String text)throws IOException{output.write(text.getBytes(StandardCharsets.UTF_8));output.flush();}
    }
}
