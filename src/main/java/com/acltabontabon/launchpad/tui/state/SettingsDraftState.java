package com.acltabontabon.launchpad.tui.state;

import com.acltabontabon.launchpad.ai.LlmProvider;
import com.acltabontabon.launchpad.tui.mcp.AiClient;
import com.acltabontabon.launchpad.tui.mcp.ClientId;
import com.acltabontabon.launchpad.tui.mcp.WriteReport;
import com.acltabontabon.launchpad.tui.view.SettingsMode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class SettingsDraftState {

    public volatile LlmProvider providerInput = LlmProvider.AUTO;
    public volatile String baseUrlInput = "";
    public volatile String modelInput = "";
    public volatile String apiKeyInput = "";
    public volatile String remoteStandardsUrlInput = "";
    public volatile int focusIndex = 0;
    public volatile String errorMessage = null;
    public volatile SettingsMode mode = SettingsMode.FIELDS;

    public final AtomicReference<List<AiClient>> mcpClients =
        new AtomicReference<>(new ArrayList<>());
    public final AtomicReference<Set<ClientId>> mcpSelected =
        new AtomicReference<>(new HashSet<>());
    public final AtomicReference<List<WriteReport>> mcpReports =
        new AtomicReference<>(new ArrayList<>());
    public volatile int mcpSelectionIndex = 0;
    public volatile String mcpBackupDir = null;
}
