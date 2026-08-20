package com.jiramanager.web;

import com.jiramanager.model.FeatureDesignDoc;
import com.jiramanager.repository.FeatureDesignDocRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DesignDocControllerTest {

    @Test
    void viewLatest_missingDoc_returns404() {
        FeatureDesignDocRepository repo = mock(FeatureDesignDocRepository.class);
        when(repo.findTopByFeature_IdOrderByVersionDesc(99L)).thenReturn(Optional.empty());
        DesignDocController controller = new DesignDocController(repo);

        ResponseEntity<?> response = controller.viewLatest(99L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void viewLatest_fileMissingFromDisk_returns404(@TempDir Path tempDir) {
        FeatureDesignDocRepository repo = mock(FeatureDesignDocRepository.class);
        FeatureDesignDoc doc = new FeatureDesignDoc();
        doc.setFilePath(tempDir.resolve("does-not-exist.html").toString());
        when(repo.findTopByFeature_IdOrderByVersionDesc(1L)).thenReturn(Optional.of(doc));
        DesignDocController controller = new DesignDocController(repo);

        ResponseEntity<?> response = controller.viewLatest(1L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void viewLatest_existingDoc_returnsInlineHtmlResponse(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("plan.html");
        Files.writeString(file, "<html><body>Plan</body></html>", StandardCharsets.UTF_8);

        FeatureDesignDocRepository repo = mock(FeatureDesignDocRepository.class);
        FeatureDesignDoc doc = new FeatureDesignDoc();
        doc.setVersion(2);
        doc.setFilePath(file.toString());
        doc.setFileName("plan.html");
        when(repo.findTopByFeature_IdOrderByVersionDesc(2L)).thenReturn(Optional.of(doc));
        DesignDocController controller = new DesignDocController(repo);

        ResponseEntity<?> response = controller.viewLatest(2L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).contains("text/html");
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("inline").contains("plan.html");
        // No Content-Security-Policy by deliberate product decision (local-only tool, trusted
        // uploader) — see DesignDocController's class javadoc for what to reinstate before publish.
        assertThat(response.getHeaders().getFirst("Content-Security-Policy")).isNull();
    }

    @Test
    void viewVersion_missingVersion_returns404() {
        FeatureDesignDocRepository repo = mock(FeatureDesignDocRepository.class);
        when(repo.findByFeature_IdAndVersion(3L, 5)).thenReturn(Optional.empty());
        DesignDocController controller = new DesignDocController(repo);

        ResponseEntity<?> response = controller.viewVersion(3L, 5);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void viewVersion_existingOlderVersion_returnsInlineHtmlResponse(@TempDir Path tempDir) throws IOException {
        Path file = tempDir.resolve("plan-v1.html");
        Files.writeString(file, "<html><body>Old plan</body></html>", StandardCharsets.UTF_8);

        FeatureDesignDocRepository repo = mock(FeatureDesignDocRepository.class);
        FeatureDesignDoc doc = new FeatureDesignDoc();
        doc.setVersion(1);
        doc.setFilePath(file.toString());
        doc.setFileName("plan-v1.html");
        when(repo.findByFeature_IdAndVersion(4L, 1)).thenReturn(Optional.of(doc));
        DesignDocController controller = new DesignDocController(repo);

        ResponseEntity<?> response = controller.viewVersion(4L, 1);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("plan-v1.html");
    }
}
