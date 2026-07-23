package org.macaroon.acousticsystem.client.simulation;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/** Shared bounded lanes for independent listener-field rays. */
final class AcousticWorkerPool {
    private static final int LANE_COUNT = Math.max(
            1,
            Math.min(4, Runtime.getRuntime().availableProcessors() / 8)
    );
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();
    private static final ForkJoinPool WORKERS = new ForkJoinPool(
            LANE_COUNT,
            pool -> {
                ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory
                        .newThread(pool);
                thread.setName(
                        "AcousticSystem-ListenerRay-"
                                + THREAD_SEQUENCE.incrementAndGet()
                );
                thread.setPriority(Thread.NORM_PRIORITY);
                return thread;
            },
            null,
            true
    );

    private AcousticWorkerPool() {
    }

    static void parallelFor(int count, IntConsumer operation) {
        int lanes = Math.min(LANE_COUNT, count);
        if (lanes <= 1) {
            for (int index = 0; index < count; index++) {
                operation.accept(index);
            }
            return;
        }
        AtomicInteger cursor = new AtomicInteger();
        CompletableFuture<?>[] tasks = new CompletableFuture<?>[lanes];
        for (int lane = 0; lane < lanes; lane++) {
            tasks[lane] = CompletableFuture.runAsync(() -> {
                for (int index = cursor.getAndIncrement(); index < count;
                     index = cursor.getAndIncrement()) {
                    operation.accept(index);
                }
            }, WORKERS);
        }
        CompletableFuture.allOf(tasks).join();
    }
}
