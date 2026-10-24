package dev.lagless.desktop;

import dev.lagless.Json;
import java.nio.file.*;
import java.util.*;

/** Native desktop entry, independent from the terminal-only launcher. */
public final class NativeDesktopMain {
    private NativeDesktopMain() { }
    public static void main(String[] args) {
        int code = 0;
        try {
            DesktopRuntime.bootstrap();
            if (args.length == 0 || Arrays.equals(args,new String[]{"gui"})) {
                if(NativeUpdater.handoffPendingLaunch()){System.exit(0);return;}
                DesktopApp.launch();return;
            }
            switch (args[0]) {
                case "--version" -> System.out.println("Lagless " + DesktopRuntime.version() + " desktop-native");
                case "--verify" -> NativeVerification.run(Path.of(args[1]));
                case "--apply-update" -> NativeUpdater.applyRequest(Path.of(args[1]));
                case "--restart-after-update" -> NativeUpdater.restartAfterUpdate(Path.of(args[1]));
                case "--install-update" -> NativeUpdater.stage(DesktopRuntime.home(),Path.of(args[1]),true);
                case "--rollback" -> NativeUpdater.rollback();
                case "--plugin-example" -> ProcessPluginExample.run();
                case "--diagnostics" -> System.out.println(Json.stringify(Map.of("version",DesktopRuntime.version(),
                        "native",DesktopRuntime.nativeImage(),"home",DesktopRuntime.home().toString(),
                        "os",System.getProperty("os.name"),"arch",System.getProperty("os.arch"))));
                default -> throw new IllegalArgumentException("Unknown option: " + args[0]);
            }
        } catch (Throwable error) {
            error.printStackTrace(System.err);code=1;
            if(args.length>0&&args[0].equals("--restart-after-update"))try{DesktopApp.theme();javax.swing.JOptionPane.showMessageDialog(null,error.getMessage(),"Lagless 更新未完成",javax.swing.JOptionPane.ERROR_MESSAGE);}catch(Throwable uiError){uiError.printStackTrace(System.err);}
        }
        // Diagnostic tools must also terminate AWT threads after a failed check.
        System.exit(code);
    }
}
