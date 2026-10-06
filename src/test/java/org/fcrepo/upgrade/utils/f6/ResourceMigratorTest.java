/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */

package org.fcrepo.upgrade.utils.f6;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.apache.jena.vocabulary.RDF;
import org.fcrepo.client.FedoraTypes;
import org.fcrepo.storage.ocfl.OcflObjectSession;
import org.fcrepo.storage.ocfl.OcflObjectSessionFactory;
import org.fcrepo.storage.ocfl.ResourceContent;
import org.fcrepo.storage.ocfl.ResourceHeaders;
import org.fcrepo.upgrade.utils.Config;
import org.fcrepo.upgrade.utils.UpgradeManagerFactory;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @author pwinckles
 */
public class ResourceMigratorTest {

    private static final String ROOT = "info:fedora";
    private static final String FCR_META = "fcr:metadata";
    private static final String FCR_ACL = "fcr:acl";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private ResourceMigrator migrator;
    private Config config;
    private Config expectedConfig;
    private OcflObjectSessionFactory migrationOcflFactory;
    private OcflObjectSessionFactory expectedOcflFactory;

    private Path input;
    private Path output;

    private Path rootInner;

    @Before
    public void setup() throws IOException {
        input = Paths.get("src/test/resources/5.1-export");
        output = temp.newFolder().toPath();

        rootInner = input.resolve("rest");

        config = new Config();
        config.setInputDir(input.toFile());
        config.setOutputDir(output.toFile());
        config.setBaseUri("http://localhost:8080/rest");

        migrationOcflFactory = UpgradeManagerFactory.createOcflObjectSessionFactory(config);

        migrator = new ResourceMigrator(config, migrationOcflFactory);

        expectedConfig = new Config();
        expectedConfig.setOutputDir(new File("src/test/resources/5.1-to-6-expected"));
        expectedOcflFactory = UpgradeManagerFactory.createOcflObjectSessionFactory(expectedConfig);
    }


    @Test
    public void migrateBinary() {
        final var info = binaryInfo("simple-binary");

        migrateNoChildren(info);

        assertResourcesSame(info);
    }

    @Test
    public void migratingResourceTwiceShouldDoNothing() {
        final var info = binaryInfo("simple-binary");

        migrateNoChildren(info);
        migrateNoChildren(info);

        assertResourcesSame(info);
    }

    @Test
    public void migrateExternalBinaryProxied() {
        final var info = externalBinaryInfo("external-proxied");

        migrateNoChildren(info);

        assertResourcesSame(info);
    }

    @Test
    public void migrateExternalBinaryRedirected() {
        final var info = externalBinaryInfo("external-redirected");

        migrateNoChildren(info);

        assertResourcesSame(info);
    }

    @Test
    public void migrateBasicContainerWithNoChildren() {
        final var info = containerInfo("simple-container");

        migrateNoChildren(info);

        assertResourcesSame(info);
    }

    @Test
    public void migrateBasicContainerWithChildren() {
        final var info = containerInfo("container-with-children");

        final var children = migrate(info);

        assertEquals(2, children.size());

        assertChildInfo(info, "binary-child", ResourceInfo.Type.BINARY, children.get(0));
        assertChildInfo(info, "container-child", ResourceInfo.Type.CONTAINER, children.get(1));

        assertResourcesSame(info);
    }

    @Test
    public void migrateBasicContainerWithChildrenTwiceShouldDoNothingButReturnChildren() {
        final var info = containerInfo("container-with-children");

        migrate(info);
        final var children = migrate(info);

        assertEquals(2, children.size());

        assertChildInfo(info, "binary-child", ResourceInfo.Type.BINARY, children.get(0));
        assertChildInfo(info, "container-child", ResourceInfo.Type.CONTAINER, children.get(1));

        assertResourcesSame(info);
    }

    @Test
    public void migrateDirectContainer() {
        final var info = containerInfo("direct-container");

        migrateNoChildren(info);

        assertResourcesSame(info);
    }

    @Test
    public void migrateIndirectContainer() {
        final var info = containerInfo("indirect-container");

        migrateNoChildren(info);

        assertResourcesSame(info);
    }

    @Test
    public void migrateBinaryWithAcl() {
        final var info = binaryInfo("binary-with-acl");

        migrateNoChildren(info);

        assertResourcesSame(info);
    }

    @Test
    public void migrateContainerWithAcl() {
        final var info = containerInfo("container-with-acl");

        migrateNoChildren(info);

        assertResourcesSame(info);
    }

    @Test
    public void migrateBinaryWithVersions() {
        final var info = binaryInfo("binary-with-versions");

        migrateNoChildren(info);

        assertResourcesSame(info);
    }

    @Test
    public void migrateContainerWithVersions() {
        final var info = containerInfo("container-with-versions");

        migrateNoChildren(info);

        assertResourcesSame(info);
    }

    @Test
    public void migrateContainerWithGhostNodes() {
        final var info = containerInfo("container-with-ghosts");

        final var children = migrate(info);

        assertEquals(2, children.size());

        assertChildInfo(info, "a/b/c/hidden-container", ResourceInfo.Type.CONTAINER, children.get(0));
        assertChildInfo(info, "a/b/ghost-binary", ResourceInfo.Type.BINARY, children.get(1));

        assertResourcesSame(info);
    }

    @Test
    public void migrateBasicContainerWithCustomPredicateUsage() {
        final var info = containerInfo("container-with-custom-predicate-usage");

        migrateNoChildren(info);

        assertResourcesSame(info);
    }

    @Test
    public void migrateBinaryWithEncodedName() {
        final var info = binaryInfo("binary:with!encoding");

        migrateNoChildren(info);

        assertResourcesSame(info);
    }

    @Test
    public void rollbackMigrationWhenExceptionThrown() {
        final var info = binaryInfo("broken-binary");
        try {
            migrate(info);
            fail("expected exception");
        } catch (RuntimeException e) {
            final var session = migrationOcflFactory.newSession(info.getFullId());
            assertFalse(info.getFullId() + "should not exist", session.containsResource(info.getFullId()));
        }
    }

    @Test
    public void migrateBasicContainerAGWithNoChildren() throws Exception {
        config.setArchivalGroupRdfTypes(FedoraTypes.LDP_BASIC_CONTAINER);
        migrator = new ResourceMigrator(config, migrationOcflFactory);
        final var info = containerInfo("simple-container");

        migrateNoChildren(info);

        final var expectedHeaders = expectedBuilder(info.getFullId())
                .withArchivalGroup(true)
                .build();

        assertHeadSame(info.getFullId(), expectedHeaders);
    }

    @Test
    public void migrateBasicContainerAGWithChildren() throws Exception {
        config.setArchivalGroupRdfTypes(FedoraTypes.LDP_BASIC_CONTAINER);
        migrator = new ResourceMigrator(config, migrationOcflFactory);
        final var info = containerInfo("container-with-children");

        final var children = migrate(info);
        children.forEach(this::migrateNoChildren);

        final var expectedHeaders = expectedBuilder(info.getFullId())
                .withArchivalGroup(true)
                .withObjectRoot(true)
                // Memento date of AG matches last modified date of its children
                .withMementoCreatedDate(Instant.parse("2020-09-11T18:15:28.076Z"))
                .build();
        assertHeadSame(info.getFullId(), expectedHeaders);

        assertEquals(2, children.size());

        final var binaryChild = children.get(0);
        final var expectedBinaryHeaders = expectedBuilder(binaryChild.getFullId())
                .withArchivalGroupId(info.getFullId())
                .withObjectRoot(false)
                .withArchivalGroup(false)
                .build();
        final var expectedBinaryContent = expectedContent(binaryChild.getFullId());

        assertChildInfo(info, "binary-child", ResourceInfo.Type.BINARY, binaryChild);
        assertHeadSame(binaryChild.getFullId(), expectedBinaryHeaders, expectedBinaryContent.getContentStream().get());

        final var containerChild = children.get(1);
        final var expectedContainerHeaders = expectedBuilder(containerChild.getFullId())
                .withArchivalGroupId(info.getFullId())
                .withObjectRoot(false)
                .withArchivalGroup(false)
                .withContentPath("container-child/fcr-container.nt")
                .build();
        assertChildInfo(info, "container-child", ResourceInfo.Type.CONTAINER, containerChild);
        final var expectedContent = Files.newInputStream(Paths.get(
                "src/test/resources/5.1-to-6-expected/data/ocfl-root/56d/ed5/34e/" +
                     "56ded534e5e1683bbffd6724f71ae467eac4689294da024e7e9cfde511944303/v1/content/fcr-container.nt"));
        assertHeadSame(containerChild.getFullId(), expectedContainerHeaders, expectedContent);
    }

    @Test
    public void migrateBasicContainerAGWithAcl() {
        config.setArchivalGroupRdfTypes(FedoraTypes.LDP_BASIC_CONTAINER);
        migrator = new ResourceMigrator(config, migrationOcflFactory);
        final var info = containerInfo("container-with-acl");

        migrateNoChildren(info);

        final var session = migrationOcflFactory.newSession(info.getFullId());
        final var headers = session.readHeaders(info.getFullId());
        assertTrue(headers.isArchivalGroup());
        assertTrue(headers.isObjectRoot());

        // The AG's own ACL lives in the AG's object and must be recorded as a member of it
        final var aclId = join(info.getFullId(), FCR_ACL);
        assertTrue(aclId + " should exist", session.containsResource(aclId));
        assertEquals(info.getFullId(), session.readHeaders(aclId).getArchivalGroupId());
    }

    @Test
    public void migrateBasicContainerAGWithVersions() {
        config.setArchivalGroupRdfTypes(FedoraTypes.LDP_BASIC_CONTAINER);
        migrator = new ResourceMigrator(config, migrationOcflFactory);
        final var info = containerInfo("container-with-versions");

        migrateNoChildren(info);

        final var expectedVersions = expectedOcflFactory.newSession(info.getFullId())
                .listVersions(info.getFullId());
        final var session = migrationOcflFactory.newSession(info.getFullId());
        final var versions = session.listVersions(info.getFullId());
        assertEquals(expectedVersions.size(), versions.size());

        // Every version of the AG must be the AG root, not a member of itself
        for (final var version : versions) {
            final var headers = session.readHeaders(info.getFullId(), version.getVersionNumber());
            assertTrue(headers.isArchivalGroup());
            assertTrue(headers.isObjectRoot());
            assertNull(headers.getArchivalGroupId());
        }
    }

    @Test
    public void migrateResumedAGRoot() {
        config.setArchivalGroupRdfTypes(FedoraTypes.LDP_BASIC_CONTAINER);
        migrator = new ResourceMigrator(config, migrationOcflFactory);
        // A resource-info file written after a failed AG root records the root as its own archival group
        final var id = id("container-with-children");
        final var info = ResourceInfo.container(ROOT, id, id, rootInner, encode("container-with-children"));

        final var children = migrate(info);
        children.forEach(this::migrateNoChildren);

        final var session = migrationOcflFactory.newSession(id);
        final var headers = session.readHeaders(id);
        assertTrue(headers.isArchivalGroup());
        assertTrue(headers.isObjectRoot());
        assertNull(headers.getArchivalGroupId());

        assertEquals(2, children.size());
        for (final var child : children) {
            assertEquals(id, child.getArchivalGroupId());
            assertEquals(id, session.readHeaders(child.getFullId()).getArchivalGroupId());
        }
    }

    private ResourceHeaders.Builder expectedBuilder(final String id) {
        final var expectedSession = expectedOcflFactory.newSession(id);
        final var expectedHeaders = expectedSession.readHeaders(id);
        return ResourceHeaders.builder(expectedHeaders);
    }

    private ResourceContent expectedContent(final String id) {
        final var expectedSession = expectedOcflFactory.newSession(id);
        return expectedSession.readContent(id);
    }

    private List<ResourceInfo> migrate(final ResourceInfo info) {
        final var children = migrator.migrate(info);
        children.sort(Comparator.comparing(ResourceInfo::getFullId));
        return children;
    }

    private void migrateNoChildren(final ResourceInfo info) {
        assertEquals(0, migrate(info).size());
    }

    private void assertResourcesSame(final ResourceInfo info) {
        final var id = info.getFullId();

        final var actualSession = migrationOcflFactory.newSession(id);
        final var expectedSession = expectedOcflFactory.newSession(id);

        assertContentSame(id, expectedSession, actualSession);

        if (info.getType() == ResourceInfo.Type.BINARY) {
            final var descId = join(id, FCR_META);
            assertContentSame(descId, expectedSession, actualSession);
        }

        final var aclId = join(id, FCR_ACL);

        if (expectedSession.containsResource(aclId)) {
            assertContentSame(aclId, expectedSession, actualSession);
        } else {
            assertFalse(id + " should not have an acl", actualSession.containsResource(aclId));
        }
    }

    private void assertContentSame(final String id,
                                   final OcflObjectSession expectedSession,
                                   final OcflObjectSession actualSession) {
        assertTrue(id + " should exist", actualSession.containsResource(id));
        assertTrue(id + " should exist", expectedSession.containsResource(id));

        assertEquals(expectedSession.listVersions(id), actualSession.listVersions(id));

        expectedSession.listVersions(id).forEach(version -> {
            final var actual = actualSession.readContent(id, version.getVersionNumber());
            final var expected = expectedSession.readContent(id, version.getVersionNumber());

            assertEquals(expected.getHeaders(), actual.getHeaders());

            if (expected.getContentStream().isEmpty()) {
                assertTrue(id + " content should be null", actual.getContentStream().isEmpty());
            } else {
                assertEquals(id + " content mismatch",
                        hash(expected.getContentStream().get()), hash(actual.getContentStream().get()));
            }
        });
    }

    private void assertHeadSame(final String id,
                                final ResourceHeaders expectedHeaders) {
        assertHeadSame(id, expectedHeaders, InputStream.nullInputStream());
    }

    private void assertHeadSame(final String id,
                                final ResourceHeaders expectedHeaders,
                                final InputStream expectedContent) {
        final String idForSession = expectedHeaders.getArchivalGroupId() == null ? id :
                expectedHeaders.getArchivalGroupId();
        final var actualSession = migrationOcflFactory.newSession(idForSession);
        assertTrue(id + " should exist", actualSession.containsResource(id));

        final var versions = actualSession.listVersions(id);
        final var headVersion = versions.get(versions.size() - 1).getVersionNumber();
        final var actualHeaders = actualSession.readHeaders(id, headVersion);
        assertEquals(expectedHeaders, actualHeaders);

        final var actualContent = actualSession.readContent(id, headVersion);
        if (expectedContent == null) {
            assertTrue(id + " content should be null", actualContent.getContentStream().isEmpty());
        } else {
            assertEquals(id + " content mismatch",
                    hash(expectedContent), hash(actualContent.getContentStream().get()));
        }
    }

    private void assertChildInfo(final ResourceInfo parent,
                                 final String name,
                                 final ResourceInfo.Type type,
                                 final ResourceInfo child) {
        final var id = join(parent.getFullId(), name);

        var resolvedName = name;
        var ghosts = "";

        if (name.contains("/")) {
            final var index = name.lastIndexOf("/");
            resolvedName = name.substring(index + 1);
            ghosts = name.substring(0, index);
        }

        final var encoded = encode(resolvedName);

        assertEquals(parent.getFullId(), child.getParentId());
        assertEquals(id, child.getFullId());
        assertEquals(encoded, child.getNameEncoded());
        assertEquals(type, child.getType());
        assertEquals(parent.getInnerDirectory().resolve(ghosts), child.getOuterDirectory());
        assertEquals(child.getOuterDirectory().resolve(encoded), child.getInnerDirectory());
    }

    private ResourceInfo binaryInfo(final String name) {
        return ResourceInfo.binary(ROOT, join(ROOT, name), null, rootInner, encode(name));
    }

    private ResourceInfo externalBinaryInfo(final String name) {
        return ResourceInfo.externalBinary(ROOT, join(ROOT, name), null, rootInner, encode(name));
    }

    private ResourceInfo containerInfo(final String name) {
        return ResourceInfo.container(ROOT, join(ROOT, name), null, rootInner, encode(name));
    }

    private String encode(final String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String id(final String value) {
        return join(ROOT, value);
    }

    private String join(final String left, final String right) {
        return left + "/" + right;
    }

    private String hash(final InputStream stream) {
        try {
            return DigestUtils.sha256Hex(stream);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

}
