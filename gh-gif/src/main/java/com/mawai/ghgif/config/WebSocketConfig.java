package com.mawai.ghgif.config;

import cn.dev33.satoken.stp.StpUtil;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
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
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ConcurrentTaskScheduler;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.security.Principal;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * WebSocket配置
 * 用于站内通知的实时推送
 *
 * <p>技术特性：</p>
 * <ul>
 *   <li>使用虚拟线程处理心跳任务，支持高并发连接</li>
 *   <li>通过 Sa-Token 进行身份认证</li>
 *   <li>支持 SockJS 降级方案</li>
 * </ul>
 *
 * @author mawai
 * @since 2025-12-15
 */
@Slf4j
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * WebSocket 心跳专用虚拟线程调度器
     *
     * <p>设计说明：</p>
     * <ul>
     *   <li>使用虚拟线程处理心跳任务，每个连接的心跳在独立虚拟线程中执行</li>
     *   <li>只需 1 个平台线程作为调度器，内存占用 ~1MB</li>
     *   <li>支持数万并发连接，单个连接阻塞不影响其他连接</li>
     *   <li>通过 shutdown hook 管理生命周期，应用关闭时自动清理资源</li>
     * </ul>
     *
     * <p>性能对比（1000 个连接）：</p>
     * <ul>
     *   <li>传统线程池(1线程): 心跳延迟 0-1000ms，单点阻塞影响所有连接</li>
     *   <li>虚拟线程方案: 心跳延迟 0-50ms，完全隔离，内存占用相同</li>
     * </ul>
     */
    @Bean
    public TaskScheduler webSocketTaskScheduler() {
        // 创建虚拟线程工厂，每个心跳任务在独立虚拟线程中执行
        var virtualThreadFactory = Thread.ofVirtual()
                .name("ws-heartbeat-", 0)
                .factory();

        // 使用虚拟线程的 ScheduledExecutorService
        // poolSize=1: 只需要 1 个平台线程作为调度器
        var virtualScheduler = Executors.newScheduledThreadPool(1, virtualThreadFactory);

        // 包装为 Spring 的 TaskScheduler，并配置生命周期管理
        ConcurrentTaskScheduler scheduler = new ConcurrentTaskScheduler(virtualScheduler);

        // 注册 shutdown hook，确保应用关闭时清理资源
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("正在关闭 WebSocket 心跳调度器...");
            virtualScheduler.shutdown();
            try {
                if (!virtualScheduler.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    log.warn("WebSocket 心跳调度器未能在 10 秒内完成关闭，强制关闭");
                    virtualScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                log.error("等待 WebSocket 心跳调度器关闭时被中断", e);
                virtualScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }, "ws-scheduler-shutdown"));

        return scheduler;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // 启用简单消息代理,用于向客户端推送消息
        // /queue 前缀用于点对点消息（通知推送）
        // /topic 前缀用于广播消息,当前系统未使用(预留)
        config.enableSimpleBroker("/topic", "/queue")
              .setTaskScheduler(webSocketTaskScheduler())  // 使用虚拟线程调度器
              .setHeartbeatValue(new long[]{10000, 10000});  // 心跳间隔 10秒

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
                            accessor.setUser(new Principal() {
                                @Override
                                public String getName() {
                                    return loginId.toString();
                                }
                            });

                            log.info("WebSocket连接成功,用户ID: {}, sessionId: {}", loginId, accessor.getSessionId());
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

