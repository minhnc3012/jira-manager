package com.jiramanager.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Stores the uploaded HTML design doc for a Feature (§ {@code FeatureDesignDoc}) under
 * {@code ./data/feature-design-docs/}, alongside this app's other on-disk artifacts (H2 DB,
 * Ticket Docs markdown). Every upload is a new **version** kept in its own subdirectory — nothing
 * is deleted — so past versions stay downloadable for tracing/comparison later.
 */
@Service
public class FeatureDesignDocStorage {

    private final Path baseDir;

    public FeatureDesignDocStorage() {
        this.baseDir = Path.of("data", "feature-design-docs");
    }

    /** Package-private constructor for tests — writes under a caller-supplied (e.g. temp) directory. */
    FeatureDesignDocStorage(Path baseDir) {
        this.baseDir = baseDir;
    }

    /**
     * Saves the uploaded file under {@code {baseDir}/{featureId}/v{version}/{sanitizedFileName}}.
     * Never deletes anything — each version lives in its own subdirectory. Returns the written path.
     */
    public Path save(Long featureId, int version, String originalFileName, InputStream content) throws IOException {
        Path dir = baseDir.resolve(String.valueOf(featureId)).resolve("v" + version);
        Files.createDirectories(dir);

        Path target = dir.resolve(sanitizeFileName(originalFileName));
        Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    /**
     * Strips any directory components from the uploaded name (defends against a crafted name
     * like {@code ../../etc/passwd}) and replaces anything outside a safe character set.
     */
    private String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) return "design.html";
        String base = Path.of(name).getFileName().toString();
        base = base.replaceAll("[^a-zA-Z0-9._-]", "_");
        return base.isBlank() ? "design.html" : base;
    }
}
