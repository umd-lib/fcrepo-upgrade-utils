/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */

package org.fcrepo.upgrade.utils;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.jena.riot.Lang;
import org.fcrepo.upgrade.utils.f6.ResourceInfo;
import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * @author pwinckles
 */
public class F5ToF6UpgradeManagerTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    private Config config;
    private Path out;

    @Before
    public void setup() throws IOException {
        out = tempFolder.newFolder().toPath();

        config = new Config();
        config.setForceWindowsMode(true);
        config.setOutputDir(out.toFile());
        config.setSourceVersion(FedoraVersion.V_5);
        config.setTargetVersion(FedoraVersion.V_6);
        config.setBaseUri("http://localhost:8080/rest/");
    }

    @Test
    public void migrateEntireExport() {
        config.setInputDir(new File("src/test/resources/5.1-export"));

        final var upgradeManager = UpgradeManagerFactory.create(config);

        upgradeManager.start();

        assertMigration(Paths.get("src/test/resources/5.1-to-6-expected"));
    }

    @Test
    public void migrateExportFromAContext() {
        config.setBaseUri("http://localhost:8080/fcrepo/rest/");
        config.setInputDir(new File("src/test/resources/5.1-export-with-context"));

        final var upgradeManager = UpgradeManagerFactory.create(config);

        upgradeManager.start();

        assertMigration(Paths.get("src/test/resources/5.1-to-6-expected-with-context"));
    }

    @Test
    public void migrateExportWithMissingTimestamps() {
        config.setInputDir(new File("src/test/resources/5.1-export-missing-timestamps"));
        final var upgradeManager = UpgradeManagerFactory.create(config);
        upgradeManager.start();
        final var ocflRepository = UpgradeManagerFactory.createOcflObjectSessionFactory(config);
        final var session = ocflRepository.newSession("info:fedora");
        final var resourceHeaders = session.streamResourceHeaders().findFirst().orElse(null);
        final var created = resourceHeaders.getCreatedDate();
        final var lastUpdated = resourceHeaders.getLastModifiedDate();
        assertEquals("The root resource's create and last modified should be equal",
                     created, lastUpdated);
        checkInstantNotOlderThan(created, 3000);
        final var session2 = ocflRepository.newSession("info:fedora/simple-container");
        var resourceHeaders2 = session2.streamResourceHeaders().findFirst().orElse(null);
        final var created2 = resourceHeaders2.getCreatedDate();
        final var lastUpdated2 = resourceHeaders2.getLastModifiedDate();
        assertTrue("The last updated date should be greater than the created date",
                   created2.toEpochMilli() < lastUpdated2.toEpochMilli());
        checkInstantNotOlderThan(lastUpdated2, 3000);
    }

    @Test
    public void migrateEntireExportNTriples() {
        config.setInputDir(new File("src/test/resources/5.1-export-ntriples"));
        config.setSrcRdfLang(Lang.NT);

        final var upgradeManager = UpgradeManagerFactory.create(config);

        upgradeManager.start();

        assertMigration(Paths.get("src/test/resources/5.1-to-6-expected"));
    }

    @Test
    public void migrateBinaryReadThroughSymbolicLink() throws IOException {
        final var input = tempFolder.newFolder().toPath().resolve("export");
        org.apache.commons.io.FileUtils.copyDirectory(new File("src/test/resources/5.1-export"), input.toFile());
        final var binary = input.resolve("rest/simple-binary.binary");
        final var stored = tempFolder.newFolder().toPath().resolve("simple-binary-content");
        Files.move(binary, stored);
        Files.createSymbolicLink(binary, stored);
        config.setInputDir(input.toFile());

        UpgradeManagerFactory.create(config).start();

        assertMigration(Paths.get("src/test/resources/5.1-to-6-expected"));
    }

    @Test
    public void migrateFromResourceInfoFile() throws IOException {
        final var input = Paths.get("src/test/resources/5.1-export");
        final var root = ResourceInfo.container("info:fedora", "info:fedora", null, input, "rest");
        final var infoFile = tempFolder.newFile().toPath();
        Files.writeString(infoFile, new ObjectMapper().writeValueAsString(root) + System.lineSeparator());

        config.setInputDir(input.toFile());
        config.setResourceInfoFile(infoFile);

        UpgradeManagerFactory.create(config).start();

        assertMigration(Paths.get("src/test/resources/5.1-to-6-expected"));
    }

    @Test
    public void failWhenExportHasNoRepositoryRoot() throws IOException {
        config.setInputDir(tempFolder.newFolder());

        final var upgradeManager = UpgradeManagerFactory.create(config);

        assertThrows(IllegalStateException.class, upgradeManager::start);
    }

    private void checkInstantNotOlderThan(Instant created, int ms) {
        assertTrue("the timestamp was created in the last " + ms + " milliseconds ",
                   Instant.now().toEpochMilli()-created.toEpochMilli() < ms);
    }

    private void assertMigration(final Path expected) {
        final var expectedRoot = expected.resolve("data").resolve("ocfl-root");
        final var actualRoot = out.resolve("data").resolve("ocfl-root");

        final var expectedFiles = listAllFiles(expectedRoot);
        final var actualFiles = listAllFiles(actualRoot);

        assertThat(actualFiles, Matchers.containsInAnyOrder(expectedFiles.toArray(new String[0])));

        for (final var file : expectedFiles) {
            final var expectedFile = expectedRoot.resolve(file);
            final var actualFile = actualRoot.resolve(file);
            assertEquals(String.format("Files %s and %s are not the same", expectedFile, actualFile),
                    hash(expectedFile), hash(actualFile));
        }
    }

    private Set<String> listAllFiles(final Path root) {
        final var storageExtensions = root.resolve("extensions");
        try (final var files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(f -> !f.getParent().equals(root))
                    .filter(f -> !f.getParent().getParent().equals(storageExtensions))
                    .map(f -> root.relativize(f).toString())
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String hash(final Path path) {
        try {
            return DigestUtils.sha256Hex(Files.newInputStream(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
