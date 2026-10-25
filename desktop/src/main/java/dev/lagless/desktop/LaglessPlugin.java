package dev.lagless.desktop;

import dev.lagless.I18n;
/** ServiceLoader SPI. Plugins are trusted local Java code and run off the terminal path. */
public interface LaglessPlugin extends AutoCloseable {
    /** Optional local JSON object supplied when the plugin is loaded. */
    default void configure(java.util.Map<String,Object> settings) throws Exception { }
    String name();
    default void onOutput(String sessionId, String text) throws Exception { }
    @Override default void close() { }
    /** User initiated actions only. Return a draft; plugins never receive an execution handle. */
    default String assist(String action, String text, String context) throws Exception {
        throw new UnsupportedOperationException(I18n.t("此插件不支持 ","This plugin does not support ") + action);
    }
}
