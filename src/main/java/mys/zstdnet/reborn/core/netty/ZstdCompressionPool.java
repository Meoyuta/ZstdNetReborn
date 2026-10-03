package mys.zstdnet.reborn.core.netty;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared bounded worker pool for compression work that must leave the Netty event loop. */
final class ZstdCompressionPool {
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
        Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
        Math.max(4, Runtime.getRuntime().availableProcessors()),
        60L,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(512),
        task -> {
            var thread = new Thread(task, "zstdnet-compression-" + THREAD_ID.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        },
        new ThreadPoolExecutor.AbortPolicy()
    );

    private ZstdCompressionPool() {}

    static int maximumThreads() {
        return EXECUTOR.getMaximumPoolSize();
    }

    static int coreThreads() {
        return EXECUTOR.getCorePoolSize();
    }

    static int poolSize() {
        return EXECUTOR.getPoolSize();
    }

    static int activeThreads() {
        return EXECUTOR.getActiveCount();
    }

    static void execute(Runnable task) throws RejectedExecutionException {
        EXECUTOR.execute(task);
    }
}
