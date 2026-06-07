package com.acltabontabon.launchpad.standards;

import java.util.regex.Pattern;

/**
 * Allow-list validator for the standards-pack git remote URL.
 *
 * Git accepts a number of URL forms that broaden the attack surface beyond
 * what a "clone from my org's repo" workflow needs - `ext::sh -c ...` is a
 * known RCE vector, and `file:///` lets a pasted URL exfiltrate local paths.
 * Only `https://`, `ssh://`, and the SCP-style `user@host:path` form are
 * accepted; everything else is rejected at config-save time so the bad value
 * never reaches the `git` argv.
 */
public final class RemoteStandardsUrlValidator {

    public static final int MAX_LENGTH = 2048;

    // https://host[:port]/path - host must start with an alphanumeric.
    private static final Pattern HTTPS = Pattern.compile(
        "^https://[A-Za-z0-9][A-Za-z0-9.\\-]*(:[0-9]+)?(/[^\\s]*)?$");

    // ssh://[user@]host[:port]/path
    private static final Pattern SSH = Pattern.compile(
        "^ssh://([A-Za-z0-9_.\\-]+@)?[A-Za-z0-9][A-Za-z0-9.\\-]*(:[0-9]+)?(/[^\\s]*)?$");

    // SCP-style: user@host:path (no scheme). The colon separates host and
    // path, so the path may not start with `/` followed by a digit (that
    // would be ambiguous with ssh:// port syntax in some parsers).
    private static final Pattern SCP = Pattern.compile(
        "^[A-Za-z0-9_.\\-]+@[A-Za-z0-9][A-Za-z0-9.\\-]*:[A-Za-z0-9_./\\-]+$");

    // Shell metacharacters and whitespace - git itself is fine with these
    // in a URL, but their presence is a strong smell that the value was
    // crafted to escape into a shell or confuse an argv parser downstream.
    private static final Pattern FORBIDDEN_CHARS = Pattern.compile("[\\s;|&`$<>\\\\\"']");

    private RemoteStandardsUrlValidator() {}

    /**
     * Returns null if {@code raw} is acceptable (or blank, meaning "no remote
     * configured"); otherwise returns a human-readable reason suitable for
     * the settings screen.
     */
    public static String validate(String raw) {
        if (raw == null) return null;
        var url = raw.trim();
        if (url.isEmpty()) return null;
        if (url.length() > MAX_LENGTH) {
            return "Remote URL is too long (max " + MAX_LENGTH + " characters)";
        }
        if (FORBIDDEN_CHARS.matcher(url).find()) {
            return "Remote URL contains forbidden characters (whitespace or shell metacharacters)";
        }
        var lower = url.toLowerCase();
        if (lower.startsWith("ext::")
            || lower.startsWith("file://")
            || lower.startsWith("http://")) {
            return "Remote URL scheme is not allowed - use https:// or ssh://";
        }
        if (HTTPS.matcher(url).matches()
            || SSH.matcher(url).matches()
            || SCP.matcher(url).matches()) {
            return null;
        }
        return "Remote URL must be https://, ssh://, or user@host:path form";
    }
}
