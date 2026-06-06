package org.carrotcraft.lightAnalytics.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.carrotcraft.lightAnalytics.metrics.PeakPlayers;
import org.carrotcraft.lightAnalytics.metrics.ResourceTrends;
import org.carrotcraft.lightAnalytics.metrics.SessionStats;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Builds colored chat output for the summary command. */
public final class SummaryReportFormatter {

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.ROOT)
                    .withZone(ZoneOffset.UTC);

    public List<Component> usage() {
        return List.of(
                heading("Usage", Component.text("/lightanalytics summary [24h|7d|30d]", NamedTextColor.WHITE)),
                heading("Permission", Component.text("lightanalytics.admin", NamedTextColor.WHITE))
        );
    }

    public List<Component> format(SummaryReport report) {
        return List.of(
                Component.text("LightAnalytics summary ", NamedTextColor.GOLD, TextDecoration.BOLD)
                        .append(Component.text("(" + report.window().label() + ")", NamedTextColor.YELLOW)),
                bullet("Current", players(report.currentPopulation(), NamedTextColor.WHITE)),
                bullet("Peak", peak(report.peakPlayers())),
                bullet("Population avg", populationChange(report)),
                bullet("New players", newPlayers(report)),
                bullet("Sessions", sessions(report.sessionStats())),
                bullet("Proxy JVM", resources(report.resourceTrends()))
        );
    }

    /** A summary line: dark-gray bullet, aqua label, then the value. */
    private Component bullet(String label, Component value) {
        return Component.text()
                .append(Component.text("- ", NamedTextColor.DARK_GRAY))
                .append(Component.text(label + ": ", NamedTextColor.AQUA))
                .append(value)
                .build();
    }

    /** A "Label: value" line with no bullet, used for usage output. */
    private Component heading(String label, Component value) {
        return Component.text()
                .append(Component.text(label + ": ", NamedTextColor.AQUA))
                .append(value)
                .build();
    }

    private Component peak(PeakPlayers peak) {
        if (peak.atTimestamp() < 0) {
            return Component.text("no samples", NamedTextColor.GRAY);
        }
        return Component.text()
                .append(players(peak.peak(), NamedTextColor.YELLOW))
                .append(Component.text(" at " + TIMESTAMP.format(Instant.ofEpochMilli(peak.atTimestamp())),
                        NamedTextColor.GRAY))
                .build();
    }

    private Component populationChange(SummaryReport report) {
        return Component.text()
                .append(Component.text(decimal(report.populationChange().currentAvg()) + " players ",
                        NamedTextColor.WHITE))
                .append(Component.text("(", NamedTextColor.GRAY))
                .append(signed(signedDecimal(report.populationChange().absoluteChange()),
                        report.populationChange().absoluteChange()))
                .append(Component.text(", ", NamedTextColor.GRAY))
                .append(signed(signedPercent(report.populationChange().percentChange()),
                        report.populationChange().percentChange()))
                .append(Component.text(")", NamedTextColor.GRAY))
                .build();
    }

    private Component newPlayers(SummaryReport report) {
        return Component.text()
                .append(Component.text(String.valueOf(report.newPlayerCount()), NamedTextColor.WHITE))
                .append(Component.text(", retention: ", NamedTextColor.GRAY))
                .append(Component.text(report.retention().retainedCount() + "/" + report.retention().cohortSize(),
                        NamedTextColor.WHITE))
                .append(Component.text(" (", NamedTextColor.GRAY))
                .append(Component.text(percent(report.retention().retentionRate()),
                        retentionColor(report.retention().retentionRate())))
                .append(Component.text(")", NamedTextColor.GRAY))
                .build();
    }

    private Component sessions(SessionStats stats) {
        return Component.text()
                .append(Component.text(String.valueOf(stats.countedSessions()), NamedTextColor.WHITE))
                .append(Component.text(" counted, avg ", NamedTextColor.GRAY))
                .append(Component.text(duration((long) stats.averageDurationMillis()), NamedTextColor.WHITE))
                .append(Component.text(", total ", NamedTextColor.GRAY))
                .append(Component.text(duration(stats.totalPlaytimeMillis()), NamedTextColor.WHITE))
                .build();
    }

    private Component resources(ResourceTrends trends) {
        if (trends.sampleCount() <= 0) {
            return Component.text("no resource samples", NamedTextColor.GRAY);
        }
        long heapMax = trends.heapMax();
        return Component.text()
                .append(Component.text("CPU avg ", NamedTextColor.GRAY))
                .append(Component.text(cpu(trends.cpuProcessAvg()), cpuColor(trends.cpuProcessAvg())))
                .append(Component.text(", peak ", NamedTextColor.GRAY))
                .append(Component.text(cpu(trends.cpuProcessPeak()), cpuColor(trends.cpuProcessPeak())))
                .append(Component.text("; heap avg ", NamedTextColor.GRAY))
                .append(Component.text(bytes((long) trends.heapUsedAvg()), heapColor(trends.heapUsedAvg(), heapMax)))
                .append(Component.text(", peak ", NamedTextColor.GRAY))
                .append(Component.text(bytes(trends.heapUsedPeak()), heapColor(trends.heapUsedPeak(), heapMax)))
                .append(Component.text(" / ", NamedTextColor.GRAY))
                .append(Component.text(bytes(heapMax), NamedTextColor.GRAY))
                .build();
    }

    /** Process CPU load is 0..1; a lightweight proxy should sit in single digits. */
    private NamedTextColor cpuColor(double load) {
        if (load < 0) {
            return NamedTextColor.GRAY;
        }
        if (load < 0.10) {
            return NamedTextColor.GREEN;
        }
        if (load < 0.20) {
            return NamedTextColor.YELLOW;
        }
        return NamedTextColor.RED;
    }

    /** Heap is colored by used/max headroom rather than absolute size. */
    private NamedTextColor heapColor(double used, long max) {
        if (max <= 0 || used < 0) {
            return NamedTextColor.GRAY;
        }
        double ratio = used / max;
        if (ratio < 0.75) {
            return NamedTextColor.GREEN;
        }
        if (ratio < 0.90) {
            return NamedTextColor.YELLOW;
        }
        return NamedTextColor.RED;
    }

    private Component players(int count, NamedTextColor color) {
        return Component.text(count + (count == 1 ? " player" : " players"), color);
    }

    /** Green for gains, red for losses, gray when flat. */
    private Component signed(String text, double value) {
        NamedTextColor color = value > 0 ? NamedTextColor.GREEN
                : value < 0 ? NamedTextColor.RED
                : NamedTextColor.GRAY;
        return Component.text(text, color);
    }

    private NamedTextColor retentionColor(double rate) {
        if (rate >= 0.5) {
            return NamedTextColor.GREEN;
        }
        if (rate >= 0.25) {
            return NamedTextColor.YELLOW;
        }
        return NamedTextColor.RED;
    }

    private String cpu(double value) {
        if (value < 0) {
            return "n/a";
        }
        return decimal(value * 100.0) + "%";
    }

    private String percent(double value) {
        return decimal(value * 100.0) + "%";
    }

    private String signedPercent(double value) {
        return signedDecimal(value * 100.0) + "%";
    }

    private String signedDecimal(double value) {
        return (value >= 0 ? "+" : "") + decimal(value);
    }

    private String decimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private String bytes(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        double gib = bytes / 1_073_741_824.0;
        if (gib >= 1.0) {
            return decimal(gib) + " GiB";
        }
        double mib = bytes / 1_048_576.0;
        if (mib >= 1.0) {
            return decimal(mib) + " MiB";
        }
        double kib = bytes / 1024.0;
        if (kib >= 1.0) {
            return decimal(kib) + " KiB";
        }
        return bytes + " B";
    }

    private String duration(long millis) {
        if (millis <= 0) {
            return "0s";
        }
        long seconds = millis / 1000;
        long days = seconds / 86_400;
        seconds %= 86_400;
        long hours = seconds / 3_600;
        seconds %= 3_600;
        long minutes = seconds / 60;
        seconds %= 60;

        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }
}
