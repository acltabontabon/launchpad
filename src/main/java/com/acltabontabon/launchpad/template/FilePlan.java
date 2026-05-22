package com.acltabontabon.launchpad.template;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The user's intended write action for one generated file. Computed on review
 * entry based on whether the target path exists and whether it carries a
 * launchpad-managed block. The user can override per file in the Review screen.
 */
public final class FilePlan {

    public enum Action {
        WRITE_NEW,    // target does not exist - safe to write
        OVERWRITE,    // target exists; we are asked to replace it wholesale
        MERGE,        // target exists with markers - replace only the managed block
        SKIP          // do not write anything
    }

    public final GeneratedFile file;
    public final boolean exists;
    public final boolean hasMarkers;
    public final String existingContent;   // null when !exists
    private volatile Action action;

    public FilePlan(GeneratedFile file, boolean exists, boolean hasMarkers, String existingContent, Action action) {
        this.file = file;
        this.exists = exists;
        this.hasMarkers = hasMarkers;
        this.existingContent = existingContent;
        this.action = action;
    }

    public static FilePlan compute(GeneratedFile file, Path projectRoot) {
        var target = projectRoot.resolve(file.relativePath());
        if (!Files.isRegularFile(target)) {
            return new FilePlan(file, false, false, null, Action.WRITE_NEW);
        }
        String existing;
        try {
            existing = Files.readString(target);
        } catch (IOException e) {
            return new FilePlan(file, true, false, null, Action.SKIP);
        }
        boolean markers = MergeMarkers.hasMarkers(existing);
        // Default: merge if markers are there, else skip (don't clobber hand-edited content).
        // For non-context kinds (skills, rules) defaulting to OVERWRITE is reasonable since
        // those paths are owned by Launchpad - but err on the safe side and let user opt in.
        Action defaultAction = markers ? Action.MERGE : Action.SKIP;
        return new FilePlan(file, true, markers, existing, defaultAction);
    }

    public Action action() { return action; }

    public void setAction(Action newAction) { this.action = newAction; }

    /** What we'd actually write to disk under the current action. */
    public String resolvedContent() {
        return switch (action) {
            case WRITE_NEW, OVERWRITE -> file.content();
            case MERGE -> MergeMarkers.mergeInto(existingContent, file.content());
            case SKIP -> existingContent;   // unchanged
        };
    }

    /** Short status label rendered in the file list. */
    public String statusChip() {
        return switch (action) {
            case WRITE_NEW -> "NEW";
            case OVERWRITE -> "OVERWRITE";
            case MERGE -> "MERGE";
            case SKIP -> "SKIP";
        };
    }
}
