package dev.lagless.desktop;

import org.graalvm.nativeimage.ImageInfo;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Native-only bootstrap; the shared core has no GraalVM or GUI dependencies. */
public final class DesktopRuntime {
    private DesktopRuntime() { }
    public static boolean nativeImage() { return ImageInfo.inImageRuntimeCode(); }
    public static Path home() {
        if (nativeImage()) return Path.of(ProcessHandle.current().info().command()
                .orElseThrow(() -> new IllegalStateException("Cannot locate native executable"))).toAbsolutePath().getParent();
        return Path.of(System.getProperty("lagless.native.home", System.getProperty("user.dir"))).toAbsolutePath();
    }
    public static String version() {
        Properties properties = new Properties();
        try (InputStream in = DesktopRuntime.class.getResourceAsStream("build.properties")) {
            if (in == null) throw new IOException("Missing build version");
            properties.load(in); return properties.getProperty("version");
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    public static void bootstrap() {
        // Must precede Toolkit/GraphicsEnvironment initialization. Native executables
        // do not inherit the standard java.exe launcher's DPI-aware setting.
        System.setProperty("sun.java2d.dpiaware", "true");
        System.setProperty("sun.java2d.uiScale.enabled", "true");
        System.setProperty("swing.aatext", "true");
        System.setProperty("swing.bufferPerWindow", "true");
        if(System.getProperty("awt.useSystemAAFontSettings")==null)System.setProperty("awt.useSystemAAFontSettings", "lcd");
        if (nativeImage()) {
            // Use the stable software pipeline; D3D posts additional callbacks from
            // driver threads that are not covered by offscreen rendering metadata.
            System.setProperty("sun.java2d.d3d", "false");
            // The GDI screen-copy fast path can read beyond a Native Image pixel
            // buffer while Swing repaints (GDIBlitLoops.nativeBlit). Disabling D3D
            // alone does not disable it. Use the fallback screen-copy path.
            if (System.getProperty("os.name", "").startsWith("Windows")) {
                System.setProperty("sun.java2d.gdiBlit", "false");
            }
            System.setProperty("java.home", home().toString());
            System.setProperty("lagless.native.home", home().toString());
            System.setProperty("lagless.build.version", version());
            System.setProperty("lagless.native.image", "true");
        }
        // FlatLaf's optional platform enhancements are not required for rendering.
        System.setProperty("flatlaf.useNativeLibrary", "false");
        System.setProperty("file.encoding", "UTF-8");
    }
    public static Path applicationBackupSource() {
        return nativeImage() ? home() : dev.lagless.Installation.runningJar();
    }
}
