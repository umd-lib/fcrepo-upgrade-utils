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
import org.mockito.junit.MockitoJUnitRunner;

import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author bbpennel
 */
@RunWith(MockitoJUnitRunner.class)
public class MigrateResourceTaskTest {
    private static final String INFO_FEDORA = "info:fedora";

    @Mock
    private MigrationTaskManager taskManager;

    @Mock
    private ResourceMigrator resourceMigrator;

    @Mock
    private ResourceInfoLogger infoLogger;

    private String parentId;
    private ResourceInfo parentInfo;

    @Before
    public void setup() {
        parentId = "parent-" + UUID.randomUUID();
        parentInfo = ResourceInfo.container(INFO_FEDORA, parentId, null, Paths.get("/parent"), "parent");
    }

    @Test
    public void testNoChildren() throws Exception {
        when(resourceMigrator.migrate(parentInfo)).thenReturn(List.of());

        final var task = new MigrateResourceTask(taskManager, resourceMigrator, infoLogger, parentInfo);
        task.run();

        verify(resourceMigrator).migrate(parentInfo);
        verify(taskManager, never()).submit(any());
        verify(taskManager, never()).processImmediately(any());
        verify(infoLogger, never()).log(any());
    }

    @Test
    public void testNonArchivalGroupChildren() throws Exception {
        final var child1 = ResourceInfo.container(parentId, parentId + "/child1", null,
                Paths.get("/child1"), "child1");
        final var child2 = ResourceInfo.container(parentId, parentId + "/child2", null,
                Paths.get("/child2"), "child2");

        when(resourceMigrator.migrate(parentInfo)).thenReturn(List.of(child1, child2));

        final var task = new MigrateResourceTask(taskManager, resourceMigrator, infoLogger, parentInfo);
        task.run();

        verify(resourceMigrator).migrate(parentInfo);
        verify(taskManager).submit(child1);
        verify(taskManager).submit(child2);
        verify(taskManager, never()).processImmediately(any());
        verify(infoLogger, never()).log(any());
    }

    @Test
    public void testArchivalGroupChildren() throws Exception {
        final var agId = "ag-" + UUID.randomUUID();
        final var child1 = ResourceInfo.container(parentId, parentId + "/child1", agId,
                Paths.get("/child1"), "child1");
        final var child2 = ResourceInfo.container(parentId, parentId + "/child2", agId,
                Paths.get("/child2"), "child2");

        when(resourceMigrator.migrate(parentInfo)).thenReturn(List.of(child1, child2));

        final var task = new MigrateResourceTask(taskManager, resourceMigrator, infoLogger, parentInfo);
        task.run();

        verify(resourceMigrator).migrate(parentInfo);
        verify(taskManager).processImmediately(child1);
        verify(taskManager).processImmediately(child2);
        verify(taskManager, never()).submit(any());
        verify(infoLogger, never()).log(any());
    }

    @Test
    public void testArchivalGroupCommittedAfterItsMembers() throws Exception {
        final var agInfo = ResourceInfo.container(INFO_FEDORA, parentId, parentId, Paths.get("/parent"), "parent");
        final var child = ResourceInfo.container(parentId, parentId + "/child", parentId, Paths.get("/child"), "child");

        when(resourceMigrator.migrate(agInfo)).thenReturn(List.of(child));

        final var task = new MigrateResourceTask(taskManager, resourceMigrator, infoLogger, agInfo);
        task.run();

        final var inOrder = inOrder(taskManager, resourceMigrator);
        inOrder.verify(taskManager).processImmediately(child);
        inOrder.verify(resourceMigrator).commitArchivalGroup(parentId);
        verify(infoLogger, never()).log(any());
    }

    @Test
    public void testNonArchivalGroupNotCommitted() throws Exception {
        when(resourceMigrator.migrate(parentInfo)).thenReturn(List.of());

        final var task = new MigrateResourceTask(taskManager, resourceMigrator, infoLogger, parentInfo);
        task.run();

        verify(resourceMigrator, never()).commitArchivalGroup(any());
    }

    @Test
    public void testMigrationFailure() throws Exception {
        when(resourceMigrator.migrate(parentInfo)).thenThrow(new RuntimeException("Migration failed"));

        final var task = new MigrateResourceTask(taskManager, resourceMigrator, infoLogger, parentInfo);
        task.run();

        verify(resourceMigrator).migrate(parentInfo);
        verify(infoLogger).log(parentInfo);
        verify(taskManager, never()).submit(any());
        verify(taskManager, never()).processImmediately(any());
    }

    @Test
    public void testUnsupportedOperation() throws Exception {
        when(resourceMigrator.migrate(parentInfo))
                .thenThrow(new UnsupportedOperationException("Unsupported resource type"));

        final var task = new MigrateResourceTask(taskManager, resourceMigrator, infoLogger, parentInfo);
        task.run();

        verify(resourceMigrator).migrate(parentInfo);
        verify(infoLogger).log(parentInfo);
        verify(taskManager, never()).submit(any());
        verify(taskManager, never()).processImmediately(any());
    }

    @Test
    public void testChildSubmissionFailure() throws Exception {
        final var child = ResourceInfo.container(parentId, parentId + "/child", null,
                Paths.get("/child"), "child");

        when(resourceMigrator.migrate(parentInfo)).thenReturn(List.of(child));
        doThrow(new RuntimeException("Queue full")).when(taskManager).submit(child);

        final var task = new MigrateResourceTask(taskManager, resourceMigrator, infoLogger, parentInfo);
        task.run();

        verify(resourceMigrator).migrate(parentInfo);
        verify(taskManager).submit(child);
        verify(infoLogger).log(child);
    }
}
