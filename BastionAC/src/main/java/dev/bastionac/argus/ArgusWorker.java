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

    private record Job(int priority, long seq, String what, ArgusProgress progress, Runnable task)
            implements Comparable<Job> {
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
        submit(priority, what, null, task);
    }

    /** As above, with the case's progress record (queue position and waiting are reported into it). */
    void submit(int priority, String what, ArgusProgress progress, Runnable task) {
        queue.add(new Job(priority, seq.incrementAndGet(), what, progress, task));
    }

    /**
     * Server thread, twice a second: tells every queued case how many jobs are
     * before it (the one running included), for the live menu.
     */
    void updateAhead() {
        Job[] jobs = queue.toArray(new Job[0]);
        java.util.Arrays.sort(jobs);
        int busy = current != null ? 1 : 0;
        for (int i = 0; i < jobs.length; i++) {
            if (jobs[i].progress() != null) jobs[i].progress().ahead(i + busy);
        }
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
            waitForQuietServer(job.priority() == PRIO_CASE ? 20_000 : 120_000, job.progress());
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

    /**
     * Some patience (up to {@code maxMs}) while the average tick is over the
     * configured line. A waiting case says so in its progress.
     */
    private void waitForQuietServer(long maxMs, ArgusProgress progress) {
        long until = System.currentTimeMillis() + maxMs;
        while (running && System.currentTimeMillis() < until) {
            var srv = BastionAC.server();
            double limit = BastionAC.config().argus.pauseAboveMspt;
            if (srv == null || srv.getAverageTickTime() <= limit) return;
            if (progress != null) {
                progress.stage(ArgusProgress.Stage.WAITING_SERVER);
                progress.detail(String.format(java.util.Locale.ROOT, "тик %.0f мс при пороге %.0f мс",
                        srv.getAverageTickTime(), limit));
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
