package com.collaborative.docs.config;

import com.collaborative.docs.websocket.CollaborativeEditHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.Map;

/**
 * Configures the WebSocket endpoint.
 *
 * Maps /ws/docs/** to our CollaborativeEditHandler.
 * Clients connect to: ws://host:port/ws/docs/{documentId}?userId=xxx
 *
 * Interview talking point: "In Spring WebFlux, WebSocket configuration is different
 * from Spring MVC's @EnableWebSocket. We use HandlerMapping to map URL patterns
 * to WebSocketHandler implementations."
 */
@Configuration
@RequiredArgsConstructor
public class WebSocketConfig {

    private final CollaborativeEditHandler collaborativeEditHandler;

    @Bean
    public HandlerMapping webSocketHandlerMapping() {
        Map<String, Object> urlMap = Map.of(
                "/ws/docs/**", collaborativeEditHandler
        );

        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setOrder(1);  // higher priority than default request mappings
        mapping.setUrlMap(urlMap);
        return mapping;
    }

    @Bean
    public WebSocketHandlerAdapter webSocketHandlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}
