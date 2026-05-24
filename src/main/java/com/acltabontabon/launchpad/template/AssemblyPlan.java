package com.acltabontabon.launchpad.template;

import java.util.List;

public record AssemblyPlan(ContextTarget target, List<Section> sections) {

    public enum Section {
        INTRO,
        COMMANDS,
        ARCHITECTURE,
        ENDPOINTS,
        BUILD_PROFILES,
        COMPANION_POINTERS,
        BOUNDARIES
    }

    public static AssemblyPlan forTarget(ContextTarget target) {
        return switch (target) {
            case CLAUDE -> new AssemblyPlan(target, List.of(
                Section.INTRO,
                Section.COMMANDS,
                Section.ARCHITECTURE,
                Section.ENDPOINTS,
                Section.BUILD_PROFILES,
                Section.COMPANION_POINTERS,
                Section.BOUNDARIES
            ));
            case CURSOR -> new AssemblyPlan(target, List.of(
                Section.INTRO,
                Section.COMMANDS,
                Section.ARCHITECTURE,
                Section.ENDPOINTS,
                Section.BUILD_PROFILES,
                Section.COMPANION_POINTERS,
                Section.BOUNDARIES
            ));
        };
    }
}
