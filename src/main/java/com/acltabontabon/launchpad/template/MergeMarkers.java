package com.acltabontabon.launchpad.template;

/**
 * Sentinel comments wrapping Launchpad-managed content inside a file the user
 * may have hand-edited. On re-run, the contents between START and END are
 * replaced in-place; everything outside is left alone. Both markdown and
 * mdc files use HTML-comment form, which both renderers tolerate.
 */
public final class MergeMarkers {

    public static final String START = "<!-- launchpad:managed:start -->";
    public static final String END = "<!-- launchpad:managed:end -->";

    private MergeMarkers() {}

    public enum MarkerStatus {
        /** No marker of any kind. Safe to append a fresh managed block. */
        NONE,
        /** Exactly one START followed by exactly one END. Safe to replace the block. */
        VALID,
        /** Hand-edit broke the markers (reversed, duplicated, or one side missing). */
        CORRUPTED
    }

    public static boolean hasMarkers(String content) {
        return classify(content) == MarkerStatus.VALID;
    }

    /**
     * Classifies the marker state of a file. Treat anything other than VALID
     * or NONE as user-broken: do not attempt to merge.
     */
    public static MarkerStatus classify(String content) {
        if (content == null) return MarkerStatus.NONE;
        int startCount = countOccurrences(content, START);
        int endCount = countOccurrences(content, END);
        if (startCount == 0 && endCount == 0) return MarkerStatus.NONE;
        if (startCount == 1 && endCount == 1
            && content.indexOf(START) < content.indexOf(END)) {
            return MarkerStatus.VALID;
        }
        return MarkerStatus.CORRUPTED;
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = haystack.indexOf(needle, from);
            if (idx < 0) return count;
            count++;
            from = idx + needle.length();
        }
    }

    /** Wraps content in markers, with a leading blank line for readability. */
    public static String wrap(String content) {
        return START + "\n" + content + (content.endsWith("\n") ? "" : "\n") + END + "\n";
    }

    /**
     * Returns existing file content with the managed block replaced by newManaged.
     * If existing has no markers, appends the marked block at the end so the
     * next run can find it. Callers must classify the content first and route
     * corrupted files away from this method - it will throw rather than risk
     * silently discarding user-written content outside the broken block.
     */
    public static String mergeInto(String existing, String newManagedContent) {
        if (existing == null) return wrap(newManagedContent);
        var status = classify(existing);
        if (status == MarkerStatus.CORRUPTED) {
            throw new IllegalStateException(
                "Cannot merge into a file with corrupted launchpad markers; caller must resolve first");
        }
        if (status == MarkerStatus.NONE) {
            var sep = existing.endsWith("\n") ? "\n" : "\n\n";
            return existing + sep + wrap(newManagedContent);
        }
        int start = existing.indexOf(START);
        int end = existing.indexOf(END);
        return existing.substring(0, start) + wrap(newManagedContent) + existing.substring(end + END.length());
    }
}
