package org.carrotcraft.lightAnalytics.command;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** Supported time windows for the first admin summary command. */
public enum SummaryWindow {
    LAST_24_HOURS("24h", Duration.ofHours(24)),
    LAST_7_DAYS("7d", Duration.ofDays(7)),
    LAST_30_DAYS("30d", Duration.ofDays(30));

    private final String label;
    private final Duration duration;

    SummaryWindow(String label, Duration duration) {
        this.label = label;
        this.duration = duration;
    }

    public String label() {
        return label;
    }

    public Duration duration() {
        return duration;
    }

    public static SummaryWindow defaultWindow() {
        return LAST_24_HOURS;
    }

    public static Optional<SummaryWindow> parse(String raw) {
        return Arrays.stream(values())
                .filter(window -> window.label.equalsIgnoreCase(raw))
                .findFirst();
    }

    public static List<String> labels() {
        return Arrays.stream(values())
                .map(SummaryWindow::label)
                .toList();
    }
}
