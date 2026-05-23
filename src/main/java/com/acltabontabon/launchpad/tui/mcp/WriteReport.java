package com.acltabontabon.launchpad.tui.mcp;

public record WriteReport(ClientId id, Outcome outcome, String detail) {

    public enum Outcome {
        WRITTEN,
        SKIPPED_KEY_EXISTS,
        ERROR_NOT_OBJECT,
        ERROR_IO,
        ERROR_DEV_MODE
    }
}
