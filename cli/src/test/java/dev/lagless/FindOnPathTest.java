package dev.lagless;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FindOnPathTest {
    @Test void findsInstalledProgramsAndNeverStartsMissingOnes() {
        String shell = System.getProperty("os.name", "").startsWith("Windows") ? "cmd" : "sh";
        assertNotNull(Installation.findOnPath(shell), shell + " is on PATH");
        assertNull(Installation.findOnPath("lagless-no-such-program-" + System.nanoTime()));
        assertNull(Installation.findOnPath(""));
    }
}
