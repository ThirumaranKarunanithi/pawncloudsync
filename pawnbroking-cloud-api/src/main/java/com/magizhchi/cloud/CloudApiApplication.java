// build-trigger: 90d JWT TTL + glide-okhttp (forces Railway watch-paths to fire)
package com.magizhchi.cloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CloudApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(CloudApiApplication.class, args);
    }
}
