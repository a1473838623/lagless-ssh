package dev.lagless.desktop;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ArchiveTransferTest {
    @TempDir Path dir;
    private static final String LONG = "很长的文件夹名-" + "x".repeat(90);

    private Map<String, String> sample() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("site/index.html", "<h1>hi</h1>");
        files.put("site/" + LONG + "/中文.txt", "中文\n".repeat(300));
        files.put("site/empty.txt", "");
        files.put("site/big.bin", "z".repeat(70_000));
        return files;
    }

    @Test void writerAndReaderRoundTripLongUnicodeNamesAndEmptyFiles() throws Exception {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        ArchiveTransfer.TarWriter writer = new ArchiveTransfer.TarWriter(archive);
        writer.directory("site", 0);
        for (var file : sample().entrySet()) {
            byte[] data = file.getValue().getBytes(StandardCharsets.UTF_8);
            writer.file(file.getKey(), data.length, 1_700_000_000_000L);
            archive.write(data);
            writer.pad(data.length);
        }
        writer.finish();
        ArchiveTransfer.TarReader reader = new ArchiveTransfer.TarReader(new ByteArrayInputStream(archive.toByteArray()));
        Map<String, String> read = new LinkedHashMap<>();
        ArchiveTransfer.TarReader.Entry entry;
        while ((entry = reader.next()) != null) {
            if (entry.type() == ArchiveTransfer.TarReader.Type.DIRECTORY) { assertEquals("site/", entry.name()); continue; }
            assertEquals(ArchiveTransfer.TarReader.Type.FILE, entry.type());
            read.put(entry.name(), new String(reader.content().readAllBytes(), StandardCharsets.UTF_8));
            assertEquals(1_700_000_000_000L, entry.modified());
        }
        assertEquals(sample(), read);
    }

    @Test void systemTarReadsOursAndWeReadSystemTar() throws Exception {
        Assumptions.assumeTrue(new File("/bin/tar").exists() || new File("/usr/bin/tar").exists(), "no system tar");
        // Ours -> system tar.
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        ArchiveTransfer.TarWriter writer = new ArchiveTransfer.TarWriter(archive);
        writer.directory("site", 0);
        writer.directory("site/" + LONG, 0);
        for (var file : sample().entrySet()) {
            byte[] data = file.getValue().getBytes(StandardCharsets.UTF_8);
            writer.file(file.getKey(), data.length, 1_700_000_000_000L);
            archive.write(data);
            writer.pad(data.length);
        }
        writer.finish();
        Path ours = dir.resolve("ours.tar"), out = Files.createDirectories(dir.resolve("out"));
        Files.write(ours, archive.toByteArray());
        assertEquals(0, new ProcessBuilder("tar", "-xf", ours.toString(), "-C", out.toString()).inheritIO().start().waitFor());
        for (var file : sample().entrySet()) assertEquals(file.getValue(), Files.readString(out.resolve(file.getKey())));
        // System tar (GNU long names or pax) -> ours.
        Path theirs = dir.resolve("theirs.tar");
        Process tar = new ProcessBuilder("tar", "-cf", theirs.toString(), "-C", out.toString(), "site").inheritIO().start();
        assertTrue(tar.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, tar.exitValue());
        ArchiveTransfer.TarReader reader = new ArchiveTransfer.TarReader(Files.newInputStream(theirs));
        Map<String, String> read = new HashMap<>();
        ArchiveTransfer.TarReader.Entry entry;
        while ((entry = reader.next()) != null) if (entry.type() == ArchiveTransfer.TarReader.Type.FILE) read.put(entry.name(), new String(reader.content().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(sample(), read);
    }

    @Test void entriesMustStayInsideTheChosenFolder() throws Exception {
        Path destination = dir.resolve("site");
        assertEquals(destination, ArchiveTransfer.inside(destination, "site", "site/"));
        assertEquals(destination.resolve("a").resolve("b.txt"), ArchiveTransfer.inside(destination, "site", "./site/a/b.txt"));
        for (String bad : new String[] {"other/x", "site/../x", "/etc/passwd", "siteX/a", "site/a/../../x"})
            assertThrows(IOException.class, () -> ArchiveTransfer.inside(destination, "site", bad), bad);
        assertEquals("'it'\\''s'", ArchiveTransfer.quote("it's"));
        assertEquals("/srv", ArchiveTransfer.parent("/srv/www"));
        assertEquals("/", ArchiveTransfer.parent("/www"));
        assertEquals("www", ArchiveTransfer.name("/srv/www"));
    }
}
