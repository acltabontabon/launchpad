package com.acltabontabon.launchpad.tui.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WelcomeViewFitDetailTest {

    @Test
    void collapsesEmbeddedNewlinesIntoSpaces() {
        var hint = "git clone exited 128: Cloning into '/cache'\nfatal: unable to access '...'";
        var result = WelcomeView.fitDetail(hint, 200);
        assertFalse(result.contains("\n"), "must not retain newline");
        assertFalse(result.contains("\r"), "must not retain CR");
        assertTrue(result.startsWith("git clone exited 128:"));
        assertTrue(result.contains("fatal:"));
    }

    @Test
    void stripsAnsiCsiSequences() {
        // ESC[31mfatal:ESC[0m oops
        var hint = "[31mfatal:[0m oops";
        var result = WelcomeView.fitDetail(hint, 80);
        assertEquals("fatal: oops", result);
    }

    @Test
    void stripsLooseControlBytes() {
        var hint = "foo\rbarbaz";
        var result = WelcomeView.fitDetail(hint, 80);
        assertEquals("foo bar baz", result);
    }

    @Test
    void truncatesWithEllipsis() {
        var hint = "abcdefghijklmnop";
        var result = WelcomeView.fitDetail(hint, 5);
        assertEquals(5, result.length());
        assertTrue(result.endsWith("…"));
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertEquals("", WelcomeView.fitDetail(null, 10));
        assertEquals("", WelcomeView.fitDetail("   \n\t\r", 10));
    }
}
