package com.squarely.group;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication(scanBasePackages = {"com.squarely.group", "com.squarely.common"})
@EnableJpaRepositories(considerNestedRepositories = true)   // repos are nested in Repos.java
public class GroupApplication {
    public static void main(String[] args) {
        SpringApplication.run(GroupApplication.class, args);
    }
}
