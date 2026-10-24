package dev.lagless.desktop;

import java.util.regex.Pattern;

/** Review heuristic, not a shell sandbox; arbitrary scripts can always hide side effects. */
public final class CommandSafety {
    private static final Pattern RISK = Pattern.compile("(?is)(\\brm\\b.*(?:-[a-z]*[rf]|--recursive|--force)|\\b(?:mkfs(?:\\.[a-z0-9]+)?|fdisk|parted|wipefs|shutdown|reboot|poweroff)\\b|\\bdd\\b.*\\bof\\s*=|>\\s*/dev/|\\b(?:curl|wget)\\b.*\\|.*\\b(?:sh|bash|zsh)\\b|\\bchmod\\b.*(?:777|-[a-z]*R)|\\b(?:drop\\s+(?:database|table)|truncate\\s+table)\\b|:\\s*\\(\\s*\\)\\s*\\{|\\b(?:sudo|su)\\b)");
    private CommandSafety() { }
    public static boolean needsReview(String command) { return RISK.matcher(command).find(); }
}
