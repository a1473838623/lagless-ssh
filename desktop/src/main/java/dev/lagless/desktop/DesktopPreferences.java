package dev.lagless.desktop;

import java.nio.file.*;
import java.io.*;
import java.util.Properties;

final class DesktopPreferences {
    private final Path file;private final Properties values=new Properties();
    DesktopPreferences(Path home)throws IOException{file=home.resolve("desktop/ui.properties");if(Files.isRegularFile(file))try(var in=Files.newBufferedReader(file)){values.load(in);}}
    synchronized String get(String key,String fallback){return values.getProperty(key,fallback);}
    synchronized void set(String key,String value){values.setProperty(key,value);try{StringWriter text=new StringWriter();values.store(text,"Lagless workspace");ProfileStore.atomicWrite(file,text.toString());}catch(IOException error){error.printStackTrace(System.err);}}
}
