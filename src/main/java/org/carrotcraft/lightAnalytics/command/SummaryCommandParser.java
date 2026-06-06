package org.carrotcraft.lightAnalytics.command;

import java.util.List;

/** Argument parser and suggestions for {@code /lightanalytics}. */
final class SummaryCommandParser {

    private static final String SUMMARY = "summary";

    private SummaryCommandParser() {
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
            return List.of(SUMMARY);
        }
        if (args.length == 1) {
            return startsWith(SUMMARY, args[0]) ? List.of(SUMMARY) : List.of();
        }
        if (args.length == 2 && SUMMARY.equalsIgnoreCase(args[0])) {
            String prefix = args[1].toLowerCase();
            return SummaryWindow.labels().stream()
                    .filter(label -> label.startsWith(prefix))
                    .toList();
        }
        return List.of();
    }

    private static boolean startsWith(String value, String prefix) {
        return value.startsWith(prefix.toLowerCase());
    }
}
