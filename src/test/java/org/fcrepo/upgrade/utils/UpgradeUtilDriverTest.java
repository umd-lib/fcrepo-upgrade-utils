/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.upgrade.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.FileUtils;
import org.apache.jena.riot.Lang;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests the {@link UpgradeUtilDriver}
 */
public class UpgradeUtilDriverTest {

    private static final String F5_EXPORT = "src/test/resources/5.1-export";
    private static final String F47_EXPORT = "target/test-classes/4.7.5-export";
    private static final String BASE_URI = "http://localhost:8080/rest/";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private UpgradeUtilDriver driver;
    private File out;

    @Before
    public void setup() throws IOException {
        driver = new UpgradeUtilDriver();
        out = temp.newFolder();
    }

    private Config parse(final String... args) {
        return driver.parseOptions(driver.options(), args);
    }

    private String[] f6Args(final String... extra) {
        final List<String> args = new ArrayList<>(List.of("-i", F5_EXPORT, "-o", out.getAbsolutePath(),
                "-s", "5+", "-t", "6+", "-u", BASE_URI));
        args.addAll(List.of(extra));
        return args.toArray(new String[0]);
    }

    private void assertExit(final String... args) {
        final var e = assertThrows(UpgradeUtilDriver.ExitException.class, () -> parse(args));
        assertEquals(1, e.getStatus());
    }

    @Test
    public void testS3ConnectionDefaultsAndBadValues() {
        final var config = parse(f6Args("--write-to-s3", "--s3-bucket", "bucket"));
        assertEquals(Config.DEFAULT_S3_MAX_CONNECTIONS, config.getS3MaxConnections());
        assertEquals(Config.DEFAULT_S3_CONNECTION_ACQUISITION_TIMEOUT_SECONDS,
                config.getS3ConnectionAcquisitionTimeoutSeconds());
        assertExit(f6Args("--write-to-s3", "--s3-bucket", "bucket", "--s3-max-connections", "0"));
        assertExit(f6Args("--write-to-s3", "--s3-bucket", "bucket", "--s3-connection-acquisition-timeout", "ten"));
    }

    @Test
    public void testParseAllF6Options() {
        final var config = parse(f6Args(
                "-r", "application/n-triples",
                "-p", "2",
                "-d", "sha256",
                "--migration-user", "user",
                "--migration-user-address", "info:user",
                "--skip-acls",
                "-ag", "http://example.com/AG",
                "--write-to-s3",
                "--s3-bucket", "bucket",
                "--s3-prefix", "prefix",
                "--s3-region", "us-east-1",
                "--s3-endpoint", "http://localhost:9000",
                "--s3-path-style-access",
                "--s3-access-key", "access",
                "--s3-secret-key", "secret",
                "--s3-max-connections", "400",
                "--s3-connection-acquisition-timeout", "1200",
                "-R", "remaining.log"));
        assertEquals(400, config.getS3MaxConnections());
        assertEquals(1200, config.getS3ConnectionAcquisitionTimeoutSeconds());

        assertEquals(FedoraVersion.V_5, config.getSourceVersion());
        assertEquals(FedoraVersion.V_6, config.getTargetVersion());
        assertEquals(new File(F5_EXPORT), config.getInputDir());
        assertEquals(out, config.getOutputDir());
        assertEquals(BASE_URI, config.getBaseUri());
        assertEquals(Lang.NT, config.getSrcRdfLang());
        assertEquals(Integer.valueOf(2), config.getThreads());
        assertEquals("sha256", config.getDigestAlgorithm());
        assertEquals("user", config.getFedoraUser());
        assertEquals("info:user", config.getFedoraUserAddress());
        assertTrue(config.isSkipAcls());
        assertEquals("http://example.com/AG", config.getArchivalGroupRdfTypes());
        assertTrue(config.isWriteToS3());
        assertEquals("bucket", config.getS3Bucket());
        assertEquals("prefix", config.getS3Prefix());
        assertEquals("us-east-1", config.getS3Region());
        assertEquals("http://localhost:9000", config.getS3Endpoint());
        assertTrue(config.isS3PathStyleAccess());
        assertEquals("access", config.getS3AccessKey());
        assertEquals("secret", config.getS3SecretKey());
        assertEquals(Paths.get("remaining.log"), config.getResourceInfoFile());
    }

    @Test
    public void testParseF47ToF5Defaults() {
        final var config = parse("-i", F47_EXPORT, "-o", out.getAbsolutePath(), "-s", "4.7.5", "-t", "5+");

        assertEquals(FedoraVersion.V_4_7_5, config.getSourceVersion());
        assertEquals(FedoraVersion.V_5, config.getTargetVersion());
        assertNull(config.getBaseUri());
        assertEquals(Config.DEFAULT_SRC_RDF_LANG, config.getSrcRdfLang());
        assertEquals(Config.DEFAULT_DIGEST_ALGORITHM, config.getDigestAlgorithm());
        assertEquals(Config.DEFAULT_USER, config.getFedoraUser());
        assertEquals(Config.DEFAULT_USER_ADDRESS, config.getFedoraUserAddress());
        assertFalse(config.isSkipAcls());
        assertFalse(config.isWriteToS3());
        assertNull(config.getResourceInfoFile());
    }

    @Test
    public void testDefaultOutputDirIsCreated() throws IOException {
        final var config = parse("-i", F47_EXPORT, "-s", "4.7.5", "-t", "5+");
        final var outputDir = config.getOutputDir();
        try {
            assertTrue(outputDir.getName().startsWith("output_"));
            assertTrue(outputDir.isDirectory());
        } finally {
            FileUtils.deleteDirectory(outputDir);
        }
    }

    @Test
    public void testMissingRequiredOption() {
        assertExit("-i", F5_EXPORT, "-s", "5+");
    }

    @Test
    public void testInputDirDoesNotExist() {
        assertExit("-i", new File(out, "missing").getAbsolutePath(), "-s", "5+", "-t", "6+", "-u", BASE_URI);
    }

    @Test
    public void testUnexpectedArgument() {
        assertExit(f6Args("unexpected"));
    }

    @Test
    public void testOutputDirCannotBeCreated() throws IOException {
        final var file = temp.newFile();
        assertExit("-i", F5_EXPORT, "-o", new File(file, "sub").getAbsolutePath(),
                "-s", "5+", "-t", "6+", "-u", BASE_URI);
    }

    @Test
    public void testInvalidRdfContentType() {
        assertExit(f6Args("-r", "application/unknown"));
    }

    @Test
    public void testInvalidDigestAlgorithm() {
        assertExit(f6Args("-d", "md5"));
    }

    @Test
    public void testMissingBaseUriForF6() {
        assertExit("-i", F5_EXPORT, "-o", out.getAbsolutePath(), "-s", "5+", "-t", "6+");
    }

    @Test
    public void testS3WithoutBucket() {
        assertExit(f6Args("--write-to-s3"));
    }

    @Test
    public void testRunF5ToF6() {
        driver.run(f6Args("-p", "1"));
        assertTrue(Files.isDirectory(out.toPath().resolve("data").resolve("ocfl-root")));
    }

    @Test
    public void testRunUnsupportedMigrationPath() {
        final var e = assertThrows(UpgradeUtilDriver.ExitException.class,
                () -> driver.run(new String[]{"-i", F5_EXPORT, "-o", out.getAbsolutePath(), "-s", "6+", "-t", "5+"}));
        assertEquals(1, e.getStatus());
    }

    @Test
    public void testMainF47ToF5() {
        UpgradeUtilDriver.main(new String[]{"-i", F47_EXPORT, "-o", out.getAbsolutePath(), "-s", "4.7.5",
                "-t", "5+"});
        assertTrue(new File(out, "rest.ttl").exists());
    }

    @Test
    public void testMainLogsInvalidArgument() {
        // An invalid version is reported by main without exiting
        UpgradeUtilDriver.main(new String[]{"-i", F5_EXPORT, "-o", out.getAbsolutePath(), "-s", "7", "-t", "6+"});
    }
}
