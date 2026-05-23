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
        SKIP,         // do not write anything
        CORRUPTED,    // markers are broken (reversed/duplicate/half-missing); requires explicit override
        UNREADABLE    // target exists but could not be read (permissions, lock, I/O error); never writes
    }

    public final GeneratedFile file;
    public final boolean exists;
    public final boolean hasMarkers;
    public final String existingContent;   // null when !exists or UNREADABLE
    public final String errorMessage;      // populated only for UNREADABLE; null otherwise
    private volatile Action action;

    public FilePlan(GeneratedFile file, boolean exists, boolean hasMarkers, String existingContent, Action action) {
        this(file, exists, hasMarkers, existingContent, action, null);
    }

    public FilePlan(GeneratedFile file, boolean exists, boolean hasMarkers,
                    String existingContent, Action action, String errorMessage) {
        this.file = file;
        this.exists = exists;
        this.hasMarkers = hasMarkers;
        this.existingContent = existingContent;
        this.action = action;
        this.errorMessage = errorMessage;
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
            // Distinct from SKIP: the file exists but we cannot inspect it, so
            // we cannot decide whether overwriting would clobber user content.
            // Surface the cause and refuse to write until the user resolves it.
            var msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new FilePlan(file, true, false, null, Action.UNREADABLE, msg);
        }
        var status = MergeMarkers.classify(existing);
        Action defaultAction = switch (status) {
            case VALID -> Action.MERGE;
            case CORRUPTED -> Action.CORRUPTED;
            case NONE -> Action.SKIP;   // don't clobber hand-edited content
        };
        return new FilePlan(file, true, status == MergeMarkers.MarkerStatus.VALID, existing, defaultAction);
    }

    public Action action() { return action; }

    public void setAction(Action newAction) { this.action = newAction; }

    /** What we'd actually write to disk under the current action. */
    public String resolvedContent() {
        return switch (action) {
            case WRITE_NEW, OVERWRITE -> file.content();
            case MERGE -> MergeMarkers.mergeInto(existingContent, file.content());
            case SKIP, CORRUPTED, UNREADABLE -> existingContent;   // unchanged
        };
    }

    /** Short status label rendered in the file list. */
    public String statusChip() {
        return switch (action) {
            case WRITE_NEW -> "NEW";
            case OVERWRITE -> "OVERWRITE";
            case MERGE -> "MERGE";
            case SKIP -> "SKIP";
            case CORRUPTED -> "CORRUPTED";
            case UNREADABLE -> "UNREADABLE";
        };
    }
}
