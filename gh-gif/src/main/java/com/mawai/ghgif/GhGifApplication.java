package com.mawai.ghgif;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan(basePackages = {"com.mawai.ghgif", "com.mawai.ghcommon", "com.mawai.ghmbplus", "com.mawai.ghaws"})
@MapperScan(basePackages = "com.mawai.ghmbplus.dao")
@EnableScheduling
public class GhGifApplication {

    public static void main(String[] args) {
        SpringApplication.run(GhGifApplication.class, args);
    }

}
