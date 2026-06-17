package com.acltabontabon.launchpad.scanner;

/**
 * Small helper for mapping a character offset within a source string to a
 * 1-based line number. Shared by the regex-based extractors that record where
 * a declaration or annotation begins.
 */
public final class SourceLines {

    private SourceLines() {}

    /** 1-based line number of {@code offset} within {@code text}. */
    public static int lineNumberAt(String text, int offset) {
        if (text == null) return 1;
        int line = 1;
        int limit = Math.min(offset, text.length());
        for (int i = 0; i < limit; i++) {
            if (text.charAt(i) == '\n') line++;
        }
        return line;
    }
}
