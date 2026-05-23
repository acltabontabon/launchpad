package com.example.widget;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "widget")
public record WidgetProperties(String prefix) {

    public WidgetProperties {
        if (prefix == null || prefix.isBlank()) {
            prefix = "widget";
        }
    }
}
