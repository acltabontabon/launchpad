package com.acltabontabon.launchpad.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads {@code .launchpad} JSON sidecars for readiness evaluation, owning the
 * one {@link ObjectMapper} the inventory package needs.
 *
 * <p>Unlike the {@code *Store.load()} helpers - which collapse "file absent" and
 * "file present but corrupt" both into {@code Optional.empty()} - this probe
 * keeps the two apart, which is exactly the distinction the evaluator needs to
 * tell {@link ReadinessStatus#PARTIAL} from {@link ReadinessStatus#ERROR}.
 */
final class SidecarReader {

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    /** True when {@code file} is a regular file (presence only - says nothing about validity). */
    boolean exists(Path file) {
        return Files.isRegularFile(file);
    }

    /**
     * Whether a sidecar that is known to exist parses as {@code type}. Call only
     * after {@link #exists(Path)} returns true; a present-but-corrupt file returns
     * {@code false}, which the caller maps to {@link ReadinessStatus#ERROR}.
     */
    boolean isParseable(Path file, Class<?> type) {
        try {
            json.readValue(file.toFile(), type);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
