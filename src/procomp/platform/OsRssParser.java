package procomp.platform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure parsers for batch OS process RSS outputs: Windows {@code tasklist /FO CSV /NH}
 * and POSIX {@code ps -eo pid=,rss=}.
 */
final class OsRssParser {

    private static final long BYTES_PER_KIB = 1024L;

    private OsRssParser() {}

    static Map<Long, Long> parseWindowsTasklistCsv(String output) {
        Objects.requireNonNull(output, "output must not be null");
        Map<Long, Long> result = new LinkedHashMap<>();
        for (String rawLine : output.split("\\R", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty() || !line.startsWith("\"")) {
                continue;
            }
            List<String> fields = parseCsvLine(line);
            if (fields.size() < 5) {
                continue;
            }
            long pid = parseLongWithMin(fields.get(1).trim(), 1L);
            if (pid <= 0) {
                continue;
            }
            long kib = parseDigitsAsNonNegativeLong(fields.get(4));
            if (kib < 0) {
                continue;
            }
            result.put(pid, safeMultiplyKib(kib));
        }
        return Collections.unmodifiableMap(result);
    }

    static Map<Long, Long> parsePosixPsRss(String output) {
        Objects.requireNonNull(output, "output must not be null");
        Map<Long, Long> result = new LinkedHashMap<>();
        for (String rawLine : output.split("\\R", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\s+", -1);
            if (parts.length < 2) {
                continue;
            }
            long pid = parseLongWithMin(parts[0], 1L);
            long kib = parseLongWithMin(parts[1], 0L);
            if (pid > 0 && kib >= 0) {
                result.put(pid, safeMultiplyKib(kib));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>(5);
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }

    private static long parseLongWithMin(String raw, long minInclusive) {
        try {
            long value = Long.parseLong(raw);
            return value >= minInclusive ? value : -1L;
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static long parseDigitsAsNonNegativeLong(String raw) {
        StringBuilder digits = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        if (digits.isEmpty()) {
            return -1L;
        }
        return parseLongWithMin(digits.toString(), 0L);
    }

    private static long safeMultiplyKib(long kib) {
        return kib > Long.MAX_VALUE / BYTES_PER_KIB ? Long.MAX_VALUE : kib * BYTES_PER_KIB;
    }
}
