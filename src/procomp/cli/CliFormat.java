package procomp.cli;

import procomp.core.common.DomainError;
import procomp.core.common.Json;
import procomp.core.common.JsonValue;
import procomp.core.common.Result;

import java.io.PrintWriter;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Shared package-private CLI formatting, table rendering, JSON helper, and Result handling utilities.
 */
final class CliFormat {

    private CliFormat() {}

    static <T> int handleResult(
        Result<T, DomainError> res,
        PrintWriter err,
        Consumer<T> onSuccess
    ) {
        return switch (res) {
            case Result.Ok<T, DomainError>(T value) -> {
                onSuccess.accept(value);
                yield 0;
            }
            case Result.Err<T, DomainError>(DomainError error) -> {
                err.println("Error: " + error.format());
                yield 1;
            }
        };
    }

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

    static JsonValue jsonStrOrNull(Optional<String> value) {
        return value.<JsonValue>map(Json::str).orElse(Json.nil());
    }

    static JsonValue jsonNumOrNull(Optional<? extends Number> value) {
        return value.<JsonValue>map(n -> Json.num(n.longValue())).orElse(Json.nil());
    }

    static JsonValue jsonUptimeOrNull(Optional<Duration> uptime) {
        return uptime.<JsonValue>map(d -> Json.num(d.toSeconds())).orElse(Json.nil());
    }
}
