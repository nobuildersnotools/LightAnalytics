package org.carrotcraft.lightAnalytics.command;

import java.util.List;

/** Argument parser and suggestions for {@code /lightanalytics}. */
final class SummaryCommandParser {

    private static final String SUMMARY = "summary";
    private static final String WEB = "web";
    private static final List<String> SUBCOMMANDS = List.of(SUMMARY, WEB);

    private SummaryCommandParser() {
    }

    /** True for {@code /lightanalytics web} (issue a dashboard login link). */
    static boolean isWeb(String[] args) {
        return args.length == 1 && WEB.equalsIgnoreCase(args[0]);
    }

    static SummaryWindow parseWindow(String[] args) {
        if (args.length == 1 && SUMMARY.equalsIgnoreCase(args[0])) {
            return SummaryWindow.defaultWindow();
        }
        if (args.length == 2 && SUMMARY.equalsIgnoreCase(args[0])) {
            return SummaryWindow.parse(args[1]).orElse(null);
        }
        return null;
    }

    static List<String> suggest(String[] args) {
        if (args.length == 0) {
            return SUBCOMMANDS;
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return SUBCOMMANDS.stream()
                    .filter(sub -> sub.startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && SUMMARY.equalsIgnoreCase(args[0])) {
            String prefix = args[1].toLowerCase();
            return SummaryWindow.labels().stream()
                    .filter(label -> label.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }
}
