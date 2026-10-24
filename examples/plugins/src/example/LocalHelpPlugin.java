package example;

import dev.lagless.desktop.LaglessPlugin;

/** Offline example: supplies drafts and explanations, never executes anything. */
public final class LocalHelpPlugin implements LaglessPlugin {
    @Override public String name() { return "本地命令帮助（示例）"; }
    @Override public String assist(String action, String text, String context) {
        String command = text.strip();
        if (command.startsWith("git")) return "git status\n查看当前分支和工作区修改；不会修改文件。\n这是草稿，需手动复制并审阅。";
        if (command.startsWith("ls")) return "ls -lah\n列出目录项、权限和大小；不会修改文件。\n这是草稿，需手动复制并审阅。";
        return "此离线示例只提供 git / ls 的固定帮助。需要翻译或通用命令建议时，请在 AI助手中选择已配置的服务。";
    }
}
