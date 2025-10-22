package com.mawai.ghgif.constant;

import lombok.Getter;

/**
 * 消息类型
 * @author mawai
 */
@Getter
public enum MessageType {

    TYPE("Type"),

    GIF_MESSAGE("gif"),
    
    EMAIL_MESSAGE("email"),

    USER_LIKES_MESSAGE("userLikes"),
    
    COMMENT_LIKES_MESSAGE("commentLikes"),
    
    COMMENT_MESSAGE("comment");

    private final String value;

    MessageType(String value) {
        this.value = value;
    }
}
