package com.example.widget;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(WidgetService.class)
@EnableConfigurationProperties(WidgetProperties.class)
public class WidgetAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public WidgetService widgetService(WidgetProperties properties) {
        return new WidgetService(properties.prefix());
    }
}
