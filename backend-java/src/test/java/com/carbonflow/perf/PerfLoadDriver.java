package com.carbonflow.perf;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Real-HTTP load driver for the Phase 10.10 backend measurements.
 *
 * <h2>Why real HTTP and not MockMvc</h2>
 * <p>MockMvc bypasses the servlet container, the socket layer, Jackson's
 * response streaming and Tomcat's thread pool — exactly the components whose
 * behaviour this phase is asked to measure. Every number produced here travels
 * over a loopback TCP connection to a Tomcat worker thread inside the running
 * application, so the reported latency includes serialization, the wire and
 * connection-pool contention.
 *
 * <h2>Load shape</h2>
 * <p>Each measurement runs {@code iterations} requests distributed over
 * {@code concurrency} long-lived worker threads, each worker issuing its
 * requests back to back. The client reuses one {@link HttpClient} and therefore
 * one connection pool — an HTTP client that opened a connection per request
 * would measure the operating system's accept path rather than the application.
 *
 * <p>A fixed warm-up runs before every series and is discarded, so JIT
 * compilation and connection establishment are not charged to the first
 * percentile the report cites.
 */
public final class PerfLoadDriver {

    /** One measured request series. */
    public record Series(String endpoint, int concurrency, int iterations,
                         PerfStats.Summary latency, double stdDevMs,
                         long responseBytes, int errors, String firstError,
                         double wallClockMs, double processCpuMs,
                         long clientAllocatedBytes, long allThreadAllocatedBytes) {

        /** Measured throughput in requests per second. */
        public double requestsPerSecond() {
            return wallClockMs <= 0 ? 0 : iterations / (wallClockMs / 1000.0);
        }

        /**
         * Process CPU time consumed per request, in milliseconds.
         *
         * <p>Measured across the whole JVM, so it includes the database client,
         * Jackson and the GC threads — everything in this process, not just the
         * servlet thread. At concurrency above one, several worker threads share
         * it, so this is the <em>total</em> CPU cost of one request, which is the
         * figure that decides how much load a core can serve.
         */
        public double cpuMsPerRequest() {
            return iterations == 0 ? 0 : processCpuMs / iterations;
        }

        /** Bytes the client threads allocated per request. */
        public long allocatedBytesPerRequest() {
            return iterations == 0 ? 0 : clientAllocatedBytes / iterations;
        }

        /**
         * Bytes allocated across the whole JVM per request.
         *
         * <p>This is the figure that exposes <em>excessive object creation</em>:
         * it covers the client, the servlet thread that materialized the
         * response, Jackson, and the PostgreSQL driver, so a request that
         * allocates hundreds of megabytes to return a small aggregate is
         * visible as a number rather than inferred from a latency curve.
         */
        public double allThreadAllocatedBytesPerRequest() {
            return iterations == 0 ? 0 : (double) allThreadAllocatedBytes / iterations;
        }
    }

    /**
     * Process-wide CPU time, in milliseconds.
     *
     * <p>Backed by {@code com.sun.management.OperatingSystemMXBean}; returns
     * {@code -1} where the JVM does not expose it, which the report records as
     * "not measurable" rather than as zero.
     */
    public static double processCpuMillis() {
        java.lang.management.OperatingSystemMXBean bean =
                ManagementFactory.getOperatingSystemMXBean();
        if (bean instanceof com.sun.management.OperatingSystemMXBean sun) {
            long nanos = sun.getProcessCpuTime();
            return nanos < 0 ? -1 : nanos / 1_000_000.0;
        }
        return -1;
    }

    /** Bytes allocated by the calling thread since JVM start, or -1 if unavailable. */
    public static long threadAllocatedBytes() {
        java.lang.management.ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        if (threads instanceof com.sun.management.ThreadMXBean sun
                && sun.isThreadAllocatedMemorySupported()) {
            long bytes = sun.getThreadAllocatedBytes(Thread.currentThread().getId());
            return bytes < 0 ? -1 : bytes;
        }
        return -1;
    }

    /**
     * Total bytes allocated by every live thread in this JVM right now.
     *
     * <p>Sampled before and after a load series, the difference divided by the
     * request count is the whole-process allocation cost of one request. Returns
     * {@code -1} where allocation accounting is disabled.
     */
    public static long allThreadAllocatedBytes() {
        java.lang.management.ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        if (!(threads instanceof com.sun.management.ThreadMXBean sun)
                || !sun.isThreadAllocatedMemorySupported()) {
            return -1;
        }
        long[] ids = sun.getAllThreadIds();
        long[] allocated = sun.getThreadAllocatedBytes(ids);
        long total = 0;
        for (long value : allocated) {
            if (value > 0) {
                total += value;
            }
        }
        return total;
    }

    private final HttpClient client;
    private final String baseUrl;
    private final String bearer;
    private final ExecutorService workers;

    public PerfLoadDriver(String baseUrl, String bearer) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.bearer = bearer;
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "perf-load-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        this.workers = Executors.newCachedThreadPool(factory);
    }

    /**
     * Runs one warm-up pass whose timings are discarded.
     *
     * <p>Without this the first reported percentile of every series is dominated
     * by class loading and JIT, which would misrepresent a steady-state figure as
     * the application's latency.
     */
    public void warmUp(String path, int concurrency, int iterations) throws Exception {
        run(path, concurrency, iterations, true);
    }

    public Series run(String path, int concurrency, int iterations) throws Exception {
        return run(path, concurrency, iterations, false);
    }

    private Series run(String path, int concurrency, int iterations, boolean discard)
            throws Exception {
        long[] durations = new long[iterations];
        AtomicLong bytes = new AtomicLong();
        AtomicInteger errors = new AtomicInteger();
        AtomicLong allocated = new AtomicLong();
        String[] firstError = new String[1];
        List<Thread> threads = new ArrayList<>(concurrency);
        java.util.concurrent.CountDownLatch ready =
                new java.util.concurrent.CountDownLatch(concurrency);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(concurrency);

        int[] cursor = new int[1];
        for (int t = 0; t < concurrency; t++) {
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                long startAllocated = threadAllocatedBytes();
                while (true) {
                    int index;
                    synchronized (cursor) {
                        index = cursor[0];
                        if (index >= iterations) {
                            break;
                        }
                        cursor[0] = index + 1;
                    }
                    try {
                        HttpRequest request = HttpRequest.newBuilder()
                                .uri(URI.create(baseUrl + path))
                                .header("Authorization", "Bearer " + bearer)
                                .header("Accept", "application/json")
                                .timeout(Duration.ofMinutes(5))
                                .GET()
                                .build();
                        long started = System.nanoTime();
                        HttpResponse<byte[]> response =
                                client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                        long elapsed = System.nanoTime() - started;
                        durations[index] = elapsed;
                        bytes.addAndGet(response.body().length);
                        if (response.statusCode() >= 400) {
                            errors.incrementAndGet();
                            if (firstError[0] == null) {
                                firstError[0] = response.statusCode() + " "
                                        + truncate(new String(response.body()), 200);
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                        if (firstError[0] == null) {
                            firstError[0] = e.getClass().getSimpleName() + ": "
                                    + truncate(String.valueOf(e.getMessage()), 200);
                        }
                    }
                }
                long endAllocated = threadAllocatedBytes();
                if (startAllocated >= 0 && endAllocated >= startAllocated) {
                    allocated.addAndGet(endAllocated - startAllocated);
                }
            }, "perf-worker-" + t);
            threads.add(thread);
        }

        threads.forEach(Thread::start);
        ready.await(60, TimeUnit.SECONDS);
        double cpuBefore = processCpuMillis();
        long allocatedBefore = allThreadAllocatedBytes();
        long wallStart = System.nanoTime();
        go.countDown();
        for (Thread thread : threads) {
            thread.join(TimeUnit.MINUTES.toMillis(60));
        }
        long wallNanos = System.nanoTime() - wallStart;
        double cpuAfter = processCpuMillis();
        long allocatedAfter = allThreadAllocatedBytes();
        done.await(1, TimeUnit.SECONDS);

        double cpuMs = cpuBefore < 0 || cpuAfter < 0 ? -1 : cpuAfter - cpuBefore;
        long allAllocated = allocatedBefore < 0 || allocatedAfter < allocatedBefore
                ? -1 : allocatedAfter - allocatedBefore;
        PerfStats.Summary summary = PerfStats.of(durations);
        return new Series(path, concurrency, iterations, summary,
                PerfStats.stdDevMs(durations), bytes.get(), errors.get(), firstError[0],
                wallNanos / 1_000_000.0, cpuMs, allocated.get(), allAllocated);
    }

    /** Single timed request, for one-shot endpoints such as CSV export. */
    public TimedRequest once(String path, String accept) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + bearer)
                .timeout(Duration.ofMinutes(5))
                .GET();
        if (accept != null) {
            builder.header("Accept", accept);
        }
        long started = System.nanoTime();
        HttpResponse<byte[]> response =
                client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        long elapsed = System.nanoTime() - started;
        return new TimedRequest(path, response.statusCode(), elapsed,
                response.body().length, response.body());
    }

    /** One measured single request, with the body retained for assertions. */
    public record TimedRequest(String path, int status, long nanos, int bytes, byte[] body) {
        public double millis() {
            return nanos / 1_000_000.0;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    public void shutdown() {
        workers.shutdownNow();
    }
}
