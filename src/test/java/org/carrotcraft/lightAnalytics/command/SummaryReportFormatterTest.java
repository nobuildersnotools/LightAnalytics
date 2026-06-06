package org.carrotcraft.lightAnalytics.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.carrotcraft.lightAnalytics.metrics.PeakPlayers;
import org.carrotcraft.lightAnalytics.metrics.PopulationChange;
import org.carrotcraft.lightAnalytics.metrics.ResourceTrends;
import org.carrotcraft.lightAnalytics.metrics.Retention;
import org.carrotcraft.lightAnalytics.metrics.SessionStats;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryReportFormatterTest {

    private final SummaryReportFormatter formatter = new SummaryReportFormatter();

    @Test
    void formatsCompactSummary() {
        SummaryReport report = new SummaryReport(
                SummaryWindow.LAST_24_HOURS,
                0,
                0,
                8,
                new PeakPlayers(12, Instant.parse("2026-06-01T12:34:00Z").toEpochMilli()),
                new PopulationChange(7.4, 6.2, 1.2, 0.193548),
                5,
                new Retention(5, 2, 0.4),
                new SessionStats(42, 1_092_000, 45_900_000, 0, 0),
                new ResourceTrends(0.123, 0.4, 0.2, 0.5,
                        268_435_456.0, 536_870_912, 1_073_741_824, 10)
        );

        assertEquals(List.of(
                "LightAnalytics summary (24h)",
                "- Current: 8 players",
                "- Peak: 12 players at 2026-06-01 12:34 UTC",
                "- Population avg: 7.4 players (+1.2, +19.4%)",
                "- New players: 5, retention: 2/5 (40.0%)",
                "- Sessions: 42 counted, avg 18m 12s, total 12h 45m",
                "- Proxy JVM: CPU avg 12.3%, peak 40.0%; heap avg 256.0 MiB, peak 512.0 MiB / 1.0 GiB"
        ), plain(formatter.format(report)));
    }

    @Test
    void formatsEmptySampleDataCleanly() {
        SummaryReport report = new SummaryReport(
                SummaryWindow.LAST_7_DAYS,
                0,
                0,
                0,
                new PeakPlayers(0, -1),
                new PopulationChange(0, 0, 0, 0),
                0,
                new Retention(0, 0, 0),
                new SessionStats(0, 0, 0, 0, 0),
                new ResourceTrends(0, 0, 0, 0, 0, 0, 0, 0)
        );

        assertEquals(List.of(
                "LightAnalytics summary (7d)",
                "- Current: 0 players",
                "- Peak: no samples",
                "- Population avg: 0.0 players (+0.0, +0.0%)",
                "- New players: 0, retention: 0/0 (0.0%)",
                "- Sessions: 0 counted, avg 0s, total 0s",
                "- Proxy JVM: no resource samples"
        ), plain(formatter.format(report)));
    }

    @Test
    void usageDocumentsPermission() {
        assertEquals(List.of(
                "Usage: /lightanalytics summary [24h|7d|30d]",
                "Permission: lightanalytics.admin"
        ), plain(formatter.usage()));
    }

    @Test
    void appliesColorToOutput() {
        SummaryReport report = new SummaryReport(
                SummaryWindow.LAST_24_HOURS,
                0,
                0,
                8,
                new PeakPlayers(12, Instant.parse("2026-06-01T12:34:00Z").toEpochMilli()),
                new PopulationChange(7.4, 6.2, 1.2, 0.193548),
                5,
                new Retention(5, 2, 0.4),
                new SessionStats(42, 1_092_000, 45_900_000, 0, 0),
                new ResourceTrends(0.123, 0.4, 0.2, 0.5,
                        268_435_456.0, 536_870_912, 1_073_741_824, 10)
        );

        List<Component> lines = formatter.format(report);

        // Header is gold and bold so it stands out from the rows.
        Component header = lines.get(0);
        assertEquals(NamedTextColor.GOLD, header.color());
        assertEquals(TextDecoration.State.TRUE, header.decoration(TextDecoration.BOLD));

        // A positive population change is rendered green; nothing should stay plain white-only.
        assertTrue(containsColor(lines.get(3), NamedTextColor.GREEN),
                "expected the positive population change to be green");
    }

    @Test
    void colorsJvmStatsByLoad() {
        // Healthy lightweight proxy: ~2-5% CPU, plenty of heap headroom -> green, never red.
        Component healthy = jvmLine(new ResourceTrends(0.02, 0.05, 0.1, 0.2,
                104_857_600.0, 209_715_200, 1_073_741_824, 10));
        assertTrue(containsColor(healthy, NamedTextColor.GREEN),
                "expected low usage to render green");
        assertFalse(containsColor(healthy, NamedTextColor.RED),
                "healthy proxy should never show red");

        // Stressed proxy: CPU peak >= 20% and heap near max -> red appears.
        Component stressed = jvmLine(new ResourceTrends(0.05, 0.40, 0.1, 0.5,
                524_288_000.0, 1_020_000_000, 1_073_741_824, 10));
        assertTrue(containsColor(stressed, NamedTextColor.RED),
                "expected high CPU peak / near-max heap to render red");
    }

    /** Builds a report with the given resource trends and returns the "Proxy JVM" line. */
    private Component jvmLine(ResourceTrends trends) {
        SummaryReport report = new SummaryReport(
                SummaryWindow.LAST_24_HOURS, 0, 0, 0,
                new PeakPlayers(0, -1),
                new PopulationChange(0, 0, 0, 0),
                0,
                new Retention(0, 0, 0),
                new SessionStats(0, 0, 0, 0, 0),
                trends);
        return formatter.format(report).get(6);
    }

    /** Flattens a component (and its children) to its visible text. */
    private static List<String> plain(List<Component> components) {
        return components.stream().map(SummaryReportFormatterTest::plain).toList();
    }

    private static String plain(Component component) {
        StringBuilder sb = new StringBuilder();
        flatten(component, sb);
        return sb.toString();
    }

    private static void flatten(Component component, StringBuilder sb) {
        if (component instanceof TextComponent text) {
            sb.append(text.content());
        }
        for (Component child : component.children()) {
            flatten(child, sb);
        }
    }

    private static boolean containsColor(Component component, NamedTextColor color) {
        if (color.equals(component.color())) {
            return true;
        }
        return component.children().stream().anyMatch(child -> containsColor(child, color));
    }
}
