package dev.wgforwarder.app;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Allowed-domain list of a TCP rule. Each entry is an exact name ("example.com") or a
 * wildcard ("*.example.com", which matches subdomains only). The Go engine applies the
 * same rules to the TLS SNI or HTTP Host of each connection.
 */
final class DomainList {
    static final int MAX = 50;
    private static final String NAME =
            "^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$";

    private DomainList() { }

    /** Splits on new lines, commas, semicolons or spaces, then validates and removes duplicates. */
    static List<String> parse(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String raw : text.split("[\\s,;]+")) {
            if (raw.isEmpty()) continue;
            String pattern = normalize(raw);
            if (!valid(pattern)) {
                throw new IllegalArgumentException("허용 도메인 형식을 확인하세요: " + raw
                        + " (예: example.com 또는 *.example.com, https:// 및 포트 입력 금지)");
            }
            out.add(pattern);
        }
        if (out.size() > MAX) {
            throw new IllegalArgumentException("허용 도메인은 규칙마다 최대 " + MAX + "개까지 등록할 수 있습니다.");
        }
        return new ArrayList<>(out);
    }

    static String normalize(String raw) {
        String pattern = raw.trim().toLowerCase(Locale.ROOT);
        return pattern.endsWith(".") ? pattern.substring(0, pattern.length() - 1) : pattern;
    }

    static boolean valid(String pattern) {
        String name = pattern.startsWith("*.") ? pattern.substring(2) : pattern;
        return name.length() <= 253 && !name.matches("[0-9.]+") && name.matches(NAME);
    }
}
