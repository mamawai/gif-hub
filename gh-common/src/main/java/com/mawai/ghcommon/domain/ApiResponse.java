package com.mawai.ghcommon.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用API响应对象
 * @param <T> 响应数据类型
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {
    /**
     * 状态码
     */
    private int status;

    /**
     * 响应数据
     */
    private T data;

    /**
     * 消息
     */
    private String message;

    /**
     * 创建成功响应
     * @param data 数据
     * @param <T> 数据类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .status(200)
                .data(data)
                .message("Success")
                .build();
    }

    /**
     * 创建成功响应（带消息）
     * @param data 数据
     * @param message 消息
     * @param <T> 数据类型
     * @return 成功响应
     */
    public static <T> ApiResponse<T> success(T data, String message) {
        return ApiResponse.<T>builder()
                .status(200)
                .data(data)
                .message(message)
                .build();
    }

    /**
     * 创建失败响应
     * @param status 状态码
     * @param message 错误消息
     * @param <T> 数据类型
     * @return 失败响应
     */
    public static <T> ApiResponse<T> error(int status, String message) {
        return ApiResponse.<T>builder()
                .status(status)
                .message(message)
                .build();
    }

    /**
     * 创建失败响应（带数据）
     * @param status 状态码
     * @param message 错误消息
     * @param data 数据
     * @param <T> 数据类型
     * @return 失败响应
     */
    public static <T> ApiResponse<T> error(int status, String message, T data) {
        return ApiResponse.<T>builder()
                .status(status)
                .data(data)
                .message(message)
                .build();
    }
}