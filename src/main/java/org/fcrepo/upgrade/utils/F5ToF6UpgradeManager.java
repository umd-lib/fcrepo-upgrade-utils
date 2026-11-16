/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.upgrade.utils;

import org.fcrepo.upgrade.utils.f6.MigrationTaskManager;
import org.fcrepo.upgrade.utils.f6.ResourceInfo;
import org.fcrepo.upgrade.utils.f6.ResourceInfoLogger;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.stream.Collectors;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.slf4j.LoggerFactory.getLogger;

/**
 * @author dbernstein
 * @since 2020-08-05
 */
class F5ToF6UpgradeManager implements UpgradeManager {

    private static final Logger LOGGER = getLogger(F5ToF6UpgradeManager.class);

    private static final String ROOT = "info:fedora";
    private static final String REST = "rest";

    private final Config config;
    private final MigrationTaskManager migrationTaskManager;
    private final ResourceInfoLogger infoLogger;

    /**
     * Constructor
     */
    public F5ToF6UpgradeManager(final Config config,
                                final MigrationTaskManager migrationTaskManager,
                                final ResourceInfoLogger infoLogger) {
        this.config = config;
        this.migrationTaskManager = migrationTaskManager;
        this.infoLogger = infoLogger;
    }

    /**
     * Runs the upgrade util process
     */
    public void start() {
        LOGGER.info("Starting upgrade: config={}", config);

        if (config.getResourceInfoFile() == null) {
            LOGGER.info("Starting migration from repository root");
            final var repoRoot = ResourceInfo.container(ROOT, ROOT, null, locateRestRoot(), REST);
            migrationTaskManager.submit(repoRoot);
        } else {
            LOGGER.info("Starting migration from resources file {}", config.getResourceInfoFile());
            final var infos = infoLogger.parseLog(config.getResourceInfoFile());
            // Each resource once. A member of an archival group that is itself listed is left to the group, which
            // migrates all of its members: migrated on its own as well, it would be written to the group's object
            // by two tasks at once.
            final var ids = infos.stream().map(ResourceInfo::getFullId).collect(Collectors.toSet());
            final var submitted = new HashSet<String>();
            for (final var info : infos) {
                final var groupId = info.getArchivalGroupId();
                if (groupId != null && !groupId.equals(info.getFullId()) && ids.contains(groupId)) {
                    LOGGER.info("Leaving {} to its archival group {}", info.getFullId(), groupId);
                } else if (submitted.add(info.getFullId())) {
                    migrationTaskManager.submit(info);
                }
            }
        }

        try {
            migrationTaskManager.awaitCompletion();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } finally {
            migrationTaskManager.shutdown();
        }
        LOGGER.info("Upgrade complete.");
    }

    private Path locateRestRoot() {
        final var root = config.getInputDir().toPath();

        try (final var stream = Files.find(root, 100, (path, attrs) -> {
            if (attrs.isDirectory()) {
                try (final var children = Files.list(path)) {
                    return children.anyMatch(child -> REST.equals(child.getFileName().toString()));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return false;
        })) {
            return stream.findFirst().orElseThrow(() ->
                    new IllegalStateException("Failed to locate repository root resource in exported data"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
