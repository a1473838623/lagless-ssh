package dev.lagless.build;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Properties;
import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeClassInitialization;

/** Compiler check: verify the security data that is captured in the native heap. */
public final class SecurityBlocklistFeature implements Feature {
    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        try {
            Path source = Path.of(System.getProperty("lagless.build.blocklist"));
            String expectedHash = System.getProperty("lagless.build.blocklist.sha256");
            String actualHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
            if (!actualHash.equals(expectedHash)) throw new IllegalStateException("Certificate blocklist checksum mismatch");
            Properties expected = new Properties();
            try (InputStream input = Files.newInputStream(source)) { expected.load(input); }
            RuntimeClassInitialization.initializeAtBuildTime("sun.security.util.UntrustedCertificates");
            Class<?> type = Class.forName("sun.security.util.UntrustedCertificates");
            Field field = type.getDeclaredField("props");
            field.setAccessible(true);
            Properties captured = (Properties) field.get(null);
            if (!"SHA-256".equals(expected.getProperty("Algorithm")) || !expected.equals(captured)) {
                throw new IllegalStateException("Native Image did not load the complete official certificate blocklist");
            }
            Path report = Path.of(System.getProperty("lagless.build.blocklist.report"));
            Files.createDirectories(report.getParent());
            Files.writeString(report, "{\n  \"phase\": \"native-image-before-analysis\",\n  \"initializedAtBuildTime\": true,\n  \"sha256\": \""
                    + actualHash + "\",\n  \"fingerprints\": " + (captured.size() - 1) + "\n}\n", StandardCharsets.UTF_8);
            System.out.println("Lagless: captured official certificate blocklist (" + (captured.size() - 1) + " fingerprints, SHA-256 " + actualHash + ")");
        } catch (Exception error) {
            throw new IllegalStateException("Cannot embed the required certificate security data", error);
        }
    }
}
