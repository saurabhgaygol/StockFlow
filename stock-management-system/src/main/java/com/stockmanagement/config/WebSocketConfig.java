package com.stockmanagement.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /** Sirf notification channel allow; baaki sab subscribe/send band. */
    private static final String ALLOWED_SUBSCRIPTION = "/user/queue/notifications";

    /**
     * Optional: comma-separated origins, jaise "https://stock.satcop.com".
     * Khali chhodo to sirf same-origin allow hota hai (Spring ka default).
     */
    @Value("${app.websocket.allowed-origins:}")
    private String allowedOrigins;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // in-memory broker — single server ke liye kaafi hai, Redis/RabbitMQ ki zaroorat nahi
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user"); // per-user notification ke liye zaroori
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        StompWebSocketEndpointRegistration endpoint = registry.addEndpoint("/ws");
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            endpoint.setAllowedOriginPatterns(allowedOrigins.split("\\s*,\\s*"));
        }
        endpoint.withSockJS(); // purane browsers ke fallback ke liye
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null || accessor.getCommand() == null) {
                    return message;
                }

                StompCommand command = accessor.getCommand();

                if (StompCommand.SUBSCRIBE.equals(command)
                        && !ALLOWED_SUBSCRIPTION.equals(accessor.getDestination())) {
                    throw new MessageDeliveryException("Subscription not allowed");
                }
                if (StompCommand.SEND.equals(command)) {
                    throw new MessageDeliveryException("Sending messages is not allowed");
                }
                return message;
            }
        });
    }
}