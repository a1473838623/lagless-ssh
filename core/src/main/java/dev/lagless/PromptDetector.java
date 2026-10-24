package dev.lagless;

import java.util.regex.Pattern;

/**
 * Decides whether the last line of remote output looks like the shell prompt.
 *
 * <p>The detector learns the prompt from the first quiet line that ends like a
 * shell prompt and from then on only accepts lines with the same ending. That
 * keeps password prompts ("Password: "), continuation prompts ("> ") and
 * program questions ("Continue? [y/n] ") in raw mode, while a prompt whose
 * middle changes (current directory, git branch, time) is still recognised.
 */
public final class PromptDetector {

    private static final Pattern DEFAULT_ENDING = Pattern.compile("(?:[$#%]|❯|➜|»|>)\\s?$");

    private final Pattern userPattern;
    private volatile String learnedSuffix;
    private volatile String learnedPrefix;

    /**
     * @param userRegex optional regex supplied by the user; when set it is the
     *                  only rule and nothing is learned.
     */
    public PromptDetector(String userRegex) {
        this.userPattern = userRegex == null || userRegex.isBlank() ? null : Pattern.compile(userRegex);
    }

    public boolean isLearned() {
        return userPattern != null || learnedSuffix != null;
    }

    public String describe() {
        if (userPattern != null) {
            return "regex " + userPattern.pattern();
        }
        if (learnedSuffix == null) {
            return "(not learned yet)";
        }
        return "prefix=\"" + learnedPrefix + "\" suffix=\"" + learnedSuffix + "\"";
    }

    /**
     * Called with the stripped text of the last output line once output has
     * been quiet for a while. Returns true when the line should be treated as
     * a shell prompt and local editing should start.
     */
    public boolean looksLikePrompt(String line) {
        if (line == null) {
            return false;
        }
        String trimmedRight = stripTrailingSpaces(line);
        if (trimmedRight.isEmpty()) {
            return false;
        }
        if (userPattern != null) {
            return userPattern.matcher(line).find();
        }
        if (learnedSuffix != null) {
            return matchesLearned(line);
        }
        if (DEFAULT_ENDING.matcher(line).find() && !looksLikeContinuation(line)) {
            learn(line);
            return true;
        }
        return false;
    }

    /** Force-learn the given line as the prompt (used by the manual hotkey). */
    public void learn(String line) {
        if (userPattern != null || line == null) {
            return;
        }
        String trimmedRight = stripTrailingSpaces(line);
        if (trimmedRight.isEmpty()) {
            return;
        }
        // Suffix: the final non-space character plus whatever spaces follow it.
        learnedSuffix = line.substring(trimmedRight.length() - 1);
        // Prefix: text up to the first space or digit, capped at 4 chars. Prompts
        // like "[root@host ~]# " keep "[roo", "user@host:~$ " keeps "user".
        int cut = 0;
        while (cut < line.length() && cut < 4) {
            char c = line.charAt(cut);
            if (c == ' ' || Character.isDigit(c)) {
                break;
            }
            cut++;
        }
        learnedPrefix = line.substring(0, cut);
    }

    public void forget() {
        learnedPrefix = null;
        learnedSuffix = null;
    }

    private boolean matchesLearned(String line) {
        String suffix = learnedSuffix;
        String prefix = learnedPrefix;
        if (suffix == null) {
            return false;
        }
        if (!line.endsWith(suffix)) {
            return false;
        }
        // The prefix need not be at column 0: output without a trailing newline
        // ("printf x", "cat" of a file without one) leaves the prompt glued to
        // the end of the last output line.
        return prefix == null || prefix.isEmpty() || line.contains(prefix);
    }

    private static boolean looksLikeContinuation(String line) {
        // bash/zsh PS2 is "> " by default: a bare "> " must never be learned as
        // the primary prompt.
        return line.trim().equals(">");
    }

    private static String stripTrailingSpaces(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }
}
