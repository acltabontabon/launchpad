package com.acltabontabon.launchpad.scanner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Summary of one source directory: how many files, a sample of public
 * symbol names (class / function / export). Used in place of the full
 * file list when packing the prompt context, so the model gets shape
 * instead of an opaque ls -R dump.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PackageSummary(
    String path,                  // relative dir path, e.g. "src/main/java/com/acme/users"
    int fileCount,
    List<String> sampleSymbols    // up to ~10 public class / function / export names
) {}
