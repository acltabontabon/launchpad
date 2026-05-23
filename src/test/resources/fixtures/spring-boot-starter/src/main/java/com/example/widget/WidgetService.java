package com.example.widget;

public class WidgetService {

    private final String prefix;

    public WidgetService(String prefix) {
        this.prefix = prefix;
    }

    public String render(String body) {
        return prefix + ":" + body;
    }
}
