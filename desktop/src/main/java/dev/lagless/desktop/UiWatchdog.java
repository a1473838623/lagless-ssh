package dev.lagless.desktop;

import dev.lagless.I18n;
import java.awt.EventQueue;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Notices when the UI thread stops responding (the window then stays black or frozen)
 * and writes what that thread is doing, and how long it was stuck, to the application
 * log. It only observes: a ping is posted to the event queue every half second.
 */
final class UiWatchdog {
    static final long STUCK_NANOS = 3_000_000_000L;
    private UiWatchdog() { }

    static void start(Consumer<String> log) {
        AtomicLong answered = new AtomicLong(System.nanoTime());
        Thread watcher = new Thread(() -> {
            boolean reported = false;
            long stuckSince = 0;
            while (true) {
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                EventQueue.invokeLater(() -> answered.set(System.nanoTime()));
                long now = System.nanoTime(), silent = now - answered.get();
                if (silent > STUCK_NANOS && !reported) {
                    reported = true;
                    stuckSince = answered.get();
                    log.accept(I18n.t("界面线程无响应超过 ", "The UI thread has not responded for ") + silent / 1_000_000_000L + I18n.t(" 秒；正在执行：\n", " s; it is running:\n") + eventThreadStack());
                } else if (reported && silent < 1_000_000_000L) {
                    reported = false;
                    log.accept(I18n.t("界面线程已恢复，停顿约 ", "The UI thread recovered after about ") + (answered.get() - stuckSince) / 1_000_000_000L + I18n.t(" 秒", " s"));
                }
            }
        }, "lagless-ui-watchdog");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static String eventThreadStack() {
        StringBuilder text = new StringBuilder();
        Map<Thread, StackTraceElement[]> all;
        try { all = Thread.getAllStackTraces(); } catch (Throwable unavailable) { return I18n.t("（无法读取调用栈）", "(stack not available)"); }
        for (Map.Entry<Thread, StackTraceElement[]> entry : all.entrySet()) {
            if (!entry.getKey().getName().startsWith("AWT-EventQueue")) continue;
            text.append(entry.getKey().getName()).append(" (").append(entry.getKey().getState()).append(")\n");
            StackTraceElement[] frames = entry.getValue();
            for (int i = 0; i < Math.min(40, frames.length); i++) text.append("    at ").append(frames[i]).append('\n');
        }
        return text.isEmpty() ? I18n.t("（无法读取调用栈）", "(stack not available)") : text.toString();
    }
}
