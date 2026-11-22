package procomp.cli;

import procomp.core.common.Result;

import java.io.PrintWriter;
import java.time.Duration;
import java.util.List;

/**
 * Shared package-private CLI formatting, table rendering, and option parsing utilities.
 */
final class CliFormat {

    private CliFormat() {}

    static void renderTable(PrintWriter out, List<String> headers, List<List<String>> rows) {
        int cols = headers.size();
        int[] widths = new int[cols];
        for (int c = 0; c < cols; c++) {
            widths[c] = headers.get(c).length();
        }
        for (List<String> row : rows) {
            for (int c = 0; c < cols; c++) {
                widths[c] = Math.max(widths[c], row.get(c).length());
            }
        }
        printRow(out, headers, widths);
        for (List<String> row : rows) {
            printRow(out, row, widths);
        }
    }

    private static void printRow(PrintWriter out, List<String> cells, int[] widths) {
        StringBuilder sb = new StringBuilder();
        for (int c = 0; c < cells.size(); c++) {
            String cell = cells.get(c);
            sb.append(cell);
            if (c < cells.size() - 1) {
                int padding = widths[c] - cell.length() + 2;
                sb.append(" ".repeat(padding));
            }
        }
        out.println(sb);
    }

    static String formatDuration(Duration duration) {
        long totalSeconds = Math.max(0L, duration.toSeconds());
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return hours + "h" + minutes + "m" + seconds + "s";
        }
        if (minutes > 0) {
            return minutes + "m" + seconds + "s";
        }
        return seconds + "s";
    }

    record ParsedOption<T>(T value, int nextIndex) {}

    static boolean isTimeoutOption(String arg) {
        return "--timeout".equals(arg) || arg.startsWith("--timeout=");
    }

    static Result<ParsedOption<Duration>, String> parseTimeoutOption(List<String> args, int index) {
        String arg = args.get(index);
        if ("--timeout".equals(arg)) {
            if (index + 1 >= args.size()) {
                return Result.err("Option '--timeout' requires a positive integer number of seconds.");
            }
            return parsePositiveSeconds(args.get(index + 1))
                .map(duration -> new ParsedOption<>(duration, index + 1));
        }
        return parsePositiveSeconds(arg.substring("--timeout=".length()))
            .map(duration -> new ParsedOption<>(duration, index));
    }

    static Result<Duration, String> parsePositiveSeconds(String raw) {
        if (raw == null || raw.isBlank()) {
            return Result.err("Invalid --timeout value: must be a positive integer number of seconds.");
        }
        try {
            long seconds = Long.parseLong(raw.trim());
            if (seconds <= 0) {
                return Result.err("Invalid --timeout value '" + raw + "': must be greater than 0 seconds.");
            }
            return Result.ok(Duration.ofSeconds(seconds));
        } catch (NumberFormatException e) {
            return Result.err("Invalid --timeout value '" + raw + "': must be a positive integer number of seconds.");
        }
    }
}
