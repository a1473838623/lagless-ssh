package dev.lagless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshConfigFileTest {

    @TempDir
    Path dir;

    private Path write(String content) throws Exception {
        Path p = dir.resolve("config");
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    void parsesBlocksAndPreamble() throws Exception {
        Path p = write(String.join("\n",
                "# global",
                "ServerAliveInterval 30",
                "",
                "  Host 192.0.2.47",
                "    HostName 192.0.2.47",
                "    IdentityFile C:/keys/a.pem",
                "    User root",
                "",
                "Host ubuntu",
                "    HostName 192.0.2.97",
                "    User root",
                "    Port 2222",
                "",
                "Host *",
                "    Compression yes",
                ""));
        SshConfigFile cfg = new SshConfigFile(p).load();
        assertEquals(3, cfg.hosts().size());
        List<SshConfigFile.HostBlock> concrete = cfg.concreteHosts();
        assertEquals(2, concrete.size());
        assertEquals("192.0.2.47", concrete.get(0).name());
        assertEquals("root", concrete.get(0).option("User"));
        assertEquals("C:/keys/a.pem", concrete.get(0).option("IdentityFile"));
        assertEquals("root@192.0.2.97:2222", SshConfigFile.describe(concrete.get(1)));
        assertNull(cfg.find("nope"));
        assertNotNull(cfg.find("ubuntu"));
    }

    @Test
    void addRemoveAndSaveKeepOtherText() throws Exception {
        Path p = write("# keep me\n\nHost a\n    HostName 1.1.1.1\n\nHost *\n    Compression yes\n");
        SshConfigFile cfg = new SshConfigFile(p).load();
        cfg.add("b", "2.2.2.2", "root", 2200, "C:\\keys\\b.pem");
        cfg.save();
        String saved = Files.readString(p, StandardCharsets.UTF_8);
        assertTrue(saved.startsWith("# keep me"));
        assertTrue(saved.contains("Host b"));
        assertTrue(saved.contains("    IdentityFile C:/keys/b.pem"));
        assertTrue(saved.contains("    Port 2200"));
        assertTrue(saved.contains("Host *"));
        assertTrue(Files.exists(dir.resolve("config.bak")));

        cfg = new SshConfigFile(p).load();
        assertTrue(cfg.remove("a"));
        assertFalse(cfg.remove("a"));
        cfg.save();
        saved = Files.readString(p, StandardCharsets.UTF_8);
        assertFalse(saved.contains("Host a"));
        assertTrue(saved.contains("Host b"));
        assertTrue(saved.contains("Host *"));
    }

    @Test
    void addReplacesExistingEntry() throws Exception {
        Path p = write("Host a\n    HostName 1.1.1.1\n    User old\n");
        SshConfigFile cfg = new SshConfigFile(p).load();
        cfg.add("a", "9.9.9.9", "new", 22, null);
        cfg.save();
        String saved = Files.readString(p, StandardCharsets.UTF_8);
        assertEquals(1, saved.split("Host a").length - 1);
        assertTrue(saved.contains("User new"));
        assertFalse(saved.contains("User old"));
        assertFalse(saved.contains("Port"));
    }
}
