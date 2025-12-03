package com.mawai.ghcommon.utils;

import cn.hutool.extra.pinyin.engine.pinyin4j.Pinyin4jEngine;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class PinYinUtils {

    private static volatile Pinyin4jEngine pinyin4jEngine;

    @PostConstruct
    public void init() {
        if (pinyin4jEngine == null) {
            synchronized (PinYinUtils.class) {
                if (pinyin4jEngine == null) {
                    pinyin4jEngine = new Pinyin4jEngine();
                }
            }
        }
    }

    public Pinyin4jEngine getPinyinEngine() {
        return pinyin4jEngine;
    }

}
