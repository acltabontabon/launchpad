package com.acltabontabon.launchpad.template;

import java.util.List;

public record AssemblyPlan(List<Section> sections) {

    public enum Section {
        INTRO,
        COMMANDS,
        ARCHITECTURE,
        ENDPOINTS,
        OPERATIONS,
        COMPANION_POINTERS,
        BOUNDARIES
    }

    private static final List<Section> STANDARD = List.of(
        Section.INTRO,
        Section.COMMANDS,
        Section.ARCHITECTURE,
        Section.ENDPOINTS,
        Section.OPERATIONS,
        Section.COMPANION_POINTERS,
        Section.BOUNDARIES
    );

    public static AssemblyPlan standard() {
        return new AssemblyPlan(STANDARD);
    }
}
