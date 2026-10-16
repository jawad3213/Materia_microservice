package com.materia.backend.infrastructure.storage;

import com.materia.backend.common.attachment.domain.ports.out.AttachmentStorage;
import com.materia.backend.common.attachment.infrastructure.adapters.out.storage.LocalFileAttachmentStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** app.attachments.storage picks where attachment files go: on disk unless set to s3. */
class AttachmentStorageSelectionTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(LocalFileAttachmentStorage.class, S3AttachmentStorageConfig.class);

    @Test
    @DisplayName("storage: on disk by default, and with storage=local")
    void localByDefault() {
        context.run(app -> assertThat(app).getBean(AttachmentStorage.class).isInstanceOf(LocalFileAttachmentStorage.class));
        context.withPropertyValues("app.attachments.storage=local")
                .run(app -> assertThat(app).getBean(AttachmentStorage.class).isInstanceOf(LocalFileAttachmentStorage.class));
    }

    @Test
    @DisplayName("storage: in S3 with storage=s3, and nothing on disk")
    void s3() {
        context.withPropertyValues("app.attachments.storage=s3", "app.attachments.s3.bucket=materia-attachments",
                        "app.attachments.s3.region=eu-west-3")
                .run(app -> {
                    assertThat(app).getBean(AttachmentStorage.class).isInstanceOf(S3AttachmentStorage.class);
                    assertThat(app).doesNotHaveBean(LocalFileAttachmentStorage.class);
                });
    }

    @Test
    @DisplayName("storage: s3 without a bucket does not start")
    void s3WithoutBucket() {
        context.withPropertyValues("app.attachments.storage=s3", "app.attachments.s3.region=eu-west-3")
                .run(app -> assertThat(app).hasFailed()
                        .getFailure().rootCause().hasMessageContaining("app.attachments.s3.bucket"));
    }
}
