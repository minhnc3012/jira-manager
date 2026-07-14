package com.jiramanager.service;

import com.jiramanager.model.ConfluenceFeature;
import com.jiramanager.model.ConfluenceFeatureUpdateHistory;
import com.jiramanager.model.ConfluencePageDetail;
import com.jiramanager.model.ConfluencePageMeta;
import com.jiramanager.model.ConfluenceSpaceInfo;
import com.jiramanager.model.JiraConfig;
import com.jiramanager.model.JiraTicket;
import com.jiramanager.model.ManualSyncTarget;
import com.jiramanager.model.TicketDocItem;
import com.jiramanager.repository.ConfluenceFeatureRepository;
import com.jiramanager.repository.ConfluenceFeatureUpdateHistoryRepository;
import com.jiramanager.repository.JiraConfigRepository;
import com.jiramanager.repository.ManualSyncTargetRepository;
import com.jiramanager.repository.TicketDocItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Keeps the Spaces tree ({@link ConfluenceFeature}) and Ticket Docs ({@link TicketDocItem})
 * fresh in the background — never on a request thread. Runs once at app startup, then every
 * 4 hours, mirroring {@link JiraUserSyncRunner}'s shape: a virtual thread picks up the work
 * immediately so neither app boot nor the scheduler thread ever blocks on Jira/Confluence calls.
 *
 * "Feature" here means one synced Confluence page (a node in the Spaces tree grid). Spaces are
 * discovered by matching a Confluence space key against the Jira project keys each local user
 * has tickets in — see {@link #syncFeatures}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseSyncRunner implements ApplicationRunner {

    private static final long FOUR_HOURS_MS = 4 * 60 * 60 * 1000;

    private final JiraConfigRepository jiraConfigRepo;
    private final ConfluenceFeatureRepository featureRepo;
    private final ConfluenceFeatureUpdateHistoryRepository historyRepo;
    private final TicketDocItemRepository ticketDocItemRepo;
    private final ManualSyncTargetRepository manualSyncTargetRepo;
    private final JiraService jiraService;

    @Override
    public void run(ApplicationArguments args) {
        Thread.ofVirtual().start(this::syncAll);
    }

    /** initialDelay (not 0) avoids double-firing alongside the startup run() above. */
    @Scheduled(initialDelay = FOUR_HOURS_MS, fixedRate = FOUR_HOURS_MS)
    public void scheduledSync() {
        Thread.ofVirtual().start(this::syncAll);
    }

    private void syncAll() {
        Map<String, List<JiraConfig>> byBaseUrl = jiraConfigRepo.findAll().stream()
                .filter(KnowledgeBaseSyncRunner::isUsable)
                .collect(Collectors.groupingBy(JiraConfig::getBaseUrl));

        log.info("Starting background Knowledge Base sync for {} site(s)", byBaseUrl.size());
        for (Map.Entry<String, List<JiraConfig>> entry : byBaseUrl.entrySet()) {
            syncSite(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Refreshes both the Feature tree and ticket-doc change-detection for a single Jira site.
     * Used for the startup/scheduled pass (one site at a time) and for a manual "Sync now"
     * trigger (e.g. right after a user saves their Jira connection in Settings).
     */
    public void syncOne(JiraConfig cfg) {
        if (!isUsable(cfg)) return;
        syncSite(cfg.getBaseUrl(), List.of(cfg));
    }

    private void syncSite(String baseUrl, List<JiraConfig> configsForSite) {
        try {
            syncFeatures(baseUrl, configsForSite);
        } catch (Exception e) {
            log.warn("Feature sync failed for {}: {}", baseUrl, e.getMessage());
        }
        try {
            syncTicketDocs(baseUrl, configsForSite.get(0));
        } catch (Exception e) {
            log.warn("Ticket doc sync failed for {}: {}", baseUrl, e.getMessage());
        }
    }

    // ── Feature tree sync ────────────────────────────────────────────

    private void syncFeatures(String baseUrl, List<JiraConfig> configsForSite) {
        // Union of project keys across every local user on this site — each user only sees
        // their own assigned tickets, so this widens coverage beyond a single representative.
        Set<String> projectKeys = new LinkedHashSet<>();
        for (JiraConfig cfg : configsForSite) {
            try {
                for (JiraTicket t : jiraService.getMyTickets(cfg)) {
                    if (t.getProjectKey() != null && !t.getProjectKey().isBlank()) {
                        projectKeys.add(t.getProjectKey());
                    }
                }
            } catch (Exception e) {
                log.warn("Could not fetch tickets for project-key discovery ({}): {}", baseUrl, e.getMessage());
            }
        }
        if (projectKeys.isEmpty()) {
            log.info("No assigned-ticket project keys found for {} — nothing to auto-match against "
                    + "Confluence spaces (a user needs at least one ticket assigned to them)", baseUrl);
        } else {
            log.info("Checking {} project key(s) for a matching Confluence space ({}): {}",
                    projectKeys.size(), baseUrl, projectKeys);
        }

        // Manually-tracked whole spaces (see ManualSyncTarget) — the fallback for when the
        // project-key ↔ space-key auto-match fails, e.g. the keys genuinely differ.
        List<ManualSyncTarget> manualTargets = manualSyncTargetRepo.findByBaseUrl(baseUrl);
        Set<String> manualSpaceKeys = manualTargets.stream()
                .filter(t -> t.getPageId() == null)
                .map(ManualSyncTarget::getSpaceKey)
                .collect(Collectors.toSet());
        projectKeys.addAll(manualSpaceKeys);

        if (projectKeys.isEmpty() && manualTargets.isEmpty()) return;

        // One representative config does the actual Confluence crawl — permissions could
        // theoretically differ per user on a shared site; same simplification JiraUserSyncRunner
        // already accepts for its own site-wide sync.
        JiraConfig representative = configsForSite.get(0);
        Instant now = Instant.now();

        for (String spaceKey : projectKeys) {
            ConfluenceSpaceInfo space = jiraService.getConfluenceSpace(representative, spaceKey);
            if (space == null) {
                log.info("No Confluence space found with key '{}' ({}) — skipping", spaceKey, baseUrl);
                continue; // no matching Confluence space — common, not an error
            }

            List<ConfluencePageMeta> pages = jiraService.listSpacePages(representative, spaceKey);
            for (ConfluencePageMeta meta : pages) {
                upsertFeature(baseUrl, spaceKey, meta, now);
            }
            log.info("Synced {} Confluence page(s) for space {} ({})", pages.size(), spaceKey, baseUrl);
        }

        // Manually-tracked single pages — fetched directly by ID, bypassing the space-wide crawl
        // entirely (works even for pages whose space was never matched above).
        for (ManualSyncTarget target : manualTargets) {
            if (target.getPageId() == null) continue;
            ConfluencePageDetail detail = jiraService.getConfluencePageDetail(representative, target.getPageId());
            if (detail == null) {
                log.warn("Manually tracked page {} not found/not accessible ({})", target.getPageId(), baseUrl);
                continue;
            }
            ConfluencePageMeta meta = new ConfluencePageMeta(
                    detail.id(), detail.title(), detail.parentPageId(), detail.version(), detail.updatedAt());
            upsertFeature(baseUrl, detail.spaceKey(), meta, now);
            log.info("Synced manually-tracked page '{}' ({}) for space {} ({})",
                    detail.title(), detail.id(), detail.spaceKey(), baseUrl);
        }
    }

    private void upsertFeature(String baseUrl, String spaceKey, ConfluencePageMeta meta, Instant now) {
        ConfluenceFeature feature = featureRepo.findByBaseUrlAndPageId(baseUrl, meta.id())
                .orElseGet(ConfluenceFeature::new);

        boolean isNew = feature.getId() == null;
        Integer previousVersion = isNew ? null : feature.getVersion();

        feature.setBaseUrl(baseUrl);
        feature.setSpaceKey(spaceKey);
        feature.setPageId(meta.id());
        feature.setTitle(meta.title());
        feature.setParentPageId(meta.parentPageId());
        feature.setVersion(meta.version());
        feature.setConfluenceUpdatedAt(meta.updatedAt());
        feature.setLastSyncedAt(now);
        feature.setUrl(baseUrl + "/wiki/spaces/" + spaceKey + "/pages/" + meta.id());
        featureRepo.save(feature);

        // Only a real version bump on an already-known page is logged as history — first
        // discovery of a page is a baseline, not a "change".
        if (!isNew && previousVersion != null && meta.version() > previousVersion) {
            ConfluenceFeatureUpdateHistory hist = new ConfluenceFeatureUpdateHistory();
            hist.setFeature(feature);
            hist.setOldVersion(previousVersion);
            hist.setNewVersion(meta.version());
            hist.setNewUpdatedAt(meta.updatedAt());
            hist.setDetectedAt(now);
            historyRepo.save(hist);
        }
    }

    // ── Ticket doc change detection ─────────────────────────────────

    private void syncTicketDocs(String baseUrl, JiraConfig cfg) {
        List<TicketDocItem> items = ticketDocItemRepo.findByFeatureBaseUrl(baseUrl);
        if (items.isEmpty()) return;

        List<String> keys = items.stream().map(TicketDocItem::getTicketKey).distinct().toList();
        Map<String, JiraTicket> freshByKey = jiraService.getTicketsByKeys(cfg, keys).stream()
                .collect(Collectors.toMap(JiraTicket::getKey, t -> t, (a, b) -> a));

        int flagged = 0;
        for (TicketDocItem item : items) {
            JiraTicket fresh = freshByKey.get(item.getTicketKey());
            if (fresh == null || fresh.getUpdatedInstant() == null) continue;
            Instant known = item.getLastKnownUpdated();
            if (known == null || fresh.getUpdatedInstant().isAfter(known)) {
                item.setNeedsRegenerate(true);
                ticketDocItemRepo.save(item);
                flagged++;
            }
        }
        if (flagged > 0) {
            log.info("Flagged {} ticket doc item(s) as changed for {}", flagged, baseUrl);
        }
    }

    private static boolean isUsable(JiraConfig cfg) {
        return cfg.getBaseUrl() != null && !cfg.getBaseUrl().isBlank()
                && cfg.getEmail() != null && !cfg.getEmail().isBlank()
                && cfg.getApiToken() != null && !cfg.getApiToken().isBlank();
    }
}
