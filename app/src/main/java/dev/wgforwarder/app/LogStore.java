package dev.wgforwarder.app;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class LogStore {
    private static final int LIMIT = 32 * 1024; // current and previous files: <= 64 KiB total
    private LogStore() { }

    static synchronized void add(Context context, String message) {
        try {
            File current = new File(context.getFilesDir(), "forwarder.log");
            File previous = new File(context.getFilesDir(), "forwarder.old.log");
            String safe = message.replace('\r', ' ').replace('\n', ' ');
            if (safe.length() > 500) safe = safe.substring(0, 500) + "…";
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            byte[] line = (ts + "  " + safe + "\n").getBytes(StandardCharsets.UTF_8);
            if (current.length() + line.length > LIMIT) {
                if (previous.exists()) previous.delete();
                if (!current.renameTo(previous)) {
                    // If rotation fails, never allow an unbounded log file.
                    try (FileOutputStream ignored = new FileOutputStream(current, false)) { }
                }
            }
            try (FileOutputStream out = new FileOutputStream(current, true)) {
                out.write(line);
            }
        } catch (Exception ignored) { }
    }

    static String read(Context context) {
        try {
            File f = new File(context.getFilesDir(), "forwarder.log");
            if (!f.isFile()) return "로그가 없습니다.";
            byte[] bytes = Files.readAllBytes(f.toPath());
            int start = Math.max(0, bytes.length - 14000);
            return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
        } catch (Exception e) { return "로그를 읽을 수 없습니다."; }
    }
}
