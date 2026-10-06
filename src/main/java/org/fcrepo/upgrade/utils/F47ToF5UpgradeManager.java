/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.upgrade.utils;

import static java.net.URI.create;
import static org.apache.jena.rdf.model.ResourceFactory.createResource;
import static org.fcrepo.upgrade.utils.HttpConstants.CONTENT_LOCATION_HEADER;
import static org.fcrepo.upgrade.utils.HttpConstants.CONTENT_TYPE_HEADER;
import static org.fcrepo.upgrade.utils.HttpConstants.LINK_HEADER;
import static org.fcrepo.upgrade.utils.HttpConstants.LOCATION_HEADER;
import static org.fcrepo.upgrade.utils.RdfConstants.ACCESS_CONTROL;
import static org.fcrepo.upgrade.utils.RdfConstants.ACL;
import static org.fcrepo.upgrade.utils.RdfConstants.ACL_AGENT;
import static org.fcrepo.upgrade.utils.RdfConstants.ACL_AGENT_CLASS;
import static org.fcrepo.upgrade.utils.RdfConstants.ACL_AGENT_GROUP;
import static org.fcrepo.upgrade.utils.RdfConstants.ACL_NS;
import static org.fcrepo.upgrade.utils.RdfConstants.AUTHORIZATION;
import static org.fcrepo.upgrade.utils.RdfConstants.EBUCORE_HAS_MIME_TYPE;
import static org.fcrepo.upgrade.utils.RdfConstants.FEDORA_CREATED_BY;
import static org.fcrepo.upgrade.utils.RdfConstants.FEDORA_CREATED_DATE;
import static org.fcrepo.upgrade.utils.RdfConstants.FEDORA_LAST_MODIFIED_BY;
import static org.fcrepo.upgrade.utils.RdfConstants.FEDORA_LAST_MODIFIED_DATE;
import static org.fcrepo.upgrade.utils.RdfConstants.FEDORA_VERSION;
import static org.fcrepo.upgrade.utils.RdfConstants.FOAF_AGENT;
import static org.fcrepo.upgrade.utils.RdfConstants.FOAF_GROUP;
import static org.fcrepo.upgrade.utils.RdfConstants.FOAF_MEMBER;
import static org.fcrepo.upgrade.utils.RdfConstants.LDP_BASIC_CONTAINER;
import static org.fcrepo.upgrade.utils.RdfConstants.LDP_CONTAINER;
import static org.fcrepo.upgrade.utils.RdfConstants.LDP_CONTAINER_TYPES;
import static org.fcrepo.upgrade.utils.RdfConstants.LDP_NON_RDF_SOURCE;
import static org.fcrepo.upgrade.utils.RdfConstants.LDP_RDF_SOURCE;
import static org.fcrepo.upgrade.utils.RdfConstants.MEMENTO;
import static org.fcrepo.upgrade.utils.RdfConstants.NON_RDF_SOURCE_DESCRIPTION;
import static org.fcrepo.upgrade.utils.RdfConstants.VCARD_GROUP;
import static org.fcrepo.upgrade.utils.RdfConstants.VCARD_HAS_MEMBER;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.vocabulary.RDF;
import org.fcrepo.client.FcrepoLink;

/**
 * @author dbernstein
 * @since 2019-08-05
 */
class F47ToF5UpgradeManager extends UpgradeManagerBase implements UpgradeManager {

    private static final org.slf4j.Logger LOGGER = getLogger(F47ToF5UpgradeManager.class);
    private static final Pattern MESSAGE_EXTERNAL_BODY_URL_PATTERN = Pattern
        .compile("^.*url=\"(.*)\".*$", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter ISO_DATE_TIME_FORMATTER = DateTimeFormatter.ISO_DATE_TIME
        .withZone(ZoneId.of("UTC"));
    private static final DateTimeFormatter RFC_1123_FORMATTER = DateTimeFormatter.RFC_1123_DATE_TIME
        .withZone(ZoneId.of("UTC"));
    private static final DateTimeFormatter MEMENTO_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String MEMENTO_DATETIME_HEADER = "Memento-Datetime";
    private static final String FCR_METADATA_PATH_SEGMENT = "fcr%3Ametadata";
    private static final String FCR_VERSIONS_PATH_SEGMENT = "fcr%3Aversions";
    private static final String FCR_ACL_PATH_SEGMENT = "fcr%3Aacl";
    private static final String FCR_PATH_SEGMENT_PREFIX = "fcr%3A";
    private static final String TYPE_RELATION = "type";
    private static final String HEADERS_SUFFIX = ".headers";
    public static final String APPLICATION_OCTET_STREAM_MIMETYPE = "application/octet-stream";
    /**
     * Constructor
     *
     * @param config the upgrade configuration
     */
    F47ToF5UpgradeManager(final Config config) {
        super(config);
    }

    @Override
    public void start() {
        //walk the directory structure
        processDirectory(this.config.getInputDir());
    }

    private void processDirectory(final File dir) {
        LOGGER.info("Processing directory: {}", dir.getAbsolutePath());
        try (final Stream<Path> walk = Files.walk(dir.toPath())) {
            walk.filter(Files::isRegularFile).forEach(this::processFile);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void processFile(final Path path) {

        //skip versions container
        if (path.endsWith(FCR_VERSIONS_PATH_SEGMENT + "." + config.getSrcRdfExt())) {
            LOGGER.debug("version containers are not required for import.  Skipping {}...", path);
            return;
        }

        final boolean isVersionedResource = path.toString().contains(FCR_VERSIONS_PATH_SEGMENT + File.separator);
        final Path inputPath = this.config.getInputDir().toPath();
        final Path relativePath = inputPath.relativize(path);
        final Path relativeNewLocation;
        final TemporalAccessor versionTimestamp;

        if (isVersionedResource) {
            versionTimestamp = resolveMementoTimestamp(path);
            relativeNewLocation = resolveNewVersionedResourceLocation(path, versionTimestamp);
        } else {
            versionTimestamp = null;
            relativeNewLocation = relativePath;
        }

        final var newLocation = this.config.getOutputDir().toPath().resolve(relativeNewLocation);

        try {
            Files.createDirectories(newLocation.getParent());
            LOGGER.debug("copy file {} to {}", path, newLocation);
            FileUtils.copyFile(path.toFile(), newLocation.toFile());
            if (newLocation.toString().endsWith(config.getSrcRdfExt())) {
                upgradeRdfAndCreateHeaders(versionTimestamp, newLocation);
                LOGGER.debug("File upgraded: {}", newLocation);
            } else {
                LOGGER.debug("File copied: {}", newLocation);
            }
            LOGGER.info("Resource upgraded: {}", path);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void upgradeRdfAndCreateHeaders(final TemporalAccessor versionTimestamp,
                                            final Path newLocation)
        throws IOException {
        //parse the file
        final Model model = ModelFactory.createDefaultModel();
        try (final InputStream is = new BufferedInputStream(new FileInputStream(newLocation.toFile()))) {
            RDFDataMgr.read(model, is, config.getSrcRdfLang());
        }

        final Map<String, List<String>> metadataHeaders = new HashMap<>();
        final Map<String, List<String>> binaryHeaders = new HashMap<>();
        metadataHeaders.computeIfAbsent(LINK_HEADER, x -> new ArrayList<>());
        binaryHeaders.computeIfAbsent(LINK_HEADER, x -> new ArrayList<>());
        metadataHeaders.put(CONTENT_TYPE_HEADER, Collections.singletonList("text/turtle"));
        final AtomicBoolean isExternal = new AtomicBoolean(false);
        final AtomicReference<Resource> containerSubject = new AtomicReference<>();
        final AtomicBoolean rewriteModel = new AtomicBoolean();

        final var statements = model.listStatements().toList();
        //gather the rdf types
        final var rdfTypes = statements.stream().filter(s -> s.getPredicate().equals(RDF.type))
                                       .map(s -> s.getObject().asResource()).collect(Collectors.toList());
        final var isBinary = rdfTypes.contains(LDP_NON_RDF_SOURCE);
        final var isContainer = rdfTypes.contains(LDP_CONTAINER);

        //skip if ACL or Authorization: these files are upgraded through a separate code path
        // see convertAcl() below.
        if (!this.config.isSkipAcls() && (rdfTypes.contains(ACL) || rdfTypes.contains(AUTHORIZATION))) {
            newLocation.toFile().delete();
            return;
        }

        rdfTypes.retainAll(LDP_CONTAINER_TYPES);
        final var isConcreteContainerDefined = !rdfTypes.isEmpty();

        addTypeLinkHeader(metadataHeaders, LDP_RDF_SOURCE.getURI());
        if (isBinary) {
            addTypeLinkHeader(binaryHeaders, LDP_NON_RDF_SOURCE.getURI());
            addTypeLinkHeader(metadataHeaders, NON_RDF_SOURCE_DESCRIPTION.getURI());
        }

        //loop through all the statements, modifying the model as necessary.
        statements.forEach(statement -> {
            var currentStatement = statement;

            //replace subject and internal objects with original resource uri if versioned
            if (versionTimestamp != null) {
                model.remove(currentStatement);
                final var object = currentStatement.getObject();
                currentStatement = model.createStatement(getOriginalResource(currentStatement.getSubject()),
                                                         currentStatement.getPredicate(),
                                                         object.isURIResource() ?
                                                         getOriginalResource(object.asResource()) : object);
                model.add(currentStatement);
                rewriteModel.set(true);
            }

            containerSubject.set(currentStatement.getSubject());

            final var object = currentStatement.getObject();

            //remove fedora:Version rdf type statement.
            if (currentStatement.getPredicate().equals(RDF.type) && object.asResource().equals(FEDORA_VERSION)) {
                model.remove(currentStatement);
                rewriteModel.set(true);
            } else if (statement.getPredicate().equals(EBUCORE_HAS_MIME_TYPE)) {
                //convert hasMimetype statement
                final String value = currentStatement.getString();
                LOGGER.debug("predicate value={}", value);
                var mimetype = value;
                if (value.startsWith("message/external-body")) {
                    mimetype = APPLICATION_OCTET_STREAM_MIMETYPE;
                    final var matcher = MESSAGE_EXTERNAL_BODY_URL_PATTERN.matcher(value);
                    String externalURI = null;
                    if (matcher.matches()) {
                        externalURI = matcher.group(1);
                    }

                    LOGGER.debug("externalURI={}", externalURI);
                    //remove old has mimetype statement
                    model.remove(currentStatement);
                    currentStatement = model.createStatement(currentStatement.getSubject(),
                                                             currentStatement.getPredicate(),
                                                             mimetype);
                    //add in the new one
                    model.add(currentStatement);
                    rewriteModel.set(true);

                    //if external add appropriate binary headers
                    if (externalURI != null) {
                        binaryHeaders.put(LOCATION_HEADER, Collections.singletonList(externalURI));
                        binaryHeaders.put(CONTENT_LOCATION_HEADER, Collections.singletonList(externalURI));
                        isExternal.set(true);
                    }
                }
                binaryHeaders.put(CONTENT_TYPE_HEADER, Collections.singletonList(mimetype));
            } else if (!this.config.isSkipAcls() && statement.getPredicate().equals(ACCESS_CONTROL)) {
                //remove the current statement across both past versions and latest version
                model.remove(currentStatement);
                rewriteModel.set(true);

                //on the latest version
                if(versionTimestamp == null) {
                    //convert the acl
                    convertAcl(newLocation, currentStatement.getSubject().getURI(),
                               currentStatement.getObject().asResource().getURI());
                }
            }
        });

        if (versionTimestamp != null) {
            addMementoDatetimeHeader(versionTimestamp, metadataHeaders);
            addMementoDatetimeHeader(versionTimestamp, binaryHeaders);
            addTypeLinkHeader(metadataHeaders, MEMENTO.getURI());
            addTypeLinkHeader(binaryHeaders, MEMENTO.getURI());
        }

        // While F5 assumes BasicContainer when no concrete container is present in the RDF on import,
        // the F5->F6 upgrade pathway requires its presence.  Thus I add it here for consistency.
        // As a note, when an F5 repository is exported the BasicContainer type triple will be present in
        // the exported RDF.
        if (isContainer && !isConcreteContainerDefined) {
            model.add(containerSubject.get(), RDF.type, LDP_BASIC_CONTAINER);
            rewriteModel.set(true);
        }

        // Fedora 4 WebAC reads a group's members from foaf:Group and foaf:member; Fedora 5+ only from vcard:Group
        // and vcard:hasMember. Add the vcard form so groups named in ACLs keep their members.
        for (final Resource group : model.listSubjectsWithProperty(RDF.type, FOAF_GROUP).toList()) {
            model.add(group, RDF.type, VCARD_GROUP);
            model.listObjectsOfProperty(group, FOAF_MEMBER).toList()
                 .forEach(member -> model.add(group, VCARD_HAS_MEMBER, member));
            rewriteModel.set(true);
        }

        // rewrite only if the model has changed.
        if (rewriteModel.get()) {
            try {
                RDFDataMgr.write(new BufferedOutputStream(new FileOutputStream(newLocation.toFile())), model, config.getSrcRdfLang());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        //write rdf headers file
        final var headersPrefix = newLocation.toAbsolutePath().toString();
        writeHeadersFile(metadataHeaders, new File(headersPrefix + HEADERS_SUFFIX));

        //write binary headers file
        if (isBinary) {
            String binaryHeadersPrefix;
            if (versionTimestamp != null) {
                //locate the related binary prefix
                binaryHeadersPrefix = locateBinaryHeadersPrefixForVersionedBinary(newLocation);
            } else  {
                //for unversioned binaries we want simply to translate from
                // path/to/binary/fcr%3Ametadata.ttl to path/to/binary
                binaryHeadersPrefix = newLocation.getParent().toAbsolutePath().toString();
            }

            binaryHeadersPrefix += isExternal.get() ? ".external" : ".binary";
            writeHeadersFile(binaryHeaders, new File(binaryHeadersPrefix + HEADERS_SUFFIX));
        }

        LOGGER.debug("isExternal={}", isExternal.get());
        LOGGER.debug("headersPrefix={}", headersPrefix);
        LOGGER.debug("isContainer={}", isContainer);
        LOGGER.debug("isConcreteContainerDefined={}", isConcreteContainerDefined);
        LOGGER.debug("containerSubject={}", containerSubject);
    }

    private void convertAcl(final Path convertedProtectedResourceLocation, String protectedResource, String aclUri) {
        //locate the exported acl rdf on disk based on aclURI
        final var relativeAclPath = create(aclUri).getPath();
        final var aclDirectory = Path.of(this.config.getInputDir().toPath().toString(), relativeAclPath);
        final var aclRdfFilePath = aclDirectory + "." + config.getSrcRdfExt();
        final var newAclResource = ResourceFactory.createResource(protectedResource + "/fcr:acl");
        final var aclModel = createModelFromFile(Path.of(aclRdfFilePath));
        final var aclTriples = new ArrayList<Statement>();
        final var allowablePredicates = Arrays.asList(RDF.type, FEDORA_CREATED_DATE, FEDORA_LAST_MODIFIED_DATE,
                                                      FEDORA_CREATED_BY, FEDORA_LAST_MODIFIED_BY);
        aclModel.listStatements().toList().stream().filter(x->allowablePredicates.contains(x.getPredicate()))
                .forEach(x -> {
            final var obj = x.getObject();
            final var predicate = x.getPredicate();
            if ( !(predicate.equals(RDF.type) && obj.isResource() && obj.equals(ACL))) {
                aclTriples.add(aclModel.createStatement(newAclResource, predicate, obj));
            }
        });

        final var newAclFilePath = Path
            .of(FilenameUtils.removeExtension(convertedProtectedResourceLocation.toString()),
                FCR_ACL_PATH_SEGMENT + "." + config.getSrcRdfExt());
        newAclFilePath.getParent().toFile().mkdirs();

        //determine the location of new acl
        final var authorizations = new LinkedHashMap<String, List<Statement>>();
        var authIndex = new AtomicInteger(0);
        try (final Stream<Path> list = Files.walk(aclDirectory)) {
            list.filter(Files::isRegularFile).filter(file -> isCurrentRdf(aclDirectory, file)).forEach(authFile -> {
                 final var model = createModelFromFile(authFile);
                final var authName = "auth" + authIndex.get();
                final var subject = createResource(newAclResource + "#" + authName);
                final var isAuthorization = new AtomicBoolean(false);
                final var authTriples = new ArrayList<Statement>();
                model.listStatements().toList().stream().filter(x-> {
                    //filter only rdf type Authorization and acl namespace  predicates
                    return (x.getPredicate().equals(RDF.type) && x.getObject().asResource().equals(AUTHORIZATION)) ||
                           x.getPredicate().toString().startsWith(ACL_NS);
                }).forEach(x->{
                    var object = x.getObject();
                    if (x.getPredicate().equals(RDF.type) && x.getObject().asResource().equals(AUTHORIZATION)) {
                        isAuthorization.set(true);
                    }
                    authTriples.add(model.createStatement(subject, agentPredicate(aclUri, x), object));
                });

                authIndex.incrementAndGet();
                if (isAuthorization.get()) {
                    authorizations.put(authName, authTriples);
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        //create new model
        final var newModel = ModelFactory.createDefaultModel();
        //add acl level triples
        aclTriples.forEach(x -> newModel.add(x));
        //add all authorizations to a single model
        for (String key : authorizations.keySet()) {
            authorizations.get(key).forEach(x -> {
                newModel.add(x);
            });
        }

        //save to new acl to file
        try (final OutputStream os = new BufferedOutputStream(new FileOutputStream(newAclFilePath.toFile()))) {
            RDFDataMgr.write(os, newModel, config.getSrcRdfLang());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Only an ACL's current RDF resources can be its authorizations. Binaries and other non-RDF files cannot be
     * parsed as RDF, and fcr:* entries, such as old versions under fcr%3Aversions, must not grant access again.
     *
     * @param aclDirectory the exported ACL's directory
     * @param file a file under that directory
     * @return true if the file is the current RDF of a resource in the ACL
     */
    private boolean isCurrentRdf(final Path aclDirectory, final Path file) {
        for (final Path segment : aclDirectory.relativize(file)) {
            if (segment.toString().startsWith(FCR_PATH_SEGMENT_PREFIX)) {
                return false;
            }
        }
        return file.getFileName().toString().endsWith("." + config.getSrcRdfExt());
    }

    /**
     * Fedora 4 WebAC merges acl:agent and acl:agentClass values: foaf:Agent in either means everyone, and an
     * acl:agentClass naming a resource in the repository is a group. Fedora 5+ follows the Solid WebAC spec instead,
     * where everyone is acl:agentClass foaf:Agent and a group is acl:agentGroup, so keep their meaning by moving them
     * to those predicates. An acl:agentClass outside the repository was ignored by Fedora 4 and is left as it is.
     *
     * @param aclUri the uri of the ACL the authorization belongs to
     * @param statement a statement of the authorization
     * @return the predicate the statement should have in Fedora 5+
     */
    private Property agentPredicate(final String aclUri, final Statement statement) {
        final var predicate = statement.getPredicate();
        final var object = statement.getObject();
        if (predicate.equals(ACL_AGENT) && object.equals(FOAF_AGENT)) {
            return ACL_AGENT_CLASS;
        }
        if (predicate.equals(ACL_AGENT_CLASS) && object.isURIResource() && !object.equals(FOAF_AGENT)
                && isExportedResource(aclUri, object.asResource().getURI())) {
            return ACL_AGENT_GROUP;
        }
        return predicate;
    }

    /**
     * @param aclUri the uri of an ACL in the export
     * @param uri a uri
     * @return true if the uri names a resource on the same server as the ACL that is in the export
     */
    private boolean isExportedResource(final String aclUri, final String uri) {
        final var acl = create(aclUri);
        final var resource = create(uri);
        if (!Objects.equals(acl.getScheme(), resource.getScheme())
                || !Objects.equals(acl.getAuthority(), resource.getAuthority()) || resource.getPath() == null) {
            return false;
        }
        final var exported = Files.exists(
                Path.of(this.config.getInputDir().toPath().toString(), resource.getPath() + "." + config.getSrcRdfExt()));
        if (!exported) {
            LOGGER.warn("acl:agentClass {} in {} is not in the export, so it is left as acl:agentClass", uri, aclUri);
        }
        return exported;
    }

    private String locateBinaryHeadersPrefixForVersionedBinary(final Path newLocation) {
        //the idea here is translate a versioned metadata resource
        //   from
        //   path/to/binary/fcr%3Ametdata/fcr%3Aversions/20201105171804.ttl
        //   to
        //   path/to/binary/fcr%3Aversions/20201105171804
        final var fileName = newLocation.getFileName().toString();
        final var filenameWithoutExtension = FilenameUtils.getBaseName(fileName);
        return newLocation.getParent().getParent().getParent().toAbsolutePath() + File.separator +
                              FCR_VERSIONS_PATH_SEGMENT + File.separator + filenameWithoutExtension;
    }

    private Resource getOriginalResource(Resource resource) {
        return createResource(resource.getURI()
                                      .replaceAll("/fcr:versions/[a-zA-Z0-9.]*", ""));
    }

    private void addTypeLinkHeader(Map<String, List<String>> headers, String typeUri) {
        final FcrepoLink link = FcrepoLink.fromUri(typeUri).rel(TYPE_RELATION).build();
        headers.get(LINK_HEADER).add(link.toString());
    }

    private void addMementoDatetimeHeader(TemporalAccessor versionTimestamp, Map<String, List<String>> headers) {
        headers.put(MEMENTO_DATETIME_HEADER, Collections.singletonList(RFC_1123_FORMATTER.format(versionTimestamp)));
    }

    private Path resolveVersionsContainer(final Path path) {
        var currentPath = path;
        while (currentPath != path.getRoot()) {
            final var parent = currentPath.getParent();
            if (parent.endsWith(FCR_VERSIONS_PATH_SEGMENT)) {
                return Path.of(parent.toString() + "." + config.getSrcRdfExt());
            }

            currentPath = parent;
        }
        return null;
    }

    private TemporalAccessor resolveMementoTimestamp(final Path path) {
        var metadataPath = path;
        if (!path.toString().endsWith(config.getSrcRdfExt())) {
            final var metadataPathStr = metadataPath.toString();
            final var newMetadataPathStr = FilenameUtils.removeExtension(metadataPathStr) + File.separator +
                                           FCR_METADATA_PATH_SEGMENT + "." + config.getSrcRdfExt();
            metadataPath = Path.of(newMetadataPathStr);
        }

        //resolve the subject associated with the resource
        final Model model = createModelFromFile(metadataPath);
        final var subject = model.listSubjects().nextResource();

        //resolve the versions file
        final var versionsContainer = resolveVersionsContainer(path);
        //read the versions container in order to resolve the version timestamp
        final Model versionsContainerModel = createModelFromFile(versionsContainer);
        final var iso8601Timestamp = versionsContainerModel.listObjectsOfProperty(subject, FEDORA_CREATED_DATE)
                                                           .next().asLiteral().getString();
        //create memento id based on RFC 8601 timestamp
        return ISO_DATE_TIME_FORMATTER.parse(iso8601Timestamp);
    }

    private Model createModelFromFile(final Path path) {
        final Model model = ModelFactory.createDefaultModel();
        try (final InputStream is = new BufferedInputStream(new FileInputStream(path.toFile()))) {
            RDFDataMgr.read(model, is, config.getSrcRdfLang());
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        return model;
    }

    private Path resolveNewVersionedResourceLocation(final Path path, final TemporalAccessor mementoTimestamp) {
        final var mementoId = MEMENTO_FORMATTER.format(mementoTimestamp);
        //create a new location compatible with an F5 export.
        final var isDescription = path.endsWith(FCR_METADATA_PATH_SEGMENT + "." + config.getSrcRdfExt());
        final var inputPath = this.config.getInputDir().toPath();
        final var relativePath = inputPath.relativize(path);
        final var relativePathStr = relativePath.toString();
        var newLocation = relativePath;
        final var extension = relativePathStr.substring(relativePathStr.lastIndexOf("."));
        if (!isDescription) {
            newLocation = Path.of(relativePath.getParent().toString(), mementoId + extension);
        } else {
            newLocation = Path.of(relativePath.getParent().getParent().getParent().toString(),
                                  FCR_METADATA_PATH_SEGMENT, FCR_VERSIONS_PATH_SEGMENT, mementoId + extension);
        }
        return newLocation;
    }

    private void writeHeadersFile(final Map<String, List<String>> headers, final File file) throws IOException {
        final String json = new ObjectMapper().writeValueAsString(headers);
        file.getParentFile().mkdirs();
        try (final FileWriter writer = new FileWriter(file)) {
            writer.write(json);
        }
    }
}
