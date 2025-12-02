package com.mawai.ghadmin;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"com.mawai.ghadmin", "com.mawai.ghcommon", "com.mawai.ghmbplus", "com.mawai.ghgif"})
@MapperScan(basePackages = "com.mawai.ghmbplus.dao")
public class GhAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(GhAdminApplication.class, args);
    }

}
