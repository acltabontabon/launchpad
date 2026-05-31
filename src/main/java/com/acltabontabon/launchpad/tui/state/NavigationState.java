package com.acltabontabon.launchpad.tui.state;

import com.acltabontabon.launchpad.tui.AppState;

public class NavigationState {

    public volatile AppState.Screen currentScreen = AppState.Screen.WELCOME;
    public volatile String commandInput = "";
    public volatile int commandCursorIndex = 0;
    public volatile String welcomeFlashMessage = "";
}
