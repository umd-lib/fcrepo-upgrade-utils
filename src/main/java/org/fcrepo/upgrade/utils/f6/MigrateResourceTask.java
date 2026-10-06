/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */

package org.fcrepo.upgrade.utils.f6;

import org.slf4j.Logger;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * A task for migrating a resource to F6.
 *
 * @author pwinckles
 */
public class MigrateResourceTask implements Runnable {

    private static final Logger LOGGER = getLogger(MigrateResourceTask.class);

    private final MigrationTaskManager taskManager;
    private final ResourceMigrator resourceMigrator;
    private final ResourceInfoLogger infoLogger;
    private final ResourceInfo info;

    /**
     * @param taskManager the task manager that is coordinating migration tasks
     * @param resourceMigrator the object responsible for performing the migration
     * @param infoLogger the logger to use to record failed migrations
     * @param info the resource to be migrated
     */
    public MigrateResourceTask(final MigrationTaskManager taskManager,
                               final ResourceMigrator resourceMigrator,
                               final ResourceInfoLogger infoLogger,
                               final ResourceInfo info) {
        this.taskManager = taskManager;
        this.resourceMigrator = resourceMigrator;
        this.infoLogger = infoLogger;
        this.info = info;
    }

    @Override
    public void run() {
        try {
            final var children = resourceMigrator.migrate(info);

            for (final var child : children) {
                try {
                    if (child.getArchivalGroupId() == null) {
                        taskManager.submit(child);
                    } else {
                        taskManager.processImmediately(child);
                    }
                } catch (RuntimeException e) {
                    LOGGER.warn("Failed to queue {} for migration", child.getFullId());
                    infoLogger.log(child);
                }
            }

            // Archival group members are migrated in place above, so by now the whole group has been migrated
            if (info.getFullId().equals(info.getArchivalGroupId())) {
                resourceMigrator.commitArchivalGroup(info.getFullId());
            }
        } catch (UnsupportedOperationException e) {
            // This is thrown when a resource is encountered that is not currently handled
            LOGGER.error(e.getMessage());
            infoLogger.log(info);
        } catch (RuntimeException e) {
            LOGGER.error("Failed to process {}", info, e);
            infoLogger.log(info);
        }
    }

}
