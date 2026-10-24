package dev.lagless;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaglessConfigTest {

    @Test
    void defaultsAndHostOverrides() {
        LaglessConfig c = new LaglessConfig();
        c.parse(List.of(
                "# comment",
                "quiet-ms = 150",
                "completion = true",
                "",
                "[myhost]",
                "prompt-regex = \\$ $",
                "quiet-ms=300",
                "[other]",
                "completion = false"));
        assertEquals("150", c.get(null, "quiet-ms"));
        assertEquals("300", c.get("myhost", "quiet-ms"));
        assertEquals("\\$ $", c.get("myhost", "prompt-regex"));
        assertNull(c.get("other", "prompt-regex"));
        assertEquals("false", c.get("other", "completion"));

        Options o = new Options();
        c.applyTo(o, "myhost");
        assertEquals(300, o.quietMs);
        assertEquals("\\$ $", o.promptRegex);
        assertTrue(o.completion);

        Options explicit = Options.parse(new String[] {"--quiet-ms", "50", "--prompt-regex", "x", "myhost"});
        c.applyTo(explicit, "myhost");
        assertEquals(50, explicit.quietMs);
        assertEquals("x", explicit.promptRegex);

        Options other = new Options();
        c.applyTo(other, "other");
        assertFalse(other.completion);
    }

    @Test
    void subCommandParsing() {
        Options o = Options.parse(new String[] {"add", "box", "root@1.2.3.4:2222", "-i", "k.pem"});
        assertEquals("add", o.command);
        assertEquals(List.of("box", "root@1.2.3.4:2222"), o.commandArgs);
        assertEquals("k.pem", o.identity);

        Options dest = Options.parse(new String[] {"root@1.2.3.4:2222"});
        assertNull(dest.command);
        assertEquals("root", dest.user);
        assertEquals("1.2.3.4", dest.host);
        assertEquals(2222, dest.port);

        Options none = Options.parse(new String[0]);
        assertNull(none.command);
        assertNull(none.host);
    }

}
