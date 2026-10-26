package dev.lagless.desktop;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class TransferQueueTest {
    @Test void progressMovesVisiblyForLargeFoldersAndNamesBothEnds(){
        TransferQueue.Job download=new TransferQueue.Job(false,Path.of("/tmp/target/logs"),"/var/log",0);
        assertEquals("/var/log",download.from());assertEquals(Path.of("/tmp/target/logs").toAbsolutePath().toString(),download.to());
        download.state=TransferQueue.State.RUNNING;download.total=10_000_000_000L;download.bytes=40_000_000L;
        assertEquals("0.4%",download.progressText(),"small progress on a big folder is not shown as 0%");
        download.bytes=2_500_000_000L;assertEquals("25%",download.progressText());
        download.state=TransferQueue.State.COMPLETE;assertEquals("100%",download.progressText());
        TransferQueue.Job upload=new TransferQueue.Job(true,Path.of("/home/me/site"),"/srv/www/site",0);
        assertEquals("/srv/www/site",upload.to());assertTrue(upload.from().endsWith("site"));
    }
}
