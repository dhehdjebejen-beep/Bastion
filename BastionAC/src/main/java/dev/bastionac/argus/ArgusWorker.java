package dev.bastionac.argus;

import dev.bastionac.BastionAC;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Argus thread. One job at a time, lowest priority, and it steps aside
 * while the server is struggling: the host has two cores and the game comes
 * first. Case jobs go before everything else and wait for a quiet tick only
 * briefly, since staff are waiting on them.
 */
final class ArgusWorker {

    static final int PRIO_CASE = 0;
    static final int PRIO_LABELS = 1;
    static final int PRIO_SCAN = 2;

    private record Job(int priority, long seq, String what, Runnable task) implements Comparable<Job> {
        @Override
        public int compareTo(Job o) {
            int p = Integer.compare(priority, o.priority);
            return p != 0 ? p : Long.compare(seq, o.seq);
        }
    }

    private final PriorityBlockingQueue<Job> queue = new PriorityBlockingQueue<>();
    private final AtomicLong seq = new AtomicLong();
    private final Thread thread;
    private volatile boolean running = true;
    private volatile String current;

    ArgusWorker() {
        thread = new Thread(this::loop, "BastionAC-Argus");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    void submit(int priority, String what, Runnable task) {
        queue.add(new Job(priority, seq.incrementAndGet(), what, task));
    }

    int pending() {
        return queue.size();
    }

    String current() {
        return current;
    }

    void shutdown() {
        running = false;
        thread.interrupt();
    }

    private void loop() {
        while (running) {
            Job job;
            try {
                job = queue.take();
            } catch (InterruptedException e) {
                if (!running) return;
                continue;
            }
            // A case someone is waiting on yields to a lagging server only
            // briefly; background scans can afford the full two minutes.
            waitForQuietServer(job.priority() == PRIO_CASE ? 20_000 : 120_000);
            current = job.what();
            try {
                job.task().run();
            } catch (Throwable t) {
                BastionAC.LOGGER.warn("Argus job '{}' failed: {}", job.what(), t.toString());
            } finally {
                current = null;
            }
        }
    }

    /** Some patience (up to {@code maxMs}) while the average tick is over the configured line. */
    private void waitForQuietServer(long maxMs) {
        long until = System.currentTimeMillis() + maxMs;
        while (running && System.currentTimeMillis() < until) {
            var srv = BastionAC.server();
            if (srv == null || srv.getAverageTickTime() <= BastionAC.config().argus.pauseAboveMspt) return;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
