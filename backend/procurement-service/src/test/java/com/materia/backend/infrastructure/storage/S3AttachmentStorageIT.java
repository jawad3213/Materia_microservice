package com.materia.backend.infrastructure.storage;

import com.materia.backend.common.attachment.domain.exceptions.AttachmentStorageException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Attachment files in an S3 bucket (Adobe S3Mock here, an S3-compatible server), each service under its own prefix. */
@Testcontainers
class S3AttachmentStorageIT {

    private static final String BUCKET = "materia-attachments";

    @Container
    private static final GenericContainer<?> S3_MOCK = new GenericContainer<>("adobe/s3mock:5.2.3")
            .withExposedPorts(9090)
            // S3Mock is a Spring Boot application: on a loaded CI machine it needs more than the default minute
            .waitingFor(Wait.forHttp("/").forPort(9090).forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));

    private static S3Client s3;

    @BeforeAll
    static void bucket() {
        s3 = S3Client.builder()
                .endpointOverride(URI.create("http://" + S3_MOCK.getHost() + ":" + S3_MOCK.getMappedPort(9090)))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .build();
        s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    }

    @AfterAll
    static void close() {
        s3.close();
    }

    @Test
    @DisplayName("s3: a file is stored under the service's prefix, read back whole, and removed")
    void storeReadDelete() {
        S3AttachmentStorage storage = new S3AttachmentStorage(s3, BUCKET, "procurement/");
        byte[] scan = "%PDF-1.7 delivery note".getBytes(StandardCharsets.UTF_8);

        storage.store("purchase_order/42/a.pdf", scan);

        assertThat(storage.read("purchase_order/42/a.pdf")).isEqualTo(scan);
        assertThat(keys("procurement/")).containsExactly("procurement/purchase_order/42/a.pdf");

        storage.delete("purchase_order/42/a.pdf");

        assertThat(keys("procurement/purchase_order/42/")).isEmpty();
        assertThatThrownBy(() -> storage.read("purchase_order/42/a.pdf"))
                .isInstanceOf(AttachmentStorageException.class);
    }

    @Test
    @DisplayName("s3: a stored file replaces the previous one under the same key")
    void overwrite() {
        S3AttachmentStorage storage = new S3AttachmentStorage(s3, BUCKET, "procurement");

        storage.store("goods_receipt/7/b.png", "first".getBytes(StandardCharsets.UTF_8));
        storage.store("goods_receipt/7/b.png", "second".getBytes(StandardCharsets.UTF_8));

        assertThat(new String(storage.read("goods_receipt/7/b.png"), StandardCharsets.UTF_8)).isEqualTo("second");
        assertThat(keys("procurement/goods_receipt/7/")).containsExactly("procurement/goods_receipt/7/b.png");
    }

    @Test
    @DisplayName("s3: two services in one bucket do not see each other's files")
    void servicesKeepToTheirPrefix() {
        S3AttachmentStorage procurement = new S3AttachmentStorage(s3, BUCKET, "procurement/");
        S3AttachmentStorage finance = new S3AttachmentStorage(s3, BUCKET, "/finance/");

        finance.store("invoice/9/c.pdf", "invoice".getBytes(StandardCharsets.UTF_8));

        assertThat(keys("finance/")).containsExactly("finance/invoice/9/c.pdf");
        assertThatThrownBy(() -> procurement.read("invoice/9/c.pdf")).isInstanceOf(AttachmentStorageException.class);
    }

    @Test
    @DisplayName("s3: removing a file that is not there succeeds, as on disk")
    void deleteMissing() {
        new S3AttachmentStorage(s3, BUCKET, "procurement/").delete("purchase_order/1/missing.pdf");
    }

    @Test
    @DisplayName("s3: a key that would leave the service's prefix is refused before reaching the bucket")
    void invalidKeys() {
        S3AttachmentStorage storage = new S3AttachmentStorage(s3, BUCKET, "procurement/");

        for (String key : List.of("../finance/invoice/9/c.pdf", "/purchase_order/1/a.pdf", "purchase_order//a.pdf",
                "purchase_order/./a.pdf", "purchase_order\\a.pdf", " ")) {
            assertThatThrownBy(() -> storage.read(key)).as(key).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("s3: without a bucket the service does not start")
    void bucketRequired() {
        assertThatThrownBy(() -> new S3AttachmentStorage(s3, " ", "procurement/"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.attachments.s3.bucket");
    }

    private static List<String> keys(String prefix) {
        return s3.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).prefix(prefix).build())
                .contents().stream().map(S3Object::key).toList();
    }
}
