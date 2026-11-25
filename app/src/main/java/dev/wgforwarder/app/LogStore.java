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
    private static final int LIMIT = 512 * 1024;
    private LogStore() { }

    static synchronized void add(Context context, String message) {
        try {
            File current = new File(context.getFilesDir(), "forwarder.log");
            File previous = new File(context.getFilesDir(), "forwarder.old.log");
            if (current.length() > LIMIT) {
                if (previous.exists()) previous.delete();
                current.renameTo(previous);
            }
            String safe = message.replace('\r', ' ').replace('\n', ' ');
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            try (FileOutputStream out = new FileOutputStream(current, true)) {
                out.write((ts + "  " + safe + "\n").getBytes(StandardCharsets.UTF_8));
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
