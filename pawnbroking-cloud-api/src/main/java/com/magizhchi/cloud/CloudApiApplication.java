// build-trigger: 90d JWT TTL + glide-okhttp (forces Railway watch-paths to fire)
package com.magizhchi.cloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// Scheduling is on for the nightly prune (PruneService); it holds a Postgres
// advisory lock, so more than one instance is safe.
@EnableScheduling
@SpringBootApplication
public class CloudApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(CloudApiApplication.class, args);
    }
}
