package org.carrotcraft.lightAnalytics.command;

import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.carrotcraft.lightAnalytics.metrics.MetricsService;
import org.slf4j.Logger;

import java.time.Clock;
import java.util.List;

/** Velocity command wrapper for the admin summary report. */
public final class LightAnalyticsCommand implements SimpleCommand {

    public static final String PERMISSION = "lightanalytics.admin";

    private final MetricsService metrics;
    private final Clock clock;
    private final Logger logger;
    private final SummaryReportFormatter formatter = new SummaryReportFormatter();

    public LightAnalyticsCommand(MetricsService metrics, Clock clock, Logger logger) {
        this.metrics = metrics;
        this.clock = clock;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        SummaryWindow window = SummaryCommandParser.parseWindow(args);
        if (window == null) {
            send(invocation, formatter.usage());
            return;
        }

        long to = clock.millis();
        long from = to - window.duration().toMillis();
        try {
            SummaryReport report = new SummaryReport(
                    window,
                    from,
                    to,
                    metrics.currentPopulation(),
                    metrics.peakPlayers(from, to),
                    metrics.populationChange(from, to),
                    metrics.newPlayerCount(from, to),
                    metrics.retention(from, to),
                    metrics.sessionStats(from, to),
                    metrics.resourceTrends(from, to)
            );
            send(invocation, formatter.format(report));
        } catch (RuntimeException e) {
            logger.error("Failed to build LightAnalytics summary", e);
            invocation.source().sendMessage(Component.text(
                    "LightAnalytics metrics are unavailable; check the proxy console.", NamedTextColor.RED));
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return SummaryCommandParser.suggest(invocation.arguments());
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERMISSION);
    }

    private void send(Invocation invocation, List<Component> lines) {
        for (Component line : lines) {
            invocation.source().sendMessage(line);
        }
    }
}
