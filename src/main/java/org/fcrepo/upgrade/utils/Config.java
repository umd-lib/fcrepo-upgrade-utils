/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.upgrade.utils;

import com.google.common.base.Preconditions;
import org.apache.jena.riot.Lang;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * A class representing the configuration of the upgrade run.
 *
 * @author dbernstein
 */
public class Config {

    public static final String DEFAULT_USER = "fedoraAdmin";
    public static final String DEFAULT_USER_ADDRESS = "info:fedora/fedoraAdmin";
    public static final String DEFAULT_DIGEST_ALGORITHM = "sha512";
    public static final List<String> VALID_DIGEST_ALGORITHMS = List.of(DEFAULT_DIGEST_ALGORITHM, "sha256");
    public static final Lang DEFAULT_SRC_RDF_LANG = Lang.TTL;
    public static final int DEFAULT_THREADS = Runtime.getRuntime().availableProcessors();

    private FedoraVersion sourceVersion;
    private FedoraVersion targetVersion;
    private File inputDir;
    private File outputDir;

    // F4/5 -> F6 Options
    private Lang srcRdfLang = DEFAULT_SRC_RDF_LANG;
    private String baseUri;
    private Integer threads = DEFAULT_THREADS;
    private String digestAlgorithm;
    private String fedoraUser = DEFAULT_USER;
    private String fedoraUserAddress = DEFAULT_USER_ADDRESS;
    private boolean forceWindowsMode = false;
    private Path resourceInfoFile;
    private boolean skipAcls = false;
    private boolean linkBinaries = false;
    private boolean requireEmptyOutput = false;
    private String archivalGroupRdfTypes;

    // S3 Options
    private boolean writeToS3;
    private String s3Region;
    private String s3Endpoint;
    private boolean s3PathStyleAccess;
    public static final int DEFAULT_S3_MAX_CONNECTIONS = 200;
    public static final int DEFAULT_S3_CONNECTION_ACQUISITION_TIMEOUT_SECONDS = 600;
    private int s3MaxConnections = DEFAULT_S3_MAX_CONNECTIONS;
    private int s3ConnectionAcquisitionTimeoutSeconds = DEFAULT_S3_CONNECTION_ACQUISITION_TIMEOUT_SECONDS;
    private String s3AccessKey;
    private String s3SecretKey;
    private String s3Bucket;
    private String s3Prefix;

    /**
     * Set the version of the source to be transformed.
     *
     * @param sourceVersion The source version
     */
    public void setSourceVersion(final FedoraVersion sourceVersion) {
        this.sourceVersion = sourceVersion;
    }

    /**
     * The version of the source to be transformed.
     *
     * @return The source version
     */
    public FedoraVersion getSourceVersion() {
        return sourceVersion;
    }

    /**
     * The version of Fedora into which you are transforming the source.
     *
     * @return The target version
     */
    public FedoraVersion getTargetVersion() {
        return targetVersion;
    }

    /**
     * Set the version of Fedora into which you are transforming the source.
     *
     * @param targetVersion The target version
     */
    public void setTargetVersion(final FedoraVersion targetVersion) {
        this.targetVersion = targetVersion;
    }

    /**
     * The output directory
     * @return a directory
     */
    public File getOutputDir() {
        return outputDir;
    }

    /**
     * Set the output directory
     * @param outputDir a directory
     */
    public void setOutputDir(final File outputDir) {
        this.outputDir = outputDir;
    }

    /**
     * The input directory
     * @return a directory
     */
    public File getInputDir() {
        return inputDir;
    }

    /**
     * Set the input directory
     * @param inputDir a directory
     */
    public void setInputDir(final File inputDir) {
        this.inputDir = inputDir;
    }

    /**
     * @return the base uri of the existing Fedora, eg http://localhost:8080/rest
     */
    public String getBaseUri() {
        return baseUri;
    }

    /**
     * Sets the baseUri
     * @param baseUri the base uri of the existing Fedora, eg http://localhost:8080/rest
     */
    public void setBaseUri(final String baseUri) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri must be specified");
    }

    /**
     * @return the number of threads to use
     */
    public Integer getThreads() {
        return threads;
    }

    /**
     * Sets the number of threads to use
     * @param threads number of threads
     */
    public void setThreads(final Integer threads) {
        if (threads == null) {
            this.threads = DEFAULT_THREADS;
        } else {
            Preconditions.checkArgument(threads > 0, "threads must be > 0");
            this.threads = threads;
        }
    }

    /**
     * @return the digest algorithm to use in OCFL, sha512 or sha256
     */
    public String getDigestAlgorithm() {
        if (this.digestAlgorithm == null) {
            return DEFAULT_DIGEST_ALGORITHM;
        }
        return digestAlgorithm;
    }

    /**
     * Sets the digest algorithm
     * @param digestAlgorithm sha512 or sha256
     */
    public void setDigestAlgorithm(final String digestAlgorithm) {
        this.digestAlgorithm = digestAlgorithm;
    }

    /**
     * @return the user to attribute OCFL versions to
     */
    public String getFedoraUser() {
        return fedoraUser;
    }

    /**
     * Sets the user to attribute OCFL versions to
     * @param fedoraUser user name
     */
    public void setFedoraUser(final String fedoraUser) {
        if (fedoraUser == null) {
            this.fedoraUser = DEFAULT_USER;
        } else {
            this.fedoraUser = fedoraUser;
        }
    }

    /**
     * @return the address of the user OCFL versions are attributed to
     */
    public String getFedoraUserAddress() {
        return fedoraUserAddress;
    }

    /**
     * Sets the address of the user OCFL versions are attributed to
     * @param fedoraUserAddress the address of the user OCFL versions are attributed to
     */
    public void setFedoraUserAddress(final String fedoraUserAddress) {
        if (fedoraUserAddress == null) {
            this.fedoraUserAddress = DEFAULT_USER_ADDRESS;
        } else {
            this.fedoraUserAddress = fedoraUserAddress;
        }
    }

    /**
     * @return the rdf lang of the export
     */
    public Lang getSrcRdfLang() {
        return srcRdfLang;
    }

    /**
     * @return the extension of the rdf lang of the export
     */
    public String getSrcRdfExt() {
        return srcRdfLang.getFileExtensions().get(0);
    }

    /**
     * Sets the rdf lang of the export
     * @param srcRdfLang the rdf lang of the export
     */
    public void setSrcRdfLang(final Lang srcRdfLang) {
        if (srcRdfLang == null) {
            this.srcRdfLang = DEFAULT_SRC_RDF_LANG;
        } else {
            this.srcRdfLang = srcRdfLang;
        }
    }

    /**
     * This is just used for testing
     *
     * @return indicates whether or not OCFL should be forced into Windows mode
     */
    public boolean isForceWindowsMode() {
        return forceWindowsMode;
    }

    /**
     * This is just used for testing
     *
     * @param forceWindowsMode true if OCFL should be forced into Windows mode
     */
    public void setForceWindowsMode(final boolean forceWindowsMode) {
        this.forceWindowsMode = forceWindowsMode;
    }

    /**
     * @return true if should skip special handling of acl resources
     */
    public boolean isSkipAcls() {
        return skipAcls;
    }

    /**
     * @param skipAcls true if should skip special handling of acl resources
     */
    public void setSkipAcls(final boolean skipAcls) {
        this.skipAcls = skipAcls;
    }

    /**
     * @return true if binary content should be linked into the output rather than copied
     */
    public boolean isLinkBinaries() {
        return linkBinaries;
    }

    /**
     * @param linkBinaries true if binary content should be linked into the output rather than copied: a symbolic
     *                     link is reproduced as a symbolic link to the same target, and a regular file is hard
     *                     linked (or copied, where the output is on another file system)
     */
    public void setLinkBinaries(final boolean linkBinaries) {
        this.linkBinaries = linkBinaries;
    }

    /**
     * @return true if a migration to 6+ should fail when the OCFL storage root already holds objects
     */
    public boolean isRequireEmptyOutput() {
        return requireEmptyOutput;
    }

    /**
     * @param requireEmptyOutput true if a migration to 6+ should fail, before migrating anything, when the OCFL
     *                           storage root already holds objects. A resource that is already there is otherwise
     *                           skipped, whatever its content.
     */
    public void setRequireEmptyOutput(final boolean requireEmptyOutput) {
        this.requireEmptyOutput = requireEmptyOutput;
    }

    /**
     * @return RDF types that identify Archival Groups, comma separated
     */
    public String getArchivalGroupRdfTypes() {
        return archivalGroupRdfTypes;
    }

    /**
     * @param archivalGroupRdfTypes RDF types that identify Archival Groups, comma separated
     */
    public void setArchivalGroupRdfTypes(String archivalGroupRdfTypes) {
        this.archivalGroupRdfTypes = archivalGroupRdfTypes;
    }

    /**
     * @return true if should write migrated resources to S3
     */
    public boolean isWriteToS3() {
        return writeToS3;
    }

    /**
     * @param writeToS3 true if should write migrated resources to S3
     */
    public void setWriteToS3(final boolean writeToS3) {
        this.writeToS3 = writeToS3;
    }

    /**
     * @return the aws region or null
     */
    public String getS3Region() {
        return s3Region;
    }

    /**
     * @param s3Region aws region
     */
    public void setS3Region(final String s3Region) {
        this.s3Region = s3Region;
    }

    /**
     * @return s3 endpoint url or null
     */
    public String getS3Endpoint() {
        return s3Endpoint;
    }

    /**
     * @param s3Endpoint s3 endpoint
     */
    public void setS3Endpoint(final String s3Endpoint) {
        this.s3Endpoint = s3Endpoint;
    }

    /**
     * @return true if path style access should be used
     */
    public boolean isS3PathStyleAccess() {
        return s3PathStyleAccess;
    }

    /**
     * @param s3PathStyleAccess true if path style access should be used
     */
    public void setS3PathStyleAccess(final boolean s3PathStyleAccess) {
        this.s3PathStyleAccess = s3PathStyleAccess;
    }

    /**
     * @return the most connections to S3 open at once
     */
    public int getS3MaxConnections() {
        return s3MaxConnections;
    }

    /**
     * @param s3MaxConnections the most connections to S3 open at once
     */
    public void setS3MaxConnections(final int s3MaxConnections) {
        this.s3MaxConnections = s3MaxConnections;
    }

    /**
     * @return how long, in seconds, a request to S3 waits for a free connection before it fails
     */
    public int getS3ConnectionAcquisitionTimeoutSeconds() {
        return s3ConnectionAcquisitionTimeoutSeconds;
    }

    /**
     * @param seconds how long a request to S3 waits for a free connection before it fails
     */
    public void setS3ConnectionAcquisitionTimeoutSeconds(final int seconds) {
        this.s3ConnectionAcquisitionTimeoutSeconds = seconds;
    }

    /**
     * @return aws access key
     */
    public String getS3AccessKey() {
        return s3AccessKey;
    }

    /**
     * @param s3AccessKey aws access key
     */
    public void setS3AccessKey(final String s3AccessKey) {
        this.s3AccessKey = s3AccessKey;
    }

    /**
     * @return aws secret key
     */
    public String getS3SecretKey() {
        return s3SecretKey;
    }

    /**
     * @param s3SecretKey aws secret key
     */
    public void setS3SecretKey(final String s3SecretKey) {
        this.s3SecretKey = s3SecretKey;
    }

    /**
     * @return s3 bucket or null
     */
    public String getS3Bucket() {
        return s3Bucket;
    }

    /**
     * @param s3Bucket s3 bucket
     */
    public void setS3Bucket(final String s3Bucket) {
        this.s3Bucket = s3Bucket;
    }

    /**
     * @return s3 prefix repo is located in or null
     */
    public String getS3Prefix() {
        return s3Prefix;
    }

    /**
     * @param s3Prefix s3 prefix repo is located in
     */
    public void setS3Prefix(final String s3Prefix) {
        this.s3Prefix = s3Prefix;
    }

    /**
     * @return path to the resource info file or null
     */
    public Path getResourceInfoFile() {
        return resourceInfoFile;
    }

    /**
     * @param resourceInfoFile path to the resource info file
     */
    public void setResourceInfoFile(Path resourceInfoFile) {
        this.resourceInfoFile = resourceInfoFile;
    }

    @Override
    public String toString() {
        return "Config{" +
                "sourceVersion=" + sourceVersion +
                ", targetVersion=" + targetVersion +
                ", inputDir=" + inputDir +
                ", outputDir=" + outputDir +
                ", srcRdfLang=" + srcRdfLang +
                ", baseUri='" + baseUri + '\'' +
                ", threads=" + threads +
                ", digestAlgorithm='" + digestAlgorithm + '\'' +
                ", fedoraUser='" + fedoraUser + '\'' +
                ", fedoraUserAddress='" + fedoraUserAddress + '\'' +
                ", forceWindowsMode=" + forceWindowsMode +
                ", skipAcls=" + skipAcls +
                ", linkBinaries=" + linkBinaries +
                ", requireEmptyOutput=" + requireEmptyOutput +
                ", writeToS3=" + writeToS3 +
                ", s3Region='" + s3Region + '\'' +
                ", s3Endpoint='" + s3Endpoint + '\'' +
                ", s3PathStyleAccess=" + s3PathStyleAccess +
                ", s3MaxConnections=" + s3MaxConnections +
                ", s3ConnectionAcquisitionTimeoutSeconds=" + s3ConnectionAcquisitionTimeoutSeconds +
                ", s3Bucket=" + s3Bucket +
                ", s3Prefix=" + s3Prefix +
                ", resourceInfoFile=" + resourceInfoFile +
                '}';
    }

}
