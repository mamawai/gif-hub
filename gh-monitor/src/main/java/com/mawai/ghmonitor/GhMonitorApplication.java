package com.mawai.ghmonitor;

import de.codecentric.boot.admin.server.config.EnableAdminServer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableAdminServer
public class GhMonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(GhMonitorApplication.class, args);
    }

}
