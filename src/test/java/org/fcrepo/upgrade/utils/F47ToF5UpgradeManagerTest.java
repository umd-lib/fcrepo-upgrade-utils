/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.upgrade.utils;

import static org.apache.jena.rdf.model.ResourceFactory.createProperty;
import static org.fcrepo.upgrade.utils.RdfConstants.ACCESS_CONTROL;
import static org.fcrepo.upgrade.utils.RdfConstants.ACL_ACCESS_TO;
import static org.fcrepo.upgrade.utils.RdfConstants.ACL_AGENT_CLASS;
import static org.fcrepo.upgrade.utils.RdfConstants.ACL_AGENT_GROUP;
import static org.fcrepo.upgrade.utils.RdfConstants.ACL_DEFAULT;
import static org.fcrepo.upgrade.utils.RdfConstants.ACL_NS;
import static org.fcrepo.upgrade.utils.RdfConstants.AUTHORIZATION;
import static org.fcrepo.upgrade.utils.RdfConstants.FEDORA_LAST_MODIFIED_DATE;
import static org.fcrepo.upgrade.utils.RdfConstants.FOAF_AGENT;
import static org.fcrepo.upgrade.utils.RdfConstants.VCARD_GROUP;
import static org.fcrepo.upgrade.utils.RdfConstants.VCARD_HAS_MEMBER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.riot.Lang;
import org.apache.jena.vocabulary.RDF;
import org.fcrepo.upgrade.utils.f6.RdfUtil;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Tests the {@link F47ToF5UpgradeManagerTest}
 *
 * @author dbernstein
 */

public class F47ToF5UpgradeManagerTest {

    static final String TARGET_DIR = System.getProperty("project.build.directory");

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void testUpgrade() throws Exception {
        //prepare
        final File tmpDir = tempFolder.newFolder();
        final File input = new File(TARGET_DIR + "/test-classes/4.7.5-export");
        final File output = new File(tmpDir, "output");
        output.mkdir();

        final var config = new Config();
        config.setSourceVersion(FedoraVersion.V_4_7_5);
        config.setTargetVersion(FedoraVersion.V_5);
        config.setInputDir(input);
        config.setOutputDir(output);
        //run
        UpgradeManager upgradeManager = UpgradeManagerFactory.create(config);
        upgradeManager.start();
        //ensure all expected files exist
        final String[] expectedFiles =
            new String[]{"rest.ttl",
                         "rest.ttl.headers",
                         "rest/external1",
                         "rest/external1/fcr%3Ametadata.ttl",
                         "rest/container1.ttl",
                         "rest/container1.ttl.headers",
                         "rest/container1/fcr%3Aacl.ttl",
                         "rest/container1/fcr%3Aversions/20201015053947.ttl",
                         "rest/container1/fcr%3Aversions/20201015053947.ttl.headers",
                         "rest/container1/fcr%3Aversions/20201015053526.ttl",
                         "rest/container1/fcr%3Aversions/20201015053526.ttl.headers",
                         "rest/container1/testbinary.binary",
                         "rest/container1/testbinary/fcr%3Ametadata.ttl",
                         "rest/container1/testbinary.binary.headers",
                         "rest/container1/testbinary/fcr%3Ametadata/fcr%3Aversions/20201015053717.ttl",
                         "rest/container1/testbinary/fcr%3Ametadata/fcr%3Aversions/20201015053717.ttl.headers",
                         "rest/container1/testbinary/fcr%3Ametadata/fcr%3Aversions/20201015053848.ttl",
                         "rest/container1/testbinary/fcr%3Ametadata/fcr%3Aversions/20201015053848.ttl.headers",
                         "rest/container1/testbinary/fcr%3Aversions/20201015053848.binary",
                         "rest/container1/testbinary/fcr%3Aversions/20201015053848.binary.headers",
                         "rest/container1/testbinary/fcr%3Aversions/20201015053717.binary",
                         "rest/external1.external.headers",
                         "rest/external1.external"};

        for (String f : expectedFiles) {
            assertTrue(f + " does not exist as expected", new File(output, f).exists());
        }

        final String[] unexpectedFiles =
            new String[]{"rest/acl.ttl",
                         "rest/acl/authZ1.ttl",
                         "rest/acl/authZ2.ttl"};

        for (String f : unexpectedFiles) {
            assertFalse(f + " should not exist.", new File(output, f).exists());
        }
        //ensure external content has been transformed properly

        final String externalContent = FileUtils
            .readFileToString(new File(output, "rest/external1/fcr%3Ametadata.ttl"), "UTF-8");
        assertFalse("external content metadata should contain the mimetype", externalContent.contains("image/jpg"));
        assertFalse("message/external-body should not be present in the external content metadata",
                    externalContent.contains("message/external-body"));

        //ensure the binaries contain NonRDFSource types in their headers
        final Map<String, List<String>> bheadders =
            deserializeHeaders(new File(output, "rest/container1/testbinary.binary.headers"));
        assertTrue("binary does not contain NonRDFSource type in the link headers",
                   bheadders.get("Link").stream().anyMatch(x -> x.contains("NonRDFSource")));

        for (String f : expectedFiles) {
            final var file = new File(output, f);
            if (f.contains("fcr%3Aversions")) {

                if (f.endsWith(".headers")) {
                    final Map<String, List<String>> mHeaders =
                        deserializeHeaders(file);
                    assertTrue("Memento headers do not contain memento type link header",
                               mHeaders.get("Link").stream().anyMatch(x -> x.contains("Memento")));
                    assertTrue("Memento headers do not contain Memento-Datetime header",
                               mHeaders.get("Memento-Datetime") != null);
                } else if (f.contains("fcr:%3Ametadata")) {
                    final var contents = IOUtils.toString(new FileInputStream(file), Charset.defaultCharset());
                    assertTrue("Mementos should not contain links to other mementos",
                               !contents.contains("fcr:versions/"));
                }
            }
        }


        //validate acl
        //ensure there are two authorizations under the hash uri #auth0 and #auth1
        final var model = RdfUtil.parseRdf(Path.of(output.toString(), "rest/container1/fcr%3Aacl.ttl"), Lang.TTL);
        final var authSubjects = new ArrayList<String>();
        model.listStatements().toList()
             .stream().filter(x -> x.getPredicate().equals(RDF.type) && x.getObject().equals(AUTHORIZATION))
             .forEach(x -> {
                 authSubjects.add(x.getSubject().asResource().getURI());
             });

        assertEquals("There should be two authorizations.", 2, authSubjects.size());
        assertTrue("There should be a subject with #auth0 hash uri",
                   authSubjects.contains("http://localhost:8080/rest/container1/fcr:acl#auth0"));
        assertTrue("There should be a subject with #auth1 hash uri",
                   authSubjects.contains("http://localhost:8080/rest/container1/fcr:acl#auth1"));

        final var lastModifiedStatement = model.listStatements().toList().stream()
                                               .filter(x -> x.getPredicate().equals(FEDORA_LAST_MODIFIED_DATE))
                                               .findFirst().get();
        assertTrue("There should be a last modified date", lastModifiedStatement != null);
        assertEquals("The subject should be the acl: ",
                     "http://localhost:8080/rest/container1/fcr:acl",
                     lastModifiedStatement.getSubject().getURI());
    }

    @Test
    public void testUpgradeIgnoresNonRdfFilesInAclDirectory() throws Exception {
        final File input = copyOfExport("4.7.5-export");
        // Only RDF can be an authorization; this used to be parsed as Turtle and abort the upgrade
        Files.writeString(input.toPath().resolve("rest/acl/notes.binary"), "{\"not\": \"turtle\"}\n");

        final File output = upgrade(input);

        assertEquals(List.of("user1", "user2"), container1AclAgents(output));
    }

    @Test
    public void testUpgradeIgnoresOldVersionsOfAuthorizations() throws Exception {
        final File input = copyOfExport("4.7.5-export");
        // An old version of an authorization must not grant access again in the migrated ACL
        addAuthorizationVersion(input, "authZ1", "version.20190101000000", "2019-01-01T00:00:00.000Z",
                                "revoked-user");

        final File output = upgrade(input);

        assertEquals(List.of("user1", "user2"), container1AclAgents(output));
    }

    @Test
    public void testUpgradeConvertsFedora4WebacAgentsAndGroups() throws Exception {
        final File input = copyOfExport("4.7.5-export");
        final var authorization = input.toPath().resolve("rest/acl/authZ1.ttl");
        Files.writeString(authorization, Files.readString(authorization).replace(
                "        ns001:mode             ns001:Read ;\n",
                "        ns001:mode             ns001:Read ;\n" +
                "        ns001:agent            foaf:Agent ;\n" +
                "        ns001:agentClass       <http://localhost:8080/rest/group1> ;\n" +
                "        ns001:agentClass       <http://localhost:8080/rest/missing-group> ;\n" +
                "        ns001:agentClass       <https://other.example.org/rest/group1> ;\n"));
        Files.writeString(input.toPath().resolve("rest/group1.ttl"), String.join("\n",
                "@prefix fedora: <http://fedora.info/definitions/v4/repository#> .",
                "@prefix ldp: <http://www.w3.org/ns/ldp#> .",
                "@prefix foaf: <http://xmlns.com/foaf/0.1/> .",
                "<http://localhost:8080/rest/group1> a fedora:Container, fedora:Resource, ldp:RDFSource, ldp:Container,",
                "        foaf:Group ;",
                "    foaf:member \"user3\", \"user4\" ;",
                "    fedora:hasParent <http://localhost:8080/rest/> ."));

        final File output = upgrade(input);

        final var acl = RdfUtil.parseRdf(Path.of(output.toString(), "rest/container1/fcr%3Aacl.ttl"), Lang.TTL);
        // Everyone, and a group in the repository, keep their meaning; anything Fedora 4 ignored stays as it was
        assertEquals(List.of("user1", "user2"), container1AclAgents(output));
        assertEquals(List.of("http://localhost:8080/rest/missing-group", FOAF_AGENT.getURI(),
                             "https://other.example.org/rest/group1"),
                     sortedUris(acl.listObjectsOfProperty(ACL_AGENT_CLASS).toList()));
        assertEquals(List.of("http://localhost:8080/rest/group1"),
                     sortedUris(acl.listObjectsOfProperty(ACL_AGENT_GROUP).toList()));
        // Fedora 4 applied the ACL to container1's descendants too, which Fedora 5+ does only with acl:default
        assertEquals(List.of("http://localhost:8080/rest/container1"),
                     sortedUris(acl.listObjectsOfProperty(ACL_DEFAULT).toList()).stream().distinct()
                                   .collect(Collectors.toList()));
        assertEquals(acl.listSubjectsWithProperty(ACL_ACCESS_TO).toSet(),
                     acl.listSubjectsWithProperty(ACL_DEFAULT).toSet());

        final var group = RdfUtil.parseRdf(Path.of(output.toString(), "rest/group1.ttl"), Lang.TTL);
        final var groupResource = group.createResource("http://localhost:8080/rest/group1");
        assertTrue(group.contains(groupResource, RDF.type, VCARD_GROUP));
        assertEquals(List.of("user3", "user4"), group.listObjectsOfProperty(groupResource, VCARD_HAS_MEMBER).toList()
                                                     .stream().map(member -> member.asLiteral().getString())
                                                     .sorted().collect(Collectors.toList()));
    }

    private static List<String> sortedUris(final List<RDFNode> nodes) {
        return nodes.stream().map(node -> node.asResource().getURI()).sorted().collect(Collectors.toList());
    }

    private File copyOfExport(final String name) throws IOException {
        final File copy = tempFolder.newFolder();
        FileUtils.copyDirectory(new File(TARGET_DIR + "/test-classes/" + name), copy);
        return copy;
    }

    private File upgrade(final File input) throws Exception {
        final File output = tempFolder.newFolder();
        final var config = new Config();
        config.setSourceVersion(FedoraVersion.V_4_7_5);
        config.setTargetVersion(FedoraVersion.V_5);
        config.setInputDir(input);
        config.setOutputDir(output);
        UpgradeManagerFactory.create(config).start();
        return output;
    }

    /**
     * Adds an old version of an authorization in the ACL at rest/acl, granting access to an agent the current
     * authorization does not
     */
    private void addAuthorizationVersion(final File export, final String authorization, final String label,
                                         final String created, final String agent) throws IOException {
        final var acl = export.toPath().resolve("rest/acl");
        final var uri = "http://localhost:8080/rest/acl/" + authorization;
        final var mementoUri = uri + "/fcr:versions/" + label;
        final var current = Files.readString(acl.resolve(authorization + ".ttl"));
        final var versions = acl.resolve(authorization).resolve("fcr%3Aversions");
        Files.createDirectories(versions);
        Files.writeString(versions.resolve(label + ".ttl"),
                          current.replace("<" + uri + ">", "<" + mementoUri + ">")
                                 .replace("\"user1\"", "\"" + agent + "\""));
        Files.writeString(acl.resolve(authorization).resolve("fcr%3Aversions.ttl"), String.join("\n",
                "@prefix fedora: <http://fedora.info/definitions/v4/repository#> .",
                "<" + uri + "> fedora:hasVersion <" + mementoUri + "> .",
                "<" + mementoUri + "> fedora:hasVersionLabel \"" + label + "\" ;",
                "    fedora:created \"" + created + "\"^^<http://www.w3.org/2001/XMLSchema#dateTime> ."));
    }

    private List<String> container1AclAgents(final File output) {
        final var model = RdfUtil.parseRdf(Path.of(output.toString(), "rest/container1/fcr%3Aacl.ttl"), Lang.TTL);
        return model.listObjectsOfProperty(createProperty(ACL_NS + "agent")).toList().stream()
                    .map(agent -> agent.asLiteral().getString())
                    .sorted()
                    .collect(Collectors.toList());
    }

    @Test
    public void testUpgradeWithNTriples() throws Exception {
        //prepare
        final File tmpDir = tempFolder.newFolder();
        final File input = new File(TARGET_DIR + "/test-classes/4.7.5-export-ntriples");
        final File output = new File(tmpDir, "output");
        output.mkdir();

        final var config = new Config();
        config.setSourceVersion(FedoraVersion.V_4_7_5);
        config.setTargetVersion(FedoraVersion.V_5);
        config.setInputDir(input);
        config.setOutputDir(output);
        config.setSrcRdfLang(Lang.NT);
        //run
        UpgradeManager upgradeManager = UpgradeManagerFactory.create(config);
        upgradeManager.start();
        //ensure all expected files exist
        final String[] expectedFiles =
            new String[]{"rest.nt",
                         "rest.nt.headers",
                         "rest/external1",
                         "rest/external1/fcr%3Ametadata.nt",
                         "rest/container1.nt",
                         "rest/container1.nt.headers",
                         "rest/container1/fcr%3Aacl.nt",
                         "rest/container1/fcr%3Aversions/20201015053947.nt",
                         "rest/container1/fcr%3Aversions/20201015053947.nt.headers",
                         "rest/container1/fcr%3Aversions/20201015053526.nt",
                         "rest/container1/fcr%3Aversions/20201015053526.nt.headers",
                         "rest/container1/testbinary.binary",
                         "rest/container1/testbinary/fcr%3Ametadata.nt",
                         "rest/container1/testbinary.binary.headers",
                         "rest/container1/testbinary/fcr%3Ametadata/fcr%3Aversions/20201015053717.nt",
                         "rest/container1/testbinary/fcr%3Ametadata/fcr%3Aversions/20201015053717.nt.headers",
                         "rest/container1/testbinary/fcr%3Ametadata/fcr%3Aversions/20201015053848.nt",
                         "rest/container1/testbinary/fcr%3Ametadata/fcr%3Aversions/20201015053848.nt.headers",
                         "rest/container1/testbinary/fcr%3Aversions/20201015053848.binary",
                         "rest/container1/testbinary/fcr%3Aversions/20201015053848.binary.headers",
                         "rest/container1/testbinary/fcr%3Aversions/20201015053717.binary",
                         "rest/external1.external.headers",
                         "rest/external1.external"};

        for (String f : expectedFiles) {
            assertTrue(f + " does not exist as expected", new File(output, f).exists());
        }

        final String[] unexpectedFiles =
            new String[]{"rest/acl.nt",
                         "rest/acl/authZ1.nt",
                         "rest/acl/authZ2.nt"};

        for (String f : unexpectedFiles) {
            assertFalse(f + " should not exist.", new File(output, f).exists());
        }
        //ensure external content has been transformed properly

        final String externalContent = FileUtils
            .readFileToString(new File(output, "rest/external1/fcr%3Ametadata.nt"), "UTF-8");
        assertFalse("external content metadata should contain the mimetype", externalContent.contains("image/jpg"));
        assertFalse("message/external-body should not be present in the external content metadata",
                    externalContent.contains("message/external-body"));

        //ensure the binaries contain NonRDFSource types in their headers
        final Map<String, List<String>> bheadders =
            deserializeHeaders(new File(output, "rest/container1/testbinary.binary.headers"));
        assertTrue("binary does not contain NonRDFSource type in the link headers",
                   bheadders.get("Link").stream().anyMatch(x -> x.contains("NonRDFSource")));

        for (String f : expectedFiles) {
            final var file = new File(output, f);
            if (f.contains("fcr%3Aversions")) {

                if (f.endsWith(".headers")) {
                    final Map<String, List<String>> mHeaders =
                        deserializeHeaders(file);
                    assertTrue("Memento headers do not contain memento type link header",
                               mHeaders.get("Link").stream().anyMatch(x -> x.contains("Memento")));
                    assertTrue("Memento headers do not contain Memento-Datetime header",
                               mHeaders.get("Memento-Datetime") != null);
                } else if (f.contains("fcr:%3Ametadata")) {
                    final var contents = IOUtils.toString(new FileInputStream(file), Charset.defaultCharset());
                    assertTrue("Mementos should not contain links to other mementos",
                               !contents.contains("fcr:versions/"));
                }
            }
        }

        //validate acl
        //ensure there are two authorizations under the hash uri #auth0 and #auth1
        final var model = RdfUtil.parseRdf(Path.of(output.toString(), "rest/container1/fcr%3Aacl.nt"), Lang.TTL);
        final var authSubjects = new ArrayList<String>();
        model.listStatements().toList()
             .stream().filter(x -> x.getPredicate().equals(RDF.type) && x.getObject().equals(AUTHORIZATION))
             .forEach(x -> {
                 authSubjects.add(x.getSubject().asResource().getURI());
             });

        assertEquals("There should be two authorizations.", 2, authSubjects.size());
        assertTrue("There should be a subject with #auth0 hash uri",
                   authSubjects.contains("http://localhost:8080/rest/container1/fcr:acl#auth0"));
        assertTrue("There should be a subject with #auth1 hash uri",
                   authSubjects.contains("http://localhost:8080/rest/container1/fcr:acl#auth1"));

        final var lastModifiedStatement = model.listStatements().toList().stream()
                                               .filter(x -> x.getPredicate().equals(FEDORA_LAST_MODIFIED_DATE))
                                               .findFirst().get();
        assertTrue("There should be a last modified date", lastModifiedStatement != null);
        assertEquals("The subject should be be the acl: ",
                     "http://localhost:8080/rest/container1/fcr:acl",
                     lastModifiedStatement.getSubject().getURI());
    }

    @Test
    public void testUpgradeSkipAcls() throws Exception {
        //prepare
        final File tmpDir = tempFolder.newFolder();
        final File input = new File(TARGET_DIR + "/test-classes/4.7.5-export");
        final File output = new File(tmpDir, "output");
        output.mkdir();

        final var config = new Config();
        config.setSourceVersion(FedoraVersion.V_4_7_5);
        config.setTargetVersion(FedoraVersion.V_5);
        config.setInputDir(input);
        config.setOutputDir(output);
	config.setSkipAcls(true);
        //run
        UpgradeManager upgradeManager = UpgradeManagerFactory.create(config);
        upgradeManager.start();
        //ensure all expected files exist
        final String[] expectedFiles =
            new String[]{"rest.ttl",
                         "rest.ttl.headers",
                         "rest/acl.ttl",
                         "rest/acl/authZ1.ttl",
                         "rest/acl/authZ2.ttl",
                         "rest/external1",
                         "rest/external1/fcr%3Ametadata.ttl",
                         "rest/container1.ttl",
                         "rest/container1.ttl.headers",
                         "rest/container1/fcr%3Aversions/20201015053947.ttl",
                         "rest/container1/fcr%3Aversions/20201015053947.ttl.headers",
                         "rest/container1/fcr%3Aversions/20201015053526.ttl",
                         "rest/container1/fcr%3Aversions/20201015053526.ttl.headers",
                         "rest/container1/testbinary.binary",
                         "rest/container1/testbinary/fcr%3Ametadata.ttl",
                         "rest/container1/testbinary.binary.headers",
                         "rest/container1/testbinary/fcr%3Ametadata/fcr%3Aversions/20201015053717.ttl",
                         "rest/container1/testbinary/fcr%3Ametadata/fcr%3Aversions/20201015053717.ttl.headers",
                         "rest/container1/testbinary/fcr%3Ametadata/fcr%3Aversions/20201015053848.ttl",
                         "rest/container1/testbinary/fcr%3Ametadata/fcr%3Aversions/20201015053848.ttl.headers",
                         "rest/container1/testbinary/fcr%3Aversions/20201015053848.binary",
                         "rest/container1/testbinary/fcr%3Aversions/20201015053848.binary.headers",
                         "rest/container1/testbinary/fcr%3Aversions/20201015053717.binary",
                         "rest/external1.external.headers",
                         "rest/external1.external"};

        for (String f : expectedFiles) {
            assertTrue(f + " does not exist as expected", new File(output, f).exists());
        }

        final String[] unexpectedFiles =
            new String[]{"rest/container1/fcr%3Aacl.ttl"};

        for (String f : unexpectedFiles) {
            assertFalse(f + " should not exist.", new File(output, f).exists());
        }

        //validate acl special handling was skipped
        //ensure access control statement is still present in the container
	//acl and authorization resources were migrated and checked in expectedFiles
        final var model = RdfUtil.parseRdf(Path.of(output.toString(), "rest/container1.ttl"), Lang.TTL);
        final var accessControlStatement = model.listStatements().toList().stream()
                                                .filter(x -> x.getPredicate().equals(ACCESS_CONTROL))
                                                .findFirst().get();
        assertTrue("There should still be an access control statement", accessControlStatement != null);
    }

    private Map<String, List<String>> deserializeHeaders(final File headerFile) throws IOException {
        final byte[] mapData = Files.readAllBytes(Paths.get(headerFile.toURI()));
        final ObjectMapper objectMapper = new ObjectMapper();
        return objectMapper.readValue(mapData, new TypeReference<HashMap<String, List<String>>>() {
        });
    }

}
