/*
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree.
 */
package org.fcrepo.upgrade.utils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.annotations.VisibleForTesting;
import io.ocfl.api.MutableOcflRepository;
import io.ocfl.api.OcflConfig;
import io.ocfl.api.model.DigestAlgorithm;
import io.ocfl.api.model.OcflVersion;
import io.ocfl.aws.OcflS3Client;
import io.ocfl.core.OcflRepositoryBuilder;
import io.ocfl.core.extension.storage.layout.config.HashedNTupleLayoutConfig;
import io.ocfl.core.path.mapper.LogicalPathMappers;
import io.ocfl.core.storage.OcflStorage;
import io.ocfl.core.storage.OcflStorageBuilder;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.fcrepo.storage.ocfl.CommitType;
import org.fcrepo.storage.ocfl.DefaultOcflObjectSessionFactory;
import org.fcrepo.storage.ocfl.OcflObjectSessionFactory;
import org.fcrepo.storage.ocfl.cache.CaffeineCache;
import org.fcrepo.upgrade.utils.f6.MigrationTaskManager;
import org.fcrepo.upgrade.utils.f6.ResourceInfoLogger;
import org.fcrepo.upgrade.utils.f6.ResourceMigrator;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.transfer.s3.S3TransferManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS;
import static java.lang.String.format;

/**
 * A factory class for creating Upgrade managers based on source and target versions.
 * @author  dbernstein
 */
public class UpgradeManagerFactory {

    public static UpgradeManager create(final Config config) {
        if (config.getSourceVersion().equals(FedoraVersion.V_4_7_5) &&
            config.getTargetVersion().equals(FedoraVersion.V_5)) {
            return new F47ToF5UpgradeManager(config);
        } else if (config.getSourceVersion().equals(FedoraVersion.V_5) &&
                   config.getTargetVersion().equals(FedoraVersion.V_6)) {
            final var infoLogger = new ResourceInfoLogger();
            return new F5ToF6UpgradeManager(config, createF6MigrationTaskManager(config, infoLogger), infoLogger);
        } else {
            throw new IllegalArgumentException(format("The migration path from %s to %s is not supported.",
                                                      config.getSourceVersion().getStringValue(),
                                                      config.getTargetVersion().getStringValue()));
        }
    }

    private static MigrationTaskManager createF6MigrationTaskManager(final Config config,
                                                                     final ResourceInfoLogger infoLogger) {
        final var ocflRepo = createOcflRepo(config);
        if (config.isRequireEmptyOutput()) {
            try (final var objectIds = ocflRepo.listObjectIds()) {
                final var existing = objectIds.findFirst();
                if (existing.isPresent()) {
                    ocflRepo.close();
                    throw new IllegalStateException("The OCFL storage root is not empty: it holds " + existing.get()
                            + ". Resources that are already there would be skipped, not migrated again.");
                }
            }
        }
        final var migrator = new ResourceMigrator(config, createOcflObjectSessionFactory(config, ocflRepo), ocflRepo);
        return new MigrationTaskManager(config.getThreads(), migrator, infoLogger);
    }

    @VisibleForTesting
    public static OcflObjectSessionFactory createOcflObjectSessionFactory(final Config config) {
        return createOcflObjectSessionFactory(config, createOcflRepo(config));
    }

    @VisibleForTesting
    public static MutableOcflRepository createOcflRepo(final Config config) {
        try {
            final var output = config.getOutputDir().toPath().resolve("data");
            final var ocflRoot = Files.createDirectories(output.resolve("ocfl-root"));
            final var work = Files.createDirectories(output.resolve("ocfl-temp"));
            return createOcflRepo(config, ocflRoot, work);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static OcflObjectSessionFactory createOcflObjectSessionFactory(final Config config,
                                                                           final MutableOcflRepository ocflRepo) {
        try {
            final var output = config.getOutputDir().toPath().resolve("data");
            final var staging = Files.createDirectories(output.resolve("staging"));

            final var objectMapper = new ObjectMapper()
                    .configure(WRITE_DATES_AS_TIMESTAMPS, false)
                    .registerModule(new JavaTimeModule())
                    .setSerializationInclusion(JsonInclude.Include.NON_NULL);

            final var headersCache = Caffeine.newBuilder()
                    .maximumSize(512)
                    .expireAfterAccess(Duration.ofMinutes(10))
                    .build();

            final var rootIdCache = Caffeine.newBuilder()
                    .maximumSize(512)
                    .expireAfterAccess(Duration.ofMinutes(10))
                    .build();

            return new DefaultOcflObjectSessionFactory(ocflRepo,
                    staging,
                    objectMapper,
                    new CaffeineCache<>(headersCache),
                    new CaffeineCache<>(rootIdCache),
                    CommitType.NEW_VERSION,
                    "Generated by Fedora 4/5 to Fedora 6 migration",
                    config.getFedoraUser(),
                    config.getFedoraUserAddress());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static MutableOcflRepository createOcflRepo(final Config config,
                                                        final Path storageRoot,
                                                        final Path workDir) {
        final var logicalPathMapper = SystemUtils.IS_OS_WINDOWS || config.isForceWindowsMode() ?
                LogicalPathMappers.percentEncodingWindowsMapper() : LogicalPathMappers.percentEncodingLinuxMapper();

        final var digestAlgorithm = DigestAlgorithm.fromOcflName(config.getDigestAlgorithm());

        final OcflStorage storage;

        if (config.isWriteToS3()) {
            final var s3Client = s3Client(config);
            storage = OcflStorageBuilder.builder()
                                        .cloud(OcflS3Client.builder()
                            .s3Client(s3Client)
                            // ocfl-java 2 uploads and downloads files through a transfer manager, and requires one
                            .transferManager(S3TransferManager.builder().s3Client(s3Client).build())
                            .bucket(config.getS3Bucket())
                            .repoPrefix(config.getS3Prefix())
                            .build())
                                        .build();
        } else {
            storage = OcflStorageBuilder.builder().fileSystem(storageRoot).build();
        }

        return new OcflRepositoryBuilder()
                .defaultLayoutConfig(new HashedNTupleLayoutConfig())
                .ocflConfig(new OcflConfig()
                        .setDefaultDigestAlgorithm(digestAlgorithm)
                        .setOcflVersion(OcflVersion.OCFL_1_1)
                        .setUpgradeObjectsOnWrite(true)
                )
                .logicalPathMapper(logicalPathMapper)
                .storage(storage)
                .workDir(workDir)
                .buildMutable();
    }

    private static S3AsyncClient s3Client(final Config config) {
        // multipartEnabled lets the client use UploadPartCopy for objects over the 5GB CopyObject limit
        final var builder = S3AsyncClient.builder().multipartEnabled(true);

        if (StringUtils.isNotBlank(config.getS3Region())) {
            builder.region(Region.of(config.getS3Region()));
        }

        if (StringUtils.isNotBlank(config.getS3Endpoint())) {
            builder.endpointOverride(URI.create(config.getS3Endpoint()));
        }

        if (config.isS3PathStyleAccess()) {
            builder.serviceConfiguration(c -> c.pathStyleAccessEnabled(true));
        }

        if (StringUtils.isNoneBlank(config.getS3AccessKey(), config.getS3SecretKey())) {
            builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(config.getS3AccessKey(), config.getS3SecretKey())));
        }

        return builder.build();
    }

}
