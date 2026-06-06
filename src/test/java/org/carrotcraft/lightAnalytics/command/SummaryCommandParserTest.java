package org.carrotcraft.lightAnalytics.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryCommandParserTest {

    @Test
    void summaryDefaultsToTwentyFourHours() {
        assertEquals(SummaryWindow.LAST_24_HOURS, SummaryCommandParser.parseWindow(new String[]{"summary"}));
    }

    @Test
    void parsesSupportedWindows() {
        assertEquals(SummaryWindow.LAST_24_HOURS, SummaryCommandParser.parseWindow(new String[]{"summary", "24h"}));
        assertEquals(SummaryWindow.LAST_7_DAYS, SummaryCommandParser.parseWindow(new String[]{"summary", "7d"}));
        assertEquals(SummaryWindow.LAST_30_DAYS, SummaryCommandParser.parseWindow(new String[]{"summary", "30d"}));
    }

    @Test
    void rejectsUnsupportedShapesAndWindows() {
        assertNull(SummaryCommandParser.parseWindow(new String[]{}));
        assertNull(SummaryCommandParser.parseWindow(new String[]{"help"}));
        assertNull(SummaryCommandParser.parseWindow(new String[]{"summary", "1h"}));
        assertNull(SummaryCommandParser.parseWindow(new String[]{"summary", "24h", "extra"}));
    }

    @Test
    void suggestsSubcommandAndWindows() {
        assertEquals(List.of("summary", "web"), SummaryCommandParser.suggest(new String[]{}));
        assertEquals(List.of("summary"), SummaryCommandParser.suggest(new String[]{"su"}));
        assertEquals(List.of("web"), SummaryCommandParser.suggest(new String[]{"w"}));
        assertEquals(List.of(), SummaryCommandParser.suggest(new String[]{"x"}));
        assertEquals(List.of("24h"), SummaryCommandParser.suggest(new String[]{"summary", "2"}));
        assertEquals(List.of("7d"), SummaryCommandParser.suggest(new String[]{"summary", "7"}));
        assertEquals(List.of("30d"), SummaryCommandParser.suggest(new String[]{"summary", "3"}));
    }

    @Test
    void recognizesWebSubcommand() {
        assertTrue(SummaryCommandParser.isWeb(new String[]{"web"}));
        assertTrue(SummaryCommandParser.isWeb(new String[]{"WEB"}));
        assertFalse(SummaryCommandParser.isWeb(new String[]{"summary"}));
        assertFalse(SummaryCommandParser.isWeb(new String[]{"web", "extra"}));
    }
}
