package dev.lagless;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptDetectorTest {

    @Test
    void learnsBashPromptAndFollowsDirectoryChanges() {
        PromptDetector d = new PromptDetector(null);
        assertTrue(d.looksLikePrompt("ubuntu@box:~$ "));
        assertTrue(d.isLearned());
        assertTrue(d.looksLikePrompt("ubuntu@box:/etc/nginx$ "));
        assertFalse(d.looksLikePrompt("Password: "));
        assertFalse(d.looksLikePrompt("> "));
        assertFalse(d.looksLikePrompt(">>> "));
        assertFalse(d.looksLikePrompt("Continue? [y/n] "));
    }

    @Test
    void promptGluedToOutputWithoutNewline() {
        PromptDetector d = new PromptDetector(null);
        assertTrue(d.looksLikePrompt("ubuntu@box:~$ "));
        assertTrue(d.looksLikePrompt("sftp round trip 42ubuntu@box:~$ "));
        assertFalse(d.looksLikePrompt("some output$ "));
    }

    @Test
    void rootPrompt() {
        PromptDetector d = new PromptDetector(null);
        assertTrue(d.looksLikePrompt("[root@host ~]# "));
        assertTrue(d.looksLikePrompt("[root@host /var/log]# "));
        assertFalse(d.looksLikePrompt("[sudo] password for root: "));
    }

    @Test
    void doesNotLearnFromOrdinaryOutput() {
        PromptDetector d = new PromptDetector(null);
        assertFalse(d.looksLikePrompt("Last login: Mon Sep 15 03:00:00 2026 from 1.2.3.4"));
        assertFalse(d.looksLikePrompt(""));
        assertFalse(d.isLearned());
    }

    @Test
    void doesNotLearnBareContinuationPrompt() {
        PromptDetector d = new PromptDetector(null);
        assertFalse(d.looksLikePrompt("> "));
        assertFalse(d.isLearned());
    }

    @Test
    void userRegexWins() {
        PromptDetector d = new PromptDetector("\\$ $");
        assertTrue(d.looksLikePrompt("anything $ "));
        assertFalse(d.looksLikePrompt("anything # "));
    }

    @Test
    void forgetAllowsRelearning() {
        PromptDetector d = new PromptDetector(null);
        assertTrue(d.looksLikePrompt("a$ "));
        assertFalse(d.looksLikePrompt("b# "));
        d.forget();
        assertTrue(d.looksLikePrompt("b# "));
        assertFalse(d.looksLikePrompt("a$ "));
    }
}
