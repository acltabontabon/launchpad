package com.acltabontabon.launchpad;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class LaunchpadApplication {

    public static void main(String[] args) {
        SpringApplication.run(LaunchpadApplication.class, args);
    }
}
