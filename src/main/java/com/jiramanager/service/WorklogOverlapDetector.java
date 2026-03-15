package com.jiramanager.service;

import com.jiramanager.model.WorklogEntry;

import java.util.*;

/**
 * Detects time-range overlaps between worklog entries.
 *
 * <p>Two entries overlap when their time ranges intersect strictly:
 * <pre>  A.startTime &lt; B.endTime  AND  A.endTime &gt; B.startTime</pre>
 * Touching boundaries (A ends exactly when B starts) are <em>not</em> considered overlaps.
 */
public final class WorklogOverlapDetector {

    private WorklogOverlapDetector() {}

    /**
     * Returns the set of worklog IDs that overlap with at least one other entry.
     * Entries with a {@code null} worklogId are silently skipped.
     *
     * @param entries list of worklog entries for a single day (any order)
     * @return unmodifiable set of overlapping worklog IDs; empty when no overlaps exist
     */
    public static Set<String> findOverlappingIds(List<WorklogEntry> entries) {
        if (entries == null || entries.size() < 2) return Set.of();

        Set<String> overlapping = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            for (int j = i + 1; j < entries.size(); j++) {
                WorklogEntry a = entries.get(i);
                WorklogEntry b = entries.get(j);
                if (overlaps(a, b)) {
                    if (a.getWorklogId() != null) overlapping.add(a.getWorklogId());
                    if (b.getWorklogId() != null) overlapping.add(b.getWorklogId());
                }
            }
        }
        return Collections.unmodifiableSet(overlapping);
    }

    /**
     * Returns {@code true} when two entries have overlapping time ranges.
     * Safe against {@code null} start/end times — returns {@code false} in that case.
     */
    public static boolean overlaps(WorklogEntry a, WorklogEntry b) {
        if (a == null || b == null) return false;
        if (a.getStartTime() == null || a.getEndTime() == null) return false;
        if (b.getStartTime() == null || b.getEndTime() == null) return false;
        // [a.start, a.end) ∩ [b.start, b.end) is non-empty iff a.start < b.end && a.end > b.start
        return a.getStartTime().isBefore(b.getEndTime())
                && a.getEndTime().isAfter(b.getStartTime());
    }

    /**
     * Returns all entries that overlap with the given {@code target} entry.
     *
     * @param target     the reference entry
     * @param allEntries the full list of entries for the day
     * @return list of entries (excluding {@code target} itself) that overlap with it
     */
    public static List<WorklogEntry> findOverlapPartners(WorklogEntry target,
                                                          List<WorklogEntry> allEntries) {
        if (target == null || allEntries == null) return List.of();
        List<WorklogEntry> partners = new ArrayList<>();
        for (WorklogEntry e : allEntries) {
            if (e == target) continue;
            if (overlaps(target, e)) partners.add(e);
        }
        return Collections.unmodifiableList(partners);
    }
}
