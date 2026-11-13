/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.upgrade.utils;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFLanguages;

import java.io.File;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static org.fcrepo.upgrade.utils.Config.VALID_DIGEST_ALGORITHMS;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * @author dbernstein
 * @since 2019-08-05
 */
public class UpgradeUtilDriver {

    private static final org.slf4j.Logger logger = getLogger(UpgradeUtilDriver.class);

    public static final Map<FedoraVersion, Set<FedoraVersion>> VALID_MIGRATION_PATHS = new HashMap<>();

    static {
        VALID_MIGRATION_PATHS.put(FedoraVersion.V_4_7_5, Set.of(FedoraVersion.V_5));
        VALID_MIGRATION_PATHS.put(FedoraVersion.V_5, Set.of(FedoraVersion.V_6));
    }

    UpgradeUtilDriver() {
        // Package-private so tests can create an instance; not part of the public API
    }

    /**
     * The main entry point
     *
     * @param args from the command line
     */
    public static void main(final String[] args) {
        final UpgradeUtilDriver driver = new UpgradeUtilDriver();

        try {
            driver.run(args);

        } catch (final ExitException e) {
            System.exit(e.getStatus());
        } catch (final Exception e) {
            logger.error("Error performing upgrade: {}", e.getMessage());
            logger.debug("Stacktrace: ", e);
        }
    }

    void run(final String[] args) {
        final var configOptions = options();
        final var config = parseOptions(configOptions, args);

        try {
            //start the upgrade
            final var manager = UpgradeManagerFactory.create(config);
            manager.start();
        } catch (final Exception e) {
            logger.error("Upgrade failed.", e);
            printHelpAndExit(format("Upgrade failed: %s  -> See log for details.", e.getMessage()), configOptions);
        }
    }

    Config parseOptions(final Options configOptions, final String[] args) {
        // first see if they've specified a config file
        final CommandLineParser parser = new DefaultParser();
        final CommandLine cmd;
        try {
            cmd = parser.parse(configOptions, args);
        } catch (final ParseException e) {
            throw printHelpAndExit(e.getMessage(), configOptions);
        }

        logger.info("Command line parameters: ");
        for(final var option : cmd.getOptions()) {
            logger.info("    {}: {}", option.getLongOpt(), option.getValues());
        }


        final File inputDir = new File(cmd.getOptionValue("i"));

        if (!inputDir.exists()) {
            printHelpAndExit(format("input directory %s does not exist.", inputDir.getAbsolutePath()), configOptions);
        }

        final var commandArgs = cmd.getArgs();

        if(commandArgs.length > 0) {
            final var errorMessage =
                new StringBuilder(format("The following argument(s) were not expected: %s .", commandArgs));
            errorMessage.append("  Please ensure all arguments are associated with a valid command-line flag.");
            printHelpAndExit(errorMessage.toString(),options());
        }

        final String outputDirStr = cmd.getOptionValue("o");

        final File outputDir;

        if (outputDirStr == null) {
            final var date = Calendar.getInstance().getTime();
            final var dateFormat = new SimpleDateFormat("yyyyMMdd-HHmmss");
            final var strCurrentTime = dateFormat.format(date);
            outputDir = new File("output_" + strCurrentTime);
            outputDir.mkdirs();
        } else {
            outputDir = new File(outputDirStr);
            outputDir.mkdirs();
            if (!outputDir.exists()) {
                printHelpAndExit(format("output directory %s could not be created.", outputDir.getAbsolutePath()),
                        configOptions);
            }
        }

        logger.info("input directory: {}", inputDir.getAbsolutePath());
        logger.info("output directory: {}", outputDir.getAbsolutePath());

        final var config = new Config();
        config.setSourceVersion(FedoraVersion.fromString(cmd.getOptionValue("s")));
        config.setTargetVersion(FedoraVersion.fromString(cmd.getOptionValue("t")));
        config.setInputDir(inputDir);
        config.setOutputDir(outputDir);

        if (cmd.hasOption("source-rdf")) {
            final Lang lang = RDFLanguages.contentTypeToLang(cmd.getOptionValue("source-rdf"));
            if (lang == null) {
                printHelpAndExit(format("invalid RDF content-type (%s) provided", cmd.getOptionValue("source-rdf")),
                    configOptions);
            }
            config.setSrcRdfLang(lang);
        }

        if (cmd.hasOption("threads")) {
            config.setThreads(Integer.valueOf(cmd.getOptionValue("threads")));
        }

        if (config.getTargetVersion().equals(FedoraVersion.V_6)) {
            //base URI only used when migrating to F6
            if (cmd.getOptionValue("base-uri") == null) {
                printHelpAndExit("base-uri must be specified when migrating to Fedora 6", configOptions);
            }
            config.setBaseUri(cmd.getOptionValue("base-uri"));
            config.setArchivalGroupRdfTypes(cmd.getOptionValue("archival-group-rdf-types"));
        }
        if (cmd.hasOption("digest-algorithm")) {
            final var algo = cmd.getOptionValue("digest-algorithm");
            if (!VALID_DIGEST_ALGORITHMS.contains(algo)) {
                printHelpAndExit(format("invalid digest algorithm (%s) provided, must be one of %s",
                        algo, String.join(", ", VALID_DIGEST_ALGORITHMS)), configOptions);
            }
            config.setDigestAlgorithm(cmd.getOptionValue("digest-algorithm"));
        }
        config.setFedoraUser(cmd.getOptionValue("migration-user"));
        config.setFedoraUserAddress(cmd.getOptionValue("migration-user-address"));

        config.setSkipAcls(cmd.hasOption("skip-acls"));
        config.setLinkBinaries(cmd.hasOption("link-binaries"));

        config.setWriteToS3(cmd.hasOption("write-to-s3"));
        config.setS3Region(cmd.getOptionValue("s3-region"));
        config.setS3Endpoint(cmd.getOptionValue("s3-endpoint"));
        config.setS3PathStyleAccess(cmd.hasOption("s3-path-style-access"));
        config.setS3AccessKey(cmd.getOptionValue("s3-access-key"));
        config.setS3SecretKey(cmd.getOptionValue("s3-secret-key"));
        config.setS3Bucket(cmd.getOptionValue("s3-bucket"));
        config.setS3Prefix(cmd.getOptionValue("s3-prefix"));

        if (cmd.getOptionValue('R') != null) {
            config.setResourceInfoFile(Paths.get(cmd.getOptionValue('R')));
        }

        if (config.isWriteToS3() && StringUtils.isBlank(config.getS3Bucket())) {
            printHelpAndExit("s3-bucket must be specified when writing to S3", configOptions);
        }

        return config;
    }

    Options options() {
        // Help option
        final Options configOptions = new org.apache.commons.cli.Options();

        configOptions.addOption(Option.builder("h")
                .longOpt("help")
                .hasArg(false)
                .desc("Print these options")
                .required(false)
                .build());

        configOptions.addOption(Option.builder("i")
                .longOpt("input-dir")
                .hasArg(true)
                .desc("The path to the directory containing a Fedora 4.7.x or Fedora 5.x export")
                .required(true)
                .build());

        configOptions.addOption(Option.builder("o")
                .longOpt("output-dir")
                .hasArg(true)
                .desc("The path to the directory where upgraded resources will be written. " +
                        "Default value: output_<yyyyMMdd-HHmmss>. " +
                        "For example: output_20200101-075901")
                .required(false)
                .build());

        configOptions.addOption(Option.builder("s")
                .longOpt("source-version")
                .hasArg(true)
                .desc(format(
                        "The version of Fedora that was the source of the export. Valid values: %s",
                        join(VALID_MIGRATION_PATHS.keySet())))
                .required(true)
                .build());

        configOptions.addOption(Option.builder("t")
                .longOpt("target-version")
                .hasArg(true)
                .desc(format("The version of Fedora to which you are upgrading. Valid values: %s",
                        join(VALID_MIGRATION_PATHS.values().stream()
                                .flatMap(x -> x.stream()).collect(Collectors.toSet()))))
                .required(true)
                .build());

        // F6 migration opts
        configOptions.addOption(Option.builder("r")
                .longOpt("source-rdf")
                .hasArg(true)
                .desc("The RDF language used in the Fedora export, expressed as a content type." +
                        System.lineSeparator() + "Acceptable values are: application/ld+json, application/n-triples," +
                        " application/rdf+xml, text/n3, text/rdf+n3, text/turtle." + System.lineSeparator() +
                        "Default: " + Config.DEFAULT_SRC_RDF_LANG.getContentType().getContentTypeStr())
                .required(false)
                .build());

        configOptions.addOption(Option.builder("u")
                .longOpt("base-uri")
                .hasArg(true)
                .desc("Base URI of the source Fedora being upgraded. For example, http://localhost:8080/rest")
                .required(false)
                .build());

        configOptions.addOption(Option.builder("p")
                .longOpt("threads")
                .hasArg(true)
                .desc("The number of threads to use. Default: the number of available cores")
                .required(false)
                .build());

        configOptions.addOption(Option.builder("d")
                .longOpt("digest-algorithm")
                .hasArg(true)
                .desc("The digest algorithm to use in OCFL. Default: " + Config.DEFAULT_DIGEST_ALGORITHM)
                .required(false)
                .build());

        configOptions.addOption(Option.builder()
                .longOpt("migration-user")
                .hasArg(true)
                .desc("The user to attribute OCFL versions to. Default: " + Config.DEFAULT_USER)
                .required(false)
                .build());

        configOptions.addOption(Option.builder()
                .longOpt("migration-user-address")
                .hasArg(true)
                .desc("The address of the user OCFL versions are attributed to. Default: "
                        + Config.DEFAULT_USER_ADDRESS)
                .required(false)
                .build());
        configOptions.addOption(Option.builder()
                .longOpt("link-binaries")
                .hasArg(false)
                .desc("When migrating from 4.7.x to 5+, link binary content into the output instead of copying "
                        + "it: a symbolic link stays a symbolic link to the same target, and a regular file is hard "
                        + "linked (copied if the output is on another file system). The content is only read by "
                        + "the migration to 6+.")
                .required(false)
                .build());
        configOptions.addOption(Option.builder()
                .longOpt("skip-acls")
                .hasArg(false)
                .desc("Skip creating fcr:acl resources and migrate webac:Acl and acl:Authorization resources normally")
                .required(false)
                .build());

        configOptions.addOption(Option.builder("ag")
                .longOpt("archival-group-rdf-types")
                .hasArg(true)
                .desc("Comma separated list of RDF type URIs. If a resource has any of the types" +
                        " in the provided list, it will be created as an archival group in OCFL.")
                .required(false)
                .build());
        // archivalGroupRdfTypes

        // S3 options

        configOptions.addOption(Option.builder()
                .longOpt("write-to-s3")
                .hasArg(false)
                .desc("Enables writing migrated Fedora 6 data to S3 rather than the local filesystem")
                .required(false)
                .build());

        configOptions.addOption(Option.builder()
                .longOpt("s3-bucket")
                .hasArg(true)
                .desc("The S3 bucket to write to, required when writing to S3")
                .required(false)
                .build());

        configOptions.addOption(Option.builder()
                .longOpt("s3-prefix")
                .hasArg(true)
                .desc("The S3 prefix to locate the OCFL repo in, optionally use when writing to S3")
                .required(false)
                .build());

        configOptions.addOption(Option.builder()
                .longOpt("s3-region")
                .hasArg(true)
                .desc("The AWS region, optionally use when writing to S3")
                .required(false)
                .build());

        configOptions.addOption(Option.builder()
                .longOpt("s3-endpoint")
                .hasArg(true)
                .desc("The AWS endpoint URL, optionally use when writing to S3")
                .required(false)
                .build());

        configOptions.addOption(Option.builder()
                .longOpt("s3-path-style-access")
                .hasArg(false)
                .desc("The S3 access style, optionally use when writing to S3")
                .required(false)
                .build());

        configOptions.addOption(Option.builder()
                .longOpt("s3-access-key")
                .hasArg(true)
                .desc("The AWS access key, optionally use when writing to S3")
                .required(false)
                .build());

        configOptions.addOption(Option.builder()
                .longOpt("s3-secret-key")
                .hasArg(true)
                .desc("The AWS secret key, optionally use when writing to S3")
                .required(false)
                .build());

        configOptions.addOption(Option.builder("R")
                .longOpt("resource-info-file")
                .hasArg(true)
                .desc("The path of the file that contains a list of resources to be processed")
                .required(false)
                .build());

        return configOptions;
    }

    private Object join(final Collection<FedoraVersion> versions) {
        return versions.stream().map(FedoraVersion::getStringValue).sorted().collect(Collectors.joining(", "));
    }

    /**
     * Prints the error message and usage, then throws an {@link ExitException} which {@link #main} converts
     * into a non-zero exit status.
     */
    private static ExitException printHelpAndExit(final String errorMessage, final Options options) {
        final HelpFormatter formatter = new HelpFormatter();
        System.err.println(errorMessage);
        formatter.printHelp("java -jar fcrepo-upgrade-util-<version>.jar", options);
        throw new ExitException(1);
    }

    /**
     * Signals that the process should exit with the given status.
     */
    static class ExitException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final int status;

        ExitException(final int status) {
            super("exit status " + status);
            this.status = status;
        }

        int getStatus() {
            return status;
        }
    }

}
