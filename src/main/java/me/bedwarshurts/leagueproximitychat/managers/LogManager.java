package me.bedwarshurts.leagueproximitychat.managers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;

public final class LogManager {

    private static final int MAX_LINES = 2000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static boolean installed = false;

    private LogManager() {
    }

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        System.setOut(new PrintStream(new TeeStream(System.out), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new TeeStream(System.err), true, StandardCharsets.UTF_8));
    }

    private static synchronized void append(String line) {
        lines.addLast(LocalTime.now().format(TIME) + "  " + line);
        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
        }
    }

    public static synchronized String snapshot() {
        return String.join("\n", lines);
    }

    private static final class TeeStream extends OutputStream {
        private final OutputStream original;
        private final ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();

        private TeeStream(OutputStream original) {
            this.original = original;
        }

        @Override
        public synchronized void write(int b) throws IOException {
            original.write(b);
            if (b == '\n') {
                completeLine();
            } else if (b != '\r') {
                lineBuffer.write(b);
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            original.write(b, off, len);
            for (int i = off; i < off + len; i++) {
                int c = b[i] & 0xFF;
                if (c == '\n') {
                    completeLine();
                } else if (c != '\r') {
                    lineBuffer.write(c);
                }
            }
        }

        @Override
        public void flush() throws IOException {
            original.flush();
        }

        private void completeLine() {
            String line = lineBuffer.toString(StandardCharsets.UTF_8);
            lineBuffer.reset();
            if (!line.isBlank()) {
                append(line);
            }
        }
    }
}
