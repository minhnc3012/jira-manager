package com.jiramanager.service;

import com.jiramanager.model.ConfluencePageDetail;
import com.jiramanager.model.JiraConfig;
import com.jiramanager.model.JiraTicket;
import com.jiramanager.model.SpaceItem;
import com.jiramanager.model.SpaceItemUpdateHistory;
import com.jiramanager.model.TicketDocItem;
import com.jiramanager.repository.JiraConfigRepository;
import com.jiramanager.repository.SpaceItemRepository;
import com.jiramanager.repository.SpaceItemUpdateHistoryRepository;
import com.jiramanager.repository.TicketDocItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Keeps every linked {@link SpaceItem} (one attached to a specific Confluence page) and every
 * Ticket Doc's attached tickets ({@link TicketDocItem}) fresh in the background — never on a
 * request thread. Runs once at app startup, then every 4 hours, mirroring
 * {@link JiraUserSyncRunner}'s shape: a virtual thread picks up the work immediately so neither
 * app boot nor the scheduler thread ever blocks on Jira/Confluence calls.
 *
 * <p>The Spaces tree itself (which items exist, their names, and their parent/child structure)
 * is entirely user-managed via {@code SpacesView} — this runner never creates, renames, or
 * reparents an item. It only refreshes the sync-derived fields (version/updated timestamp) on
 * items the user has explicitly linked to a Confluence page.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeBaseSyncRunner implements ApplicationRunner {

    private static final long FOUR_HOURS_MS = 4 * 60 * 60 * 1000;

    private final JiraConfigRepository jiraConfigRepo;
    private final SpaceItemRepository spaceItemRepo;
    private final SpaceItemUpdateHistoryRepository historyRepo;
    private final TicketDocItemRepository ticketDocItemRepo;
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
            syncSite(entry.getKey(), entry.getValue().get(0));
        }
    }

    /**
     * Refreshes both linked Space items and ticket-doc change-detection for a single Jira site.
     * Used for the startup/scheduled pass and for a manual "Refresh" trigger on the Spaces page.
     */
    public void syncOne(JiraConfig cfg) {
        if (!isUsable(cfg)) return;
        syncSite(cfg.getBaseUrl(), cfg);
    }

    private void syncSite(String baseUrl, JiraConfig cfg) {
        try {
            syncLinkedItems(baseUrl, cfg);
        } catch (Exception e) {
            log.warn("Linked item sync failed for {}: {}", baseUrl, e.getMessage());
        }
        try {
            syncTicketDocs(baseUrl, cfg);
        } catch (Exception e) {
            log.warn("Ticket doc sync failed for {}: {}", baseUrl, e.getMessage());
        }
    }

    // ── Linked Space item sync (version/updated refresh only — never touches name/structure) ──

    private void syncLinkedItems(String baseUrl, JiraConfig cfg) {
        List<SpaceItem> items = spaceItemRepo.findLinkedBySpaceBaseUrl(baseUrl);
        if (items.isEmpty()) return;

        Instant now = Instant.now();
        int refreshed = 0;
        for (SpaceItem item : items) {
            ConfluencePageDetail detail = jiraService.getConfluencePageDetail(cfg, item.getConfluencePageId());
            if (detail == null) {
                log.warn("Linked page {} not found/not accessible ({}) — leaving last-known state",
                        item.getConfluencePageId(), baseUrl);
                continue;
            }

            boolean bumped = item.getVersion() != null && detail.version() > item.getVersion();
            Integer previousVersion = item.getVersion();

            item.setVersion(detail.version());
            item.setConfluenceUpdatedAt(detail.updatedAt());
            item.setLastSyncedAt(now);
            item.setUrl(baseUrl + "/wiki/spaces/" + item.getConfluenceSpaceKey()
                    + "/pages/" + item.getConfluencePageId());
            spaceItemRepo.save(item);
            refreshed++;

            // Only a real version bump on an already-synced item is logged as history — first
            // sync after linking is a baseline, not a "change".
            if (bumped) {
                SpaceItemUpdateHistory hist = new SpaceItemUpdateHistory();
                hist.setItem(item);
                hist.setOldVersion(previousVersion);
                hist.setNewVersion(detail.version());
                hist.setNewUpdatedAt(detail.updatedAt());
                hist.setDetectedAt(now);
                historyRepo.save(hist);
            }
        }
        log.info("Refreshed {} linked Space item(s) for {}", refreshed, baseUrl);
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
