package com.jiramanager.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

public class BookingTest {

    // Simple BookedBay record
    record BookedBay(String name, LocalDateTime entryTime, LocalDateTime exitTime) {}

    // Simulate getExitTime (returns the stored exitTime)
    static LocalDateTime getExitTime(BookedBay bay) {
        return bay.exitTime();
    }

    public static void main(String[] args) {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");

        // Booking A (09->11): 10h falls within 09-11
        // Booking B (10->12): 10h falls within 10-12
        // Booking C (11->13): 10h falls within 11-13
        // "10h" here = the car stays for 10 hours inside the slot
        // So actual exit = entry + 10h, capped/stored as given range end

        // Per the problem: entry/exit are the SLOT boundaries, not +10h offset
        // A: entry=09:00, exit=11:00  → truncated entry=09, exit+1h=12
        // B: entry=10:00, exit=12:00  → truncated entry=10, exit+1h=13
        // C: entry=11:00, exit=13:00  → truncated entry=11, exit+1h=14

        List<BookedBay> bookedBays = List.of(
            new BookedBay("A", LocalDateTime.of(2024, 1, 1,  5, 0), LocalDateTime.of(2024, 1, 1, 8, 0)),
            new BookedBay("B", LocalDateTime.of(2024, 1, 1, 10, 0), LocalDateTime.of(2024, 1, 1, 15, 0)),
            new BookedBay("C", LocalDateTime.of(2024, 1, 1, 14, 0), LocalDateTime.of(2024, 1, 1, 23, 0))
        );

        // ── Step 1: Build events ──────────────────────────────────────────────
        TreeMap<LocalDateTime, Integer> events = new TreeMap<>();

        for (BookedBay bay : bookedBays) {
            LocalDateTime entry = bay.entryTime().truncatedTo(ChronoUnit.HOURS);
            LocalDateTime exit  = getExitTime(bay).truncatedTo(ChronoUnit.HOURS).plusHours(1);
//            LocalDateTime next = entry;
//            while(next.isBefore(exit)) {
//                events.merge(next,  1, Integer::sum);   // car enters -> +1
//                next = next.plusHours(1);
//            }
            events.merge(entry,  1, Integer::sum);
            events.merge(exit,  -1, Integer::sum);   // car leaves -> -1
        }

        System.out.println("=== EVENTS (raw delta) ===");
        System.out.printf("%-8s  %s%n", "Time", "Delta");
        System.out.println("-------------------");
        for (Map.Entry<LocalDateTime, Integer> e : events.entrySet()) {
            String sign = e.getValue() > 0 ? "+" + e.getValue() : String.valueOf(e.getValue());
            System.out.printf("%-8s  %s%n", e.getKey().format(fmt), sign);
        }

        // ── Step 2: Build cumulative counts ──────────────────────────────────
        TreeMap<LocalDateTime, Integer> cumulativeCounts = new TreeMap<>();
        int runningCount = 0;
        for (Map.Entry<LocalDateTime, Integer> event : events.entrySet()) {
            runningCount += event.getValue();
            cumulativeCounts.put(event.getKey(), runningCount);
        }

        System.out.println();
        System.out.println("=== CUMULATIVE COUNTS (cars parked) ===");
        System.out.printf("%-8s  %-30s  %s%n", "Time", "Calculation", "Result");
        System.out.println("-------------------------------------------------------");

        // Re-compute step by step for display
        int prev = 0;
        for (Map.Entry<LocalDateTime, Integer> e : events.entrySet()) {
            int delta = e.getValue();
            int curr  = prev + delta;
            String sign  = delta > 0 ? "+" + delta : String.valueOf(delta);
            String label = delta > 0 ? "(car enters)" : "(car leaves)";
            System.out.printf("%-8s  %d %s %s %-10s  → %d cars%n",
                e.getKey().format(fmt), prev, sign, label, "", curr);
            prev = curr;
        }

        System.out.println();
        System.out.println("=== CUMULATIVE MAP (final) ===");
        events.forEach((t, c) ->
            System.out.printf("%-8s  %d cars%n", t.format(fmt), c));
    }
}
