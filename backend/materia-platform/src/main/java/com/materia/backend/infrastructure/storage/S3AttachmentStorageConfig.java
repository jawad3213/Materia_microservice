package com.materia.backend.infrastructure.storage;

import com.materia.backend.common.attachment.domain.ports.out.AttachmentStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;

/**
 * Attachment files in S3, with {@code app.attachments.storage=s3} (the default, {@code local}, keeps them on disk:
 * LocalFileAttachmentStorage).
 *
 * <ul>
 *   <li>{@code app.attachments.s3.bucket}: the bucket (required);</li>
 *   <li>{@code app.attachments.s3.prefix}: this service's part of it, e.g. {@code procurement/};</li>
 *   <li>{@code app.attachments.s3.region}: the bucket's region; by default the SDK's (AWS_REGION, which ECS sets);</li>
 *   <li>{@code app.attachments.s3.endpoint} and {@code .path-style}: an S3-compatible server instead of AWS (MinIO,
 *       LocalStack), for local runs and tests.</li>
 * </ul>
 * Credentials come from the SDK's default chain: on ECS the task role, which only needs s3:PutObject, s3:GetObject
 * and s3:DeleteObject on this service's prefix.
 */
@Configuration
@ConditionalOnProperty(name = "app.attachments.storage", havingValue = "s3")
public class S3AttachmentStorageConfig {

    @Bean(destroyMethod = "close")
    public S3Client attachmentsS3Client(@Value("${app.attachments.s3.region:}") String region,
                                        @Value("${app.attachments.s3.endpoint:}") String endpoint,
                                        @Value("${app.attachments.s3.path-style:false}") boolean pathStyle) {
        S3ClientBuilder builder = S3Client.builder().forcePathStyle(pathStyle);
        if (!region.isBlank()) {
            builder.region(Region.of(region.trim()));
        } else if (!endpoint.isBlank()) {
            builder.region(Region.US_EAST_1); // an S3-compatible server ignores it, but the SDK needs one
        }
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint.trim()));
        }
        return builder.build();
    }

    @Bean
    public AttachmentStorage s3AttachmentStorage(S3Client attachmentsS3Client,
                                                 @Value("${app.attachments.s3.bucket:}") String bucket,
                                                 @Value("${app.attachments.s3.prefix:}") String prefix) {
        return new S3AttachmentStorage(attachmentsS3Client, bucket, prefix);
    }
}
