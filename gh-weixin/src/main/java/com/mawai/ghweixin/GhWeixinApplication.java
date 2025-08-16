package com.mawai.ghweixin;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@MapperScan(basePackages = "com.mawai.ghmbplus.dao")
@ComponentScan(basePackages = {"com.mawai.ghweixin", "com.mawai.ghmbplus", "com.mawai.ghcommon"})
public class GhWeixinApplication {

    public static void main(String[] args) {
        SpringApplication.run(GhWeixinApplication.class, args);
    }

}
