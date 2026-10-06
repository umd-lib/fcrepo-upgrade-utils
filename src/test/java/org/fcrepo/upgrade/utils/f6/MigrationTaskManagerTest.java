/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */

package org.fcrepo.upgrade.utils.f6;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

/**
 * @author pwinckles
 */
@RunWith(MockitoJUnitRunner.class)
public class MigrationTaskManagerTest {

    private static final String INFO_FEDORA = "info:fedora";

    @Mock
    public ResourceMigrator resourceMigrator;

    private MigrationTaskManager manager;

    private ResourceInfo defaultInfo;

    private Path logPath;

    @Before
    public void setup() throws IOException {
        manager = new MigrationTaskManager(1, resourceMigrator, new ResourceInfoLogger());
        final var parent = randomId();
        defaultInfo = ResourceInfo.container(parent, join(parent, "child"), null, Paths.get("/"), "child");
        logPath = Paths.get("target/remaining.log");
        if (Files.exists(logPath)) {
            Files.write(logPath, new byte[0], StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    @Test
    public void blockUntilAllTasksFinish() throws InterruptedException {
        final var count = new AtomicInteger(0);

        doAnswer(invocation -> {
            TimeUnit.SECONDS.sleep(2);
            count.incrementAndGet();
            return new ArrayList<ResourceInfo>();
        }).when(resourceMigrator).migrate(Mockito.any());

        manager.submit(defaultInfo);
        manager.submit(defaultInfo);
        manager.submit(defaultInfo);

        assertNotEquals(3, count.get());
        manager.awaitCompletion();
        assertEquals(3, count.get());
    }

    @Test(expected = RejectedExecutionException.class)
    public void rejectTaskWhenShutdown() throws InterruptedException {
        doReturn(new ArrayList<ResourceInfo>()).when(resourceMigrator).migrate(Mockito.any());

        submitAndComplete(defaultInfo);

        manager.submit(defaultInfo);
    }

    @Test
    public void logInfoWhenTasksFail() throws InterruptedException {
        doThrow(new RuntimeException()).when(resourceMigrator).migrate(Mockito.any());

        submitAndComplete(defaultInfo);

        assertLogContains(defaultInfo);
    }

    @Test
    public void logInfoWhenShutdownWithOutstandingTasks() throws InterruptedException {
        doAnswer(invocation -> {
            TimeUnit.SECONDS.sleep(2);
            return new ArrayList<ResourceInfo>();
        }).when(resourceMigrator).migrate(Mockito.any());

        final var info2 = ResourceInfo.container(defaultInfo.getFullId(),
                join(defaultInfo.getFullId(), "child"), null, Paths.get("/"), "child");
        final var info3 = ResourceInfo.container(info2.getFullId(),
                join(info2.getFullId(), "child"), null, Paths.get("/"), "child");

        manager.submit(defaultInfo);
        manager.submit(info2);
        manager.submit(info3);

        TimeUnit.SECONDS.sleep(1);

        manager.shutdown();

        assertLogContains(info2, info3);
    }

    @Test
    public void processArchivalGroupChildrenImmediately() throws InterruptedException {
        final var agId = "ag-" + UUID.randomUUID();
        final var threadIds = new ArrayList<Long>();
        final var parentId = randomId();
        final var parentInfo = ResourceInfo.container(INFO_FEDORA, parentId, null, Paths.get("/"), "parent");

        // Child with archival group ID
        final var childId = join(parentId, "child");
        final var childInfo = ResourceInfo.container(parentId, childId, agId, Paths.get("/"), "child");

        // Grandchild also in same archival group
        final var grandchildId = join(childId, "grandchild");
        final var grandchildInfo = ResourceInfo.container(childId, grandchildId, agId, Paths.get("/"), "grandchild");

        doAnswer(invocation -> {
            final ResourceInfo info = invocation.getArgument(0);
            threadIds.add(Thread.currentThread().getId());

            if (info.equals(parentInfo)) {
                return List.of(childInfo);
            } else if (info.equals(childInfo)) {
                return List.of(grandchildInfo);
            }
            return new ArrayList<ResourceInfo>();
        }).when(resourceMigrator).migrate(Mockito.any());

        manager.submit(parentInfo);
        manager.awaitCompletion();

        // All three resources should have been processed in the same thread
        assertEquals(3, threadIds.size());
        assertEquals(threadIds.get(0), threadIds.get(1));
        assertEquals(threadIds.get(1), threadIds.get(2));
    }

    @Test
    public void awaitCompletionWaitsForEveryArchivalGroupChild() throws InterruptedException {
        final var agId = "ag-" + UUID.randomUUID();
        final var parentId = randomId();
        final var parentInfo = ResourceInfo.container(INFO_FEDORA, parentId, null, Paths.get("/"), "parent");
        // Between one child finishing and the next starting there must always be a task counted as in flight.
        // Each child is a chance for awaitCompletion to see none; many children make an early return near certain.
        final int childCount = 20000;
        final var children = new ArrayList<ResourceInfo>();
        for (int i = 0; i < childCount; i++) {
            children.add(ResourceInfo.container(parentId, join(parentId, "child" + i), agId, Paths.get("/"),
                    "child" + i));
        }
        final var migrated = new AtomicInteger();

        doAnswer(invocation -> {
            migrated.incrementAndGet();
            final ResourceInfo info = invocation.getArgument(0);
            return info.equals(parentInfo) ? children : new ArrayList<ResourceInfo>();
        }).when(resourceMigrator).migrate(Mockito.any());

        manager.submit(parentInfo);
        manager.awaitCompletion();

        assertEquals("awaitCompletion returned before every child was migrated", childCount + 1, migrated.get());
    }


    private void submitAndComplete(final ResourceInfo info) throws InterruptedException {
        manager.submit(info);
        manager.awaitCompletion();
        manager.shutdown();
    }

    private void assertLogContains(final ResourceInfo... infos) {
        final var actual = new ResourceInfoLogger().parseLog(logPath);
        assertThat(actual, containsInAnyOrder(infos));
    }

    private String randomId() {
        return id(UUID.randomUUID().toString());
    }

    private String id(final String value) {
        return join(INFO_FEDORA, value);
    }

    private String join(final String left, final String right) {
        return left + "/" + right;
    }

}
