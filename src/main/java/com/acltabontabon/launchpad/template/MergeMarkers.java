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

    public static boolean hasMarkers(String content) {
        return content != null && content.contains(START) && content.contains(END);
    }

    /** Wraps content in markers, with a leading blank line for readability. */
    public static String wrap(String content) {
        return START + "\n" + content + (content.endsWith("\n") ? "" : "\n") + END + "\n";
    }

    /**
     * Returns existing file content with the managed block replaced by newManaged.
     * If existing has no markers, appends the marked block at the end (separated
     * by a blank line) so the next run can find it.
     */
    public static String mergeInto(String existing, String newManagedContent) {
        if (existing == null) return wrap(newManagedContent);
        if (!hasMarkers(existing)) {
            var sep = existing.endsWith("\n") ? "\n" : "\n\n";
            return existing + sep + wrap(newManagedContent);
        }
        int start = existing.indexOf(START);
        int end = existing.indexOf(END);
        if (end < start) return wrap(newManagedContent);   // malformed - replace whole file
        return existing.substring(0, start) + wrap(newManagedContent) + existing.substring(end + END.length());
    }
}
