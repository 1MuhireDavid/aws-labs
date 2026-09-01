package com.labs.ecsdemo;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

/**
 * In-memory revision log -- a stand-in for the rev-history table on a real
 * engineering drawing, in keeping with the blueprint theme of the landing
 * page. There's no database in this stack, so this is deliberately
 * ephemeral: a blue/green deploy starts a brand-new task with an empty
 * log, which is itself a small, honest demonstration of why stateful data
 * needs something external to the container.
 */
@Service
public class RevisionLogService {

    public record Entry(int revision, String author, String note, String loggedAt) {}

    private static final int MAX_ENTRIES = 50;
    private static final int MAX_FIELD_LENGTH = 140;
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final AtomicInteger nextRevision = new AtomicInteger(1);

    /** Newest first. */
    public List<Entry> entries() {
        return entries;
    }

    public void add(String author, String note) {
        entries.add(0, new Entry(
                nextRevision.getAndIncrement(),
                clean(author, "Anonymous"),
                clean(note, "(no comment)"),
                TIMESTAMP_FORMAT.format(Instant.now())));
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
    }

    private String clean(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.length() > MAX_FIELD_LENGTH ? trimmed.substring(0, MAX_FIELD_LENGTH) : trimmed;
    }
}



