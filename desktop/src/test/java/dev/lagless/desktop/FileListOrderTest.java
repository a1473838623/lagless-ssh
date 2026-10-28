package dev.lagless.desktop;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FileListOrderTest {
    private static List<String> sorted(TransferDialog.SortKey key, boolean descending) {
        List<SftpTransfers.Entry> entries = new ArrayList<>(List.of(
                new SftpTransfers.Entry("b.txt", false, false, 300, true, 2_000),
                new SftpTransfers.Entry("A.log", false, false, 100, true, 3_000),
                new SftpTransfers.Entry("zfolder", true, false, 4096, false, 1_000),
                new SftpTransfers.Entry("c.bin", false, false, 200, true, 1_000),
                new SftpTransfers.Entry("afolder", true, false, 4096, false, 5_000)));
        entries.sort(TransferDialog.order(key, descending, SftpTransfers.Entry::directory, SftpTransfers.Entry::name,
                e -> e.directory() ? -1 : e.size(), SftpTransfers.Entry::modified, e -> -1));
        return entries.stream().map(SftpTransfers.Entry::name).toList();
    }

    @Test void foldersComeFirstThenTheChosenKey() {
        assertEquals(List.of("afolder", "zfolder", "A.log", "b.txt", "c.bin"), sorted(TransferDialog.SortKey.NAME, false));
        assertEquals(List.of("afolder", "zfolder", "A.log", "c.bin", "b.txt"), sorted(TransferDialog.SortKey.SIZE, false));
        assertEquals(List.of("afolder", "zfolder", "b.txt", "c.bin", "A.log"), sorted(TransferDialog.SortKey.SIZE, true));
        assertEquals(List.of("zfolder", "afolder", "c.bin", "b.txt", "A.log"), sorted(TransferDialog.SortKey.MODIFIED, false));
        assertEquals(List.of("afolder", "zfolder", "A.log", "b.txt", "c.bin"), sorted(TransferDialog.SortKey.MODIFIED, true));
        // No creation time over SFTP: everything ties and stays in name order.
        assertEquals(List.of("afolder", "zfolder", "A.log", "b.txt", "c.bin"), sorted(TransferDialog.SortKey.CREATED, false));
    }
}
