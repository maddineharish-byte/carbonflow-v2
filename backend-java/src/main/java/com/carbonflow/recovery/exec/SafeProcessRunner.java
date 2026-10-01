package com.carbonflow.recovery.exec;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A deliberately small, safe wrapper around {@link ProcessBuilder} for backup
 * tooling.
 *
 * <h2>Why this exists</h2>
 * <p>CarbonFlow had no process-execution abstraction before REC-03. Creating one
 * rather than calling {@code Runtime.exec(String)} inline keeps the security
 * properties of every external-tool invocation in one auditable place instead of
 * at each call site.
 *
 * <h2>Guarantees</h2>
 * <ul>
 *   <li><b>No shell.</b> Commands are argument arrays executed directly. Nothing is
 *       ever concatenated into a command string, so a value containing
 *       {@code ; } , {@code && } , {@code | } or a newline is an ordinary
 *       argument rather than injected shell syntax. {@code cmd.exe /c} is never
 *       used.</li>
 *   <li><b>Explicit executable.</b> The binary is named, never resolved by
 *       searching {@code PATH} implicitly — discovery is an explicit,
 *       configurable decision (see {@code PostgreSqlToolLocator}).</li>
 *   <li><b>Bounded execution.</b> Every invocation has a timeout and is forcibly
 *       terminated when it expires.</li>
 *   <li><b>No secret in the command line.</b> Credentials are passed through the
 *       child process environment, so they never appear in {@code ps}/{@code
 *       wmic} output. See {@link #environment(Map)}.</li>
 * </ul>
 */
public final class SafeProcessRunner {

    /**
     * Cap on captured output.
     *
     * <p>{@code pg_dump} progress output is bounded in practice, but a runaway
     * process must not be able to exhaust heap. Truncation is recorded in
     * {@link Result#outputTruncated()} rather than silently dropping data.
     */
    public static final int MAX_CAPTURED_CHARS = 64 * 1024;

    private SafeProcessRunner() {
    }

    /**
     * Outcome of one external command.
     *
     * @param exitCode         process exit code, or {@code -1} if it never started
     * @param stdout           captured standard output, possibly truncated
     * @param stderr           captured standard error, possibly truncated
     * @param timedOut         whether the timeout killed the process
     * @param outputTruncated  whether either stream hit {@link #MAX_CAPTURED_CHARS}
     */
    public record Result(int exitCode, String stdout, String stderr,
                         boolean timedOut, boolean outputTruncated) {

        public boolean succeeded() {
            return exitCode == 0 && !timedOut;
        }
    }

    /**
     * Describes a command to run.
     *
     * <p>Built fluently so a call site reads as an argument list rather than as
     * process plumbing.
     */
    public static final class Command {

        private final String executable;
        private final List<String> arguments = new ArrayList<>();
        private final Map<String, String> environment = new java.util.HashMap<>();
        private Duration timeout = Duration.ofMinutes(30);
        private Path workingDirectory;

        public Command(String executable) {
            if (executable == null || executable.isBlank()) {
                throw new IllegalArgumentException("executable is required");
            }
            this.executable = executable;
        }

        /** Appends one argument. Never interpreted by a shell. */
        public Command arg(String value) {
            if (value == null) {
                throw new IllegalArgumentException("argument must not be null");
            }
            arguments.add(value);
            return this;
        }

        /**
         * Sets a variable in the child process environment.
         *
         * <p>This is how {@code PGPASSWORD} is supplied. The parent environment is
         * inherited first, so this overrides rather than replaces.
         */
        public Command environment(Map<String, String> variables) {
            if (variables != null) {
                environment.putAll(variables);
            }
            return this;
        }

        public Command environment(String key, String value) {
            environment.put(key, value);
            return this;
        }

        public Command timeout(Duration value) {
            if (value == null || value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.timeout = value;
            return this;
        }

        public Command workingDirectory(Path value) {
            this.workingDirectory = value;
            return this;
        }

        /**
         * The argument vector, for logging and tests.
         *
         * <p>Safe to expose precisely because no credential is ever passed here —
         * that is what the environment channel is for.
         */
        public List<String> argumentVector() {
            List<String> all = new ArrayList<>();
            all.add(executable);
            all.addAll(arguments);
            return List.copyOf(all);
        }
    }

    /**
     * Executes a command, capturing output and enforcing the timeout.
     *
     * <p>Output is drained on the calling thread via {@code ProcessBuilder}'s
     * merged redirect rather than by reading both streams on separate threads,
     * which avoids a deadlock where a child fills one pipe and blocks forever.
     *
     * @throws IOException if the process cannot be started at all (missing
     *                     executable, permission denied). A started process that
     *                     fails is reported as a non-zero {@link Result}.
     */
    public static Result run(Command command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command.argumentVector());
        builder.redirectErrorStream(false);

        if (command.workingDirectory != null) {
            builder.directory(command.workingDirectory.toFile());
        }
        for (Map.Entry<String, String> entry : command.environment.entrySet()) {
            builder.environment().put(entry.getKey(), entry.getValue());
        }

        Process process = builder.start();
        boolean timedOut = false;
        // Both pipes must be drained concurrently with the wait. Draining
        // sequentially — or before waiting — deadlocks or defeats the timeout:
        // a child that fills its stderr pipe blocks forever, and a child that
        // keeps stdout open keeps drain() blocked, so the timeout never gets a
        // chance to fire. This is the single most important detail here.
        StreamCollector out = new StreamCollector();
        StreamCollector err = new StreamCollector();
        Thread outReader = readerThread(process.getInputStream(), out);
        Thread errReader = readerThread(process.getErrorStream(), err);

        try {
            boolean finished = process.waitFor(command.timeout.toMillis(),
                    TimeUnit.MILLISECONDS);
            if (!finished) {
                timedOut = true;
                // Destroy forcibly: pg_dump holding a database connection will
                // not exit on its own after a timeout.
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            // Restore the interrupt flag: swallowing it would leave a
            // cancellation invisible to the scheduler.
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Interrupted while waiting for '"
                    + command.executable + "' to finish", e);
        } finally {
            process.destroy();
        }

        // Readers exit once the pipes close, which destroying guarantees.
        joinQuietly(outReader);
        joinQuietly(errReader);

        int exitCode;
        try {
            exitCode = process.exitValue();
        } catch (IllegalThreadStateException e) {
            exitCode = -1;
        }
        return new Result(exitCode, out.text(), err.text(), timedOut,
                out.truncated || err.truncated);
    }

    /** Drains one pipe on its own daemon thread. */
    private static Thread readerThread(InputStream stream, StreamCollector collector) {
        Thread thread = new Thread(() -> {
            try {
                StreamCollector drained = drain(stream);
                collector.copyFrom(drained);
            } catch (IOException e) {
                // The stream was closed under us during teardown; whatever was
                // captured so far is still reported.
            }
        }, "carbonflow-process-reader");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Bounded stream drain. */
    private static final class StreamCollector {
        private final StringBuilder buffer = new StringBuilder();
        private volatile boolean truncated;

        void append(String chunk) {
            if (buffer.length() + chunk.length() > MAX_CAPTURED_CHARS) {
                int room = Math.max(0, MAX_CAPTURED_CHARS - buffer.length());
                buffer.append(chunk, 0, room);
                truncated = true;
            } else {
                buffer.append(chunk);
            }
        }

        void copyFrom(StreamCollector other) {
            synchronized (buffer) {
                buffer.append(other.buffer);
                truncated = truncated || other.truncated;
            }
        }

        String text() {
            synchronized (buffer) {
                return buffer.toString();
            }
        }
    }

    /**
     * Drains a stream with a stateful UTF-8 decoder.
     *
     * <p>A stateful decoder is required, not an optimisation: decoding each
     * read() buffer independently would split a multi-byte character across a
     * chunk boundary and emit replacement characters. {@code pg_dump} can emit
     * UTF-8 in a NOTICE, so this is a real possibility rather than a theoretical
     * one.
     */
    private static StreamCollector drain(InputStream stream) throws IOException {
        StreamCollector collector = new StreamCollector();
        java.nio.charset.CharsetDecoder decoder =
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE);
        byte[] raw = new byte[4096];
        java.nio.ByteBuffer bytes = java.nio.ByteBuffer.wrap(raw);
        java.nio.CharBuffer chars = java.nio.CharBuffer.allocate(raw.length);

        try (InputStream in = stream) {
            while (true) {
                bytes.clear();
                int read = in.read(bytes.array(), 0, raw.length);
                if (read == -1) {
                    break;
                }
                bytes.limit(read).position(0);

                chars.clear();
                decoder.decode(bytes, chars, false);
                chars.flip();
                collector.append(chars.toString());
            }
            // Flush any trailing partial character as a replacement rather than
            // silently dropping it.
            chars.clear();
            decoder.decode(java.nio.ByteBuffer.allocate(0), chars, true);
            chars.flip();
            collector.append(chars.toString());
        }
        return collector;
    }
}