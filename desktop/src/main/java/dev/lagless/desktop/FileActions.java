package dev.lagless.desktop;

import com.jcraft.jsch.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** File mutations use explicit children and never follow directory links. */
final class FileActions {
    static Path localChild(Path directory,String name)throws IOException{
        SftpTransfers.validName(name);Path root=directory.toAbsolutePath().normalize(),target=root.resolve(name).normalize();
        if(!root.equals(target.getParent()))throw new IOException("文件不在当前目录");return target;
    }
    static void localCopy(Path source,Path target)throws IOException{
        source=source.toAbsolutePath().normalize();target=target.toAbsolutePath().normalize();
        if(target.startsWith(source))throw new IOException("不能复制到自身或子目录");
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))throw new FileAlreadyExistsException(target.toString());
        Path root=source,destination=target;
        Files.walkFileTree(root,new SimpleFileVisitor<>(){
            private int count;
            private void check(Path file)throws IOException{if(++count>100000||root.relativize(file).getNameCount()>64)throw new IOException("目录过大，请分批操作");if(Files.isSymbolicLink(file)||redirected(file))throw new IOException("复制不跟随目录链接："+file);}
            public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs)throws IOException{check(dir);Files.createDirectory(destination.resolve(root.relativize(dir)));return FileVisitResult.CONTINUE;}
            public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException{check(file);Files.copy(file,destination.resolve(root.relativize(file)),StandardCopyOption.COPY_ATTRIBUTES);return FileVisitResult.CONTINUE;}
        });
    }
    static void localDelete(Path source)throws IOException{
        Path root=source.toAbsolutePath().normalize();if(root.getParent()==null)throw new IOException("不能删除根目录");
        Files.walkFileTree(root,new SimpleFileVisitor<>(){
            private void check(Path target)throws IOException{if(!target.toAbsolutePath().normalize().startsWith(root))throw new IOException("删除路径越界");}
            public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs)throws IOException{check(dir);if(redirected(dir))throw new IOException("不递归删除目录链接："+dir);return FileVisitResult.CONTINUE;}
            public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException{check(file);Files.delete(file);return FileVisitResult.CONTINUE;}
            public FileVisitResult postVisitDirectory(Path dir,IOException error)throws IOException{if(error!=null)throw error;check(dir);Files.delete(dir);return FileVisitResult.CONTINUE;}
        });
    }
    private static boolean redirected(Path path)throws IOException{return !path.toRealPath().equals(path.toRealPath(LinkOption.NOFOLLOW_LINKS));}
    static void remoteDelete(ChannelSftp channel,String source)throws Exception{remoteDelete(channel,source,0,new int[1]);}
    private static void remoteDelete(ChannelSftp channel,String source,int depth,int[] count)throws Exception{
        if(!source.startsWith("/")||source.equals("/")||source.contains("/../")||source.endsWith("/.."))throw new IOException("删除路径无效");
        if(depth>64||++count[0]>100000)throw new IOException("目录过大，请分批操作");
        SftpATTRS a=channel.lstat(source);if(a.isDir()&&!a.isLink()){
            for(var item:SftpTransfers.list(channel,source))remoteDelete(channel,SftpTransfers.child(source,item.name()),depth+1,count);channel.rmdir(source);
        }else channel.rm(source);
    }
    static boolean remoteExists(ChannelSftp channel,String path)throws SftpException{
        try{channel.lstat(path);return true;}catch(SftpException e){if(e.id==ChannelSftp.SSH_FX_NO_SUCH_FILE)return false;throw e;}
    }
    static String uniqueName(String original,int copy){int dot=original.lastIndexOf('.');String suffix=" - 副本"+(copy>1?" ("+copy+")":"");return dot>0?original.substring(0,dot)+suffix+original.substring(dot):original+suffix;}
    static String localSignature(Path source)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");Path root=source.toAbsolutePath().normalize();
        SortedMap<String,String> entries=new TreeMap<>();
        Files.walkFileTree(root,new SimpleFileVisitor<>(){
            private void add(Path path,BasicFileAttributes a)throws IOException{if(entries.size()>=100000||root.relativize(path).getNameCount()>64||Files.isSymbolicLink(path)||redirected(path))throw new IOException("剪切不支持链接或过大目录");String name=root.relativize(path).toString();entries.put(name,name+":"+a.isDirectory()+":"+a.size()+":"+a.lastModifiedTime()+"\n");}
            public FileVisitResult preVisitDirectory(Path dir,BasicFileAttributes attrs)throws IOException{add(dir,attrs);return FileVisitResult.CONTINUE;}
            public FileVisitResult visitFile(Path file,BasicFileAttributes attrs)throws IOException{add(file,attrs);return FileVisitResult.CONTINUE;}
        });
        for(String value:entries.values())digest.update(value.getBytes(StandardCharsets.UTF_8));return HexFormat.of().formatHex(digest.digest());
    }
    static String remoteSignature(ChannelSftp channel,String source)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");remoteSignature(channel,source,digest,0,new int[1]);return HexFormat.of().formatHex(digest.digest());
    }
    private static void remoteSignature(ChannelSftp channel,String source,MessageDigest digest,int depth,int[] count)throws Exception{
        if(depth>64||++count[0]>100000)throw new IOException("剪切目录过大");SftpATTRS a=channel.lstat(source);if(a.isLink())throw new IOException("剪切传输不跟随符号链接");
        digest.update((source+":"+a.isDir()+":"+a.getSize()+":"+a.getMTime()+"\n").getBytes(StandardCharsets.UTF_8));
        if(a.isDir())for(var item:SftpTransfers.list(channel,source))remoteSignature(channel,SftpTransfers.child(source,item.name()),digest,depth+1,count);
    }
}
