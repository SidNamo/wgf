package dev.wgforwarder.app;

import android.content.Context;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LogStoreTest {
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("wgf-log-test-");
        try {
            Context ctx = new Context(temp.toFile());
            for (int i = 0; i < 6000; i++) {
                LogStore.add(ctx, "test event " + i + " xyz0123456789");
            }
            File current = temp.resolve("forwarder.log").toFile();
            File previous = temp.resolve("forwarder.old.log").toFile();
            if (!current.exists() || !previous.exists()) throw new AssertionError("Log rotation missing");
            if (current.length() > 32768 || previous.length() > 32768)
                throw new AssertionError("Unbounded logs: " + current.length() + ", " + previous.length());
            LogStore.add(ctx, "final marker");
            if (!LogStore.read(ctx).contains("final marker"))
                throw new AssertionError("Latest entry missing");
            LogStore.add(ctx, "x".repeat(100000));
            if (current.length() > 32768) throw new AssertionError("Oversized entry not limited");
            System.out.println("LogStoreTest passed");
        } finally {
            try (var paths = Files.walk(temp)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) { }
                });
            }
        }
    }
}
