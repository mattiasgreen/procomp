package procomp.web;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight {@code text/event-stream} (SSE) framing writer with disconnect detection.
 * {@link #isOpen()} can be passed directly as {@code LogsShell}'s {@code keepFollowing} predicate.
 */
public final class SseStream implements ApiRouter.SseSink {

    public static final String CONTENT_TYPE = "text/event-stream; charset=utf-8";

    private final OutputStream out;
    private final AtomicBoolean open = new AtomicBoolean(true);

    public SseStream(OutputStream out) {
        this.out = Objects.requireNonNull(out, "out must not be null");
    }

    /**
     * Formats a single SSE frame with {@code event:} and {@code data:} lines terminated by a blank line.
     */
    public static String formatFrame(String event, String data) {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(data, "data must not be null");
        String sanitizedEvent = event.replace("\r", "").replace("\n", "");
        StringBuilder sb = new StringBuilder(sanitizedEvent.length() + data.length() + 24);
        sb.append("event: ").append(sanitizedEvent).append('\n');
        String normalized = data.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        for (String line : lines) {
            sb.append("data: ").append(line).append('\n');
        }
        sb.append('\n');
        return sb.toString();
    }

    @Override
    public synchronized boolean send(String event, String data) {
        if (!open.get()) {
            return false;
        }
        byte[] frame = formatFrame(event, data).getBytes(StandardCharsets.UTF_8);
        try {
            out.write(frame);
            out.flush();
            return true;
        } catch (IOException e) {
            open.set(false);
            return false;
        }
    }

    /**
     * Sends an SSE comment frame ({@code ": <comment>\n\n"}), ignored by EventSource clients and
     * used as a lightweight keepalive / disconnect probe. Returns {@code false} if the stream closed.
     */
    public synchronized boolean sendComment(String comment) {
        Objects.requireNonNull(comment, "comment must not be null");
        if (!open.get()) {
            return false;
        }
        String sanitized = comment.replace("\r", "").replace("\n", "");
        byte[] frame = (": " + sanitized + "\n\n").getBytes(StandardCharsets.UTF_8);
        try {
            out.write(frame);
            out.flush();
            return true;
        } catch (IOException e) {
            open.set(false);
            return false;
        }
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    @Override
    public void close() {
        if (open.compareAndSet(true, false)) {
            try {
                out.close();
            } catch (IOException ignored) {
                // Best-effort close on disconnect or shutdown.
            }
        }
    }
}
