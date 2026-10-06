/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */

package org.fcrepo.upgrade.utils.f6;

import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * Task manager for coordinating resource migration tasks.
 *
 * @author pwinckles
 */
public class MigrationTaskManager {

    private static final Logger LOGGER = getLogger(MigrationTaskManager.class);

    private final ExecutorService executorService;
    private final ResourceMigrator resourceMigrator;
    private final AtomicLong count;
    private final Object lock;
    private final ResourceInfoLogger infoLogger;

    private boolean shutdown = false;

    /**
     * @param threadCount the number of threads to use
     * @param resourceMigrator the object responsible for performing the migration
     * @param infoLogger the logger to use to record failed migrations
     */
    public MigrationTaskManager(final int threadCount,
                                final ResourceMigrator resourceMigrator,
                                final ResourceInfoLogger infoLogger) {
        this.executorService = new ThreadPoolExecutor(threadCount, threadCount,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>()) {
            @Override
            protected <T> RunnableFuture<T> newTaskFor(final Callable<T> callable) {
                return new FutureTaskWithCallable<>(callable);
            }
        };
        this.resourceMigrator = resourceMigrator;
        this.count = new AtomicLong(0);
        this.lock = new Object();
        this.infoLogger = infoLogger;

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!shutdown) {
                LOGGER.info("Shutting down...");
                shutdown();
            }
        }));
    }

    /**
     * Submits a new resource to be migrated. This method returns immediately, and the resource is migrated
     * asynchronously.
     *
     * @param info the resource to migrate
     */
    public void submit(final ResourceInfo info) {
        LOGGER.debug("Submitting resource {} for processing", info.getFullId());
        // Count the task before it can run: counted after, it could finish first and let the count reach zero
        // while work remains
        count.incrementAndGet();
        try {
            executorService.submit(new TaskWrapper(info,
                    new MigrateResourceTask(this, resourceMigrator, infoLogger, info)));
        } catch (RuntimeException e) {
            taskFinished();
            throw e;
        }
    }

    /**
     * Processes a resource migration task immediately in the current thread.
     * Used for archival group members that must be processed serially.
     *
     * @param info the resource to migrate
     */
    public void processImmediately(final ResourceInfo info) {
        LOGGER.debug("Processing resource {} immediately", info.getFullId());
        // Count the task before running it, for the same reason as in submit()
        count.incrementAndGet();
        new TaskWrapper(info,
                new MigrateResourceTask(this, resourceMigrator, infoLogger, info))
                .call();
    }

    /**
     * Blocks until all migration tasks are complete. Note, this does not prevent additional tasks from being submitted.
     * It simply waits until the queue is empty.
     *
     * @throws InterruptedException on interrupt
     */
    public void awaitCompletion() throws InterruptedException {
        if (count.get() == 0) {
            return;
        }

        synchronized (lock) {
            while (count.get() > 0) {
                lock.wait();
            }
        }
    }

    /**
     * Shutsdown the executor service, drains all remaining tasks to the log, and waits for the in progress
     * tasks to complete.
     */
    public synchronized void shutdown() {
        if (!shutdown) {
            shutdown = true;

            try {
                final var remaining = executorService.shutdownNow();
                logRemainingTasks(remaining);
                LOGGER.info("Waiting for any inflight tasks to complete...");
                if (!executorService.awaitTermination(5, TimeUnit.MINUTES)) {
                    LOGGER.warn("Failed to shutdown executor service cleanly after 5 minutes of waiting");
                }
            } catch (InterruptedException e) {
                LOGGER.warn("Failed to shutdown executor service cleanly");
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } finally {
                resourceMigrator.close();
            }
        }
    }

    /**
     * Adds remaining tasks to log
     */
    private void taskFinished() {
        count.decrementAndGet();
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    @SuppressWarnings("unchecked")
    private void logRemainingTasks(final List<Runnable> remaining) {
        remaining.forEach(task -> {
            try {
                final var callable = (TaskWrapper) ((FutureTaskWithCallable<Void>) task).callable;
                infoLogger.log(callable.info);
            } catch (Exception e) {
                LOGGER.warn("Failed to extract unprocessed resource info", e);
            }
        });
    }

    /**
     * FutureTask extension that supports accessing the wrapped Callable
     */
    private static class FutureTaskWithCallable<V> extends FutureTask<V> {

        private final Callable<V> callable;

        public FutureTaskWithCallable(final Callable<V> callable) {
            super(callable);
            this.callable = callable;
        }

    }

    private class TaskWrapper implements Callable<Void> {
        private final ResourceInfo info;
        private final Runnable runnable;

        private TaskWrapper(final ResourceInfo info, final Runnable runnable) {
            this.info = info;
            this.runnable = runnable;
        }

        @Override
        public Void call() {
            try {
                runnable.run();
                return null;
            } finally {
                taskFinished();
            }
        }
    }

}
