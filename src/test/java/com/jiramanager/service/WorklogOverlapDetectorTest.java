package com.jiramanager.service;

import com.jiramanager.model.WorklogEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorklogOverlapDetectorTest {

    // ── Helper builder ────────────────────────────────────────────────

    /** Creates a minimal WorklogEntry with an id, start, and end time. */
    private static WorklogEntry entry(String id, String start, String end) {
        Instant s = Instant.parse(start);
        Instant e = Instant.parse(end);
        return WorklogEntry.builder()
                .worklogId(id)
                .ticketKey("TICKET-" + id)
                .startTime(s)
                .endTime(e)
                .minutesSpent((int) java.time.Duration.between(s, e).toMinutes())
                .build();
    }

    // ── findOverlappingIds ────────────────────────────────────────────

    @Test
    void findOverlappingIds_nullList_returnsEmpty() {
        assertThat(WorklogOverlapDetector.findOverlappingIds(null)).isEmpty();
    }

    @Test
    void findOverlappingIds_emptyList_returnsEmpty() {
        assertThat(WorklogOverlapDetector.findOverlappingIds(List.of())).isEmpty();
    }

    @Test
    void findOverlappingIds_singleEntry_returnsEmpty() {
        WorklogEntry e = entry("1", "2025-03-15T08:00:00Z", "2025-03-15T09:00:00Z");
        assertThat(WorklogOverlapDetector.findOverlappingIds(List.of(e))).isEmpty();
    }

    @Test
    void findOverlappingIds_twoNonOverlappingEntries_returnsEmpty() {
        // A: 08:00–09:00   B: 09:00–10:00  (touching, NOT an overlap)
        WorklogEntry a = entry("A", "2025-03-15T08:00:00Z", "2025-03-15T09:00:00Z");
        WorklogEntry b = entry("B", "2025-03-15T09:00:00Z", "2025-03-15T10:00:00Z");
        assertThat(WorklogOverlapDetector.findOverlappingIds(List.of(a, b))).isEmpty();
    }

    @Test
    void findOverlappingIds_twoSequentialWithGap_returnsEmpty() {
        // A: 08:00–09:00   B: 09:30–10:30
        WorklogEntry a = entry("A", "2025-03-15T08:00:00Z", "2025-03-15T09:00:00Z");
        WorklogEntry b = entry("B", "2025-03-15T09:30:00Z", "2025-03-15T10:30:00Z");
        assertThat(WorklogOverlapDetector.findOverlappingIds(List.of(a, b))).isEmpty();
    }

    @Test
    void findOverlappingIds_twoOverlapping_returnsBothIds() {
        // A: 08:00–09:30   B: 09:00–10:00  (overlap 09:00–09:30)
        WorklogEntry a = entry("A", "2025-03-15T08:00:00Z", "2025-03-15T09:30:00Z");
        WorklogEntry b = entry("B", "2025-03-15T09:00:00Z", "2025-03-15T10:00:00Z");
        Set<String> result = WorklogOverlapDetector.findOverlappingIds(List.of(a, b));
        assertThat(result).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void findOverlappingIds_oneContainedInsideAnother_returnsBothIds() {
        // A: 08:00–10:00   B: 08:30–09:30  (B fully inside A)
        WorklogEntry a = entry("A", "2025-03-15T08:00:00Z", "2025-03-15T10:00:00Z");
        WorklogEntry b = entry("B", "2025-03-15T08:30:00Z", "2025-03-15T09:30:00Z");
        Set<String> result = WorklogOverlapDetector.findOverlappingIds(List.of(a, b));
        assertThat(result).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void findOverlappingIds_exactSameTime_returnsBothIds() {
        // A and B have identical time range
        WorklogEntry a = entry("A", "2025-03-15T09:00:00Z", "2025-03-15T10:00:00Z");
        WorklogEntry b = entry("B", "2025-03-15T09:00:00Z", "2025-03-15T10:00:00Z");
        Set<String> result = WorklogOverlapDetector.findOverlappingIds(List.of(a, b));
        assertThat(result).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void findOverlappingIds_threeEntries_onlyOverlappingPairMarked() {
        // A: 08:00–09:00   B: 08:30–09:30 (overlaps A)   C: 10:00–11:00 (no overlap)
        WorklogEntry a = entry("A", "2025-03-15T08:00:00Z", "2025-03-15T09:00:00Z");
        WorklogEntry b = entry("B", "2025-03-15T08:30:00Z", "2025-03-15T09:30:00Z");
        WorklogEntry c = entry("C", "2025-03-15T10:00:00Z", "2025-03-15T11:00:00Z");
        Set<String> result = WorklogOverlapDetector.findOverlappingIds(List.of(a, b, c));
        assertThat(result).containsExactlyInAnyOrder("A", "B");
        assertThat(result).doesNotContain("C");
    }

    @Test
    void findOverlappingIds_allThreeOverlap_returnsAllIds() {
        // A: 08:00–10:00   B: 09:00–11:00   C: 09:30–10:30
        WorklogEntry a = entry("A", "2025-03-15T08:00:00Z", "2025-03-15T10:00:00Z");
        WorklogEntry b = entry("B", "2025-03-15T09:00:00Z", "2025-03-15T11:00:00Z");
        WorklogEntry c = entry("C", "2025-03-15T09:30:00Z", "2025-03-15T10:30:00Z");
        Set<String> result = WorklogOverlapDetector.findOverlappingIds(List.of(a, b, c));
        assertThat(result).containsExactlyInAnyOrder("A", "B", "C");
    }

    @Test
    void findOverlappingIds_entryWithNullId_doesNotThrow() {
        // Entry with null worklogId should not cause NPE
        WorklogEntry a = WorklogEntry.builder()
                .worklogId(null)
                .ticketKey("T-1")
                .startTime(Instant.parse("2025-03-15T08:00:00Z"))
                .endTime(Instant.parse("2025-03-15T09:00:00Z"))
                .minutesSpent(60)
                .build();
        WorklogEntry b = entry("B", "2025-03-15T08:30:00Z", "2025-03-15T09:30:00Z");
        // Should not throw, and "B" should be in the result (null id entry is silently ignored)
        Set<String> result = WorklogOverlapDetector.findOverlappingIds(List.of(a, b));
        assertThat(result).contains("B");
        assertThat(result).doesNotContainNull();
    }

    @Test
    void findOverlappingIds_entryWithNullTimes_doesNotThrow() {
        WorklogEntry a = WorklogEntry.builder()
                .worklogId("A")
                .ticketKey("T-A")
                .startTime(null)
                .endTime(null)
                .build();
        WorklogEntry b = entry("B", "2025-03-15T08:00:00Z", "2025-03-15T09:00:00Z");
        // Null times → no overlap detected, no NPE
        assertThat(WorklogOverlapDetector.findOverlappingIds(List.of(a, b))).isEmpty();
    }

    // ── overlaps (pair check) ─────────────────────────────────────────

    @Test
    void overlaps_nullEntries_returnsFalse() {
        WorklogEntry e = entry("X", "2025-03-15T08:00:00Z", "2025-03-15T09:00:00Z");
        assertThat(WorklogOverlapDetector.overlaps(null, e)).isFalse();
        assertThat(WorklogOverlapDetector.overlaps(e, null)).isFalse();
        assertThat(WorklogOverlapDetector.overlaps(null, null)).isFalse();
    }

    @Test
    void overlaps_touching_returnsFalse() {
        // A ends exactly when B starts → not an overlap
        WorklogEntry a = entry("A", "2025-03-15T08:00:00Z", "2025-03-15T09:00:00Z");
        WorklogEntry b = entry("B", "2025-03-15T09:00:00Z", "2025-03-15T10:00:00Z");
        assertThat(WorklogOverlapDetector.overlaps(a, b)).isFalse();
        assertThat(WorklogOverlapDetector.overlaps(b, a)).isFalse();
    }

    @Test
    void overlaps_partialOverlap_returnsTrue() {
        WorklogEntry a = entry("A", "2025-03-15T08:00:00Z", "2025-03-15T09:30:00Z");
        WorklogEntry b = entry("B", "2025-03-15T09:00:00Z", "2025-03-15T10:00:00Z");
        assertThat(WorklogOverlapDetector.overlaps(a, b)).isTrue();
        assertThat(WorklogOverlapDetector.overlaps(b, a)).isTrue(); // commutative
    }

    // ── findOverlapPartners ───────────────────────────────────────────

    @Test
    void findOverlapPartners_noPartners_returnsEmpty() {
        WorklogEntry a = entry("A", "2025-03-15T08:00:00Z", "2025-03-15T09:00:00Z");
        WorklogEntry b = entry("B", "2025-03-15T09:30:00Z", "2025-03-15T10:30:00Z");
        assertThat(WorklogOverlapDetector.findOverlapPartners(a, List.of(a, b))).isEmpty();
    }

    @Test
    void findOverlapPartners_returnsOnlyOverlappingEntries() {
        WorklogEntry a = entry("A", "2025-03-15T08:00:00Z", "2025-03-15T09:30:00Z");
        WorklogEntry b = entry("B", "2025-03-15T09:00:00Z", "2025-03-15T10:00:00Z"); // overlaps A
        WorklogEntry c = entry("C", "2025-03-15T10:30:00Z", "2025-03-15T11:00:00Z"); // no overlap
        List<WorklogEntry> partners = WorklogOverlapDetector.findOverlapPartners(a, List.of(a, b, c));
        assertThat(partners).containsExactly(b);
    }

    @Test
    void findOverlapPartners_doesNotIncludeTargetItself() {
        WorklogEntry a = entry("A", "2025-03-15T08:00:00Z", "2025-03-15T09:00:00Z");
        List<WorklogEntry> partners = WorklogOverlapDetector.findOverlapPartners(a, List.of(a));
        assertThat(partners).isEmpty();
    }

    @Test
    void findOverlapPartners_nullInputs_returnEmpty() {
        WorklogEntry a = entry("A", "2025-03-15T08:00:00Z", "2025-03-15T09:00:00Z");
        assertThat(WorklogOverlapDetector.findOverlapPartners(null, List.of(a))).isEmpty();
        assertThat(WorklogOverlapDetector.findOverlapPartners(a, null)).isEmpty();
    }
}
