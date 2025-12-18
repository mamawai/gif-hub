package com.mawai.ghgif.config;

import cn.dev33.satoken.stp.StpUtil;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket配置
 * 用于站内通知的实时推送
 *
 * @author mawai
 * @since 2025-12-15
 */
@Slf4j
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // 启用简单消息代理,用于向客户端推送消息
        // /user 前缀用于点对点消息（通知推送）
        // /topic 前缀用于广播消息,当前系统未使用(预留)
        config.enableSimpleBroker("/user", "/topic");
        
        // 注意：移除了以下未使用的配置
        // - setApplicationDestinationPrefixes: 用于接收客户端消息，当前系统只推送不接收
        // - setUserDestinationPrefix: 默认值就是/user，无需显式设置
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // 注册STOMP端点,前端通过此端点建立WebSocket连接
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")  // 允许跨域
                .addInterceptors(new HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
                        // 从 URL 查询参数中获取 token（SockJS 不支持 HTTP header）
                        if (request instanceof ServletServerHttpRequest servletRequest) {
                            String token = servletRequest.getServletRequest().getParameter("satoken");
                            if (token != null && !token.isEmpty()) {
                                attributes.put("satoken", token);
                            } else {
                                log.warn("WebSocket 握手失败: URL 参数中未找到 satoken");
                            }
                        }
                        return true;
                    }

                    @Override
                    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                               WebSocketHandler wsHandler, Exception exception) {
                        // 握手后处理（可选）
                    }
                })
                .withSockJS();  // 启用SockJS降级支持
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // 添加拦截器,用于WebSocket连接时的身份认证
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                
                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                    // 从握手阶段存储的 attributes 中获取 token（通过 URL 参数传递）
                    String token = (String) accessor.getSessionAttributes().get("satoken");
                    
                    if (token == null || token.isEmpty()) {
                        log.warn("WebSocket连接失败: 未提供 token");
                        return null;  // 拒绝连接
                    }
                    
                    try {
                        // 验证token并获取用户ID
                        Object loginId = StpUtil.getLoginIdByToken(token);
                        
                        if (loginId != null) {
                            // 设置用户身份,用于后续的点对点消息推送
                            accessor.setUser(loginId::toString);
                            log.info("WebSocket连接成功,用户ID: {}", loginId);
                        } else {
                            log.warn("WebSocket连接失败: token无效");
                            return null;  // 拒绝连接
                        }
                    } catch (Exception e) {
                        log.error("WebSocket认证失败: {}", e.getMessage());
                        return null;  // 拒绝连接
                    }
                }
                
                return message;
            }
        });
    }
}

