package com.acltabontabon.launchpad.springboot.synthesizer;

import com.acltabontabon.launchpad.ai.FrameworkPromptStrategy;
import com.acltabontabon.launchpad.scanner.StackProfile;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Prompt routing for Spring Boot projects. Reads facets from
 * {@link StackProfile#springProfile()} and composes against the
 * {@code prompts/spring/} tree.
 */
@Component
public class SpringPromptStrategy implements FrameworkPromptStrategy {

    private static final String SLUG = "spring";

    @Override
    public boolean appliesTo(StackProfile stack) {
        return stack != null && stack.isSpring();
    }

    @Override
    public String slug() {
        return SLUG;
    }

    @Override
    public List<String> facetsFor(StackProfile stack) {
        if (stack == null || stack.springProfile() == null) return List.of();
        return stack.springProfile().facets();
    }
}
