package org.carrotcraft.lightAnalytics.command;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.carrotcraft.lightAnalytics.AnalyticsConfig.WebConfig;
import org.carrotcraft.lightAnalytics.metrics.MetricsService;
import org.carrotcraft.lightAnalytics.web.AuthService;
import org.slf4j.Logger;

import java.time.Clock;
import java.util.List;

/** Velocity command wrapper for the admin summary report and dashboard login. */
public final class LightAnalyticsCommand implements SimpleCommand {

    public static final String PERMISSION = "lightanalytics.admin";

    private final MetricsService metrics;
    private final Clock clock;
    private final Logger logger;
    private final AuthService auth;
    private final WebConfig webConfig;
    private final SummaryReportFormatter formatter = new SummaryReportFormatter();

    public LightAnalyticsCommand(MetricsService metrics, Clock clock, Logger logger,
                                 AuthService auth, WebConfig webConfig) {
        this.metrics = metrics;
        this.clock = clock;
        this.logger = logger;
        this.auth = auth;
        this.webConfig = webConfig;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] args = invocation.arguments();
        if (SummaryCommandParser.isWeb(args)) {
            handleWeb(invocation);
            return;
        }
        SummaryWindow window = SummaryCommandParser.parseWindow(args);
        if (window == null) {
            send(invocation, formatter.usage());
            return;
        }

        long to = clock.millis();
        long from = to - window.duration().toMillis();
        try {
            MetricsService.Summary summary = metrics.summary(from, to);
            SummaryReport report = new SummaryReport(
                    window,
                    from,
                    to,
                    summary.currentPopulation(),
                    summary.peak(),
                    summary.population(),
                    summary.newPlayers(),
                    summary.retention(),
                    summary.sessions(),
                    summary.resources()
            );
            send(invocation, formatter.format(report));
        } catch (RuntimeException e) {
            logger.error("Failed to build LightAnalytics summary", e);
            invocation.source().sendMessage(Component.text(
                    "LightAnalytics metrics are unavailable; check the proxy console.", NamedTextColor.RED));
        }
    }

    /** Issues a single-use dashboard login link to the invoker. */
    private void handleWeb(Invocation invocation) {
        if (auth == null || !webConfig.enabled()) {
            invocation.source().sendMessage(Component.text(
                    "The LightAnalytics web dashboard is disabled in config.toml.", NamedTextColor.RED));
            return;
        }
        String username = invocation.source() instanceof Player player
                ? player.getUsername()
                : "console";
        String token = auth.issueToken(username);
        String url = webConfig.resolvedBaseUrl() + "/auth?token=" + token;
        long seconds = webConfig.tokenTtl().toSeconds();

        invocation.source().sendMessage(Component.text(
                "LightAnalytics dashboard login link (valid " + seconds + "s, single use):",
                NamedTextColor.GOLD));
        invocation.source().sendMessage(Component.text(url, NamedTextColor.AQUA)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text(
                        "Click to open — expires in " + seconds + " seconds, single use"))));
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
