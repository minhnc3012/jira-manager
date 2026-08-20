package com.jiramanager.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureDesignDocStorageTest {

    @Test
    void save_writesFileUnderFeatureAndVersionDirectory(@TempDir Path tempDir) throws IOException {
        FeatureDesignDocStorage storage = new FeatureDesignDocStorage(tempDir);

        Path saved = storage.save(1L, 1, "plan.html",
                new ByteArrayInputStream("<html><body>Plan</body></html>".getBytes(StandardCharsets.UTF_8)));

        assertThat(saved).exists();
        assertThat(saved.getParent().getFileName().toString()).isEqualTo("v1");
        assertThat(saved.getParent().getParent().getFileName().toString()).isEqualTo("1");
        assertThat(Files.readString(saved)).contains("Plan");
    }

    @Test
    void save_sanitizesUnsafeFileName(@TempDir Path tempDir) throws IOException {
        FeatureDesignDocStorage storage = new FeatureDesignDocStorage(tempDir);

        Path saved = storage.save(2L, 1, "../../etc/passwd",
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));

        assertThat(saved.getFileName().toString()).doesNotContain("..").doesNotContain("/");
        assertThat(saved.startsWith(tempDir.resolve("2").resolve("v1"))).isTrue();
    }

    @Test
    void save_keepsEveryVersionSeparately(@TempDir Path tempDir) throws IOException {
        FeatureDesignDocStorage storage = new FeatureDesignDocStorage(tempDir);

        Path v1 = storage.save(3L, 1, "plan.html", new ByteArrayInputStream("v1".getBytes(StandardCharsets.UTF_8)));
        Path v2 = storage.save(3L, 2, "plan.html", new ByteArrayInputStream("v2".getBytes(StandardCharsets.UTF_8)));

        // Both versions must still exist and be independently readable — nothing gets deleted.
        assertThat(Files.exists(v1)).isTrue();
        assertThat(Files.exists(v2)).isTrue();
        assertThat(Files.readString(v1)).isEqualTo("v1");
        assertThat(Files.readString(v2)).isEqualTo("v2");
    }

    @Test
    void save_blankFileName_fallsBackToDefault(@TempDir Path tempDir) throws IOException {
        FeatureDesignDocStorage storage = new FeatureDesignDocStorage(tempDir);

        Path saved = storage.save(4L, 1, "", new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));

        assertThat(saved.getFileName().toString()).isEqualTo("design.html");
    }
}
