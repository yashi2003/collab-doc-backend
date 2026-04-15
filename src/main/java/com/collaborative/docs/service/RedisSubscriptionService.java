package com.collaborative.docs.service;

import com.collaborative.docs.dto.EditResponse;
import com.collaborative.docs.dto.RedisBroadcastMessage;
import com.collaborative.docs.websocket.CollaborativeEditHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

/**
 * Subscribes to Redis Pub/Sub channels and forwards operations to local WebSocket sessions.
 *
 * Uses a PATTERN subscription: "collab:doc:*"
 * This means we receive messages for ALL documents with a single subscription.
 *
 * LIFECYCLE:
 * - @PostConstruct: starts the subscription when the application boots
 * - @PreDestroy: cancels the subscription on shutdown
 *
 * Interview talking point: "I used a pattern subscription instead of per-document subscriptions
 * because managing dynamic subscriptions (subscribe when first user joins, unsubscribe when
 * last user leaves) adds complexity. With pattern matching, one subscription covers everything."
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisSubscriptionService {

    private static final String CHANNEL_PATTERN = "collab:doc:*";

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CollaborativeEditHandler editHandler;
    private final RedisBroadcastService broadcastService;

    private Disposable subscription;

    @PostConstruct
    public void subscribe() {
        log.info("Starting Redis Pub/Sub subscription on pattern: {}", CHANNEL_PATTERN);

        subscription = redisTemplate.listenTo(PatternTopic.of(CHANNEL_PATTERN))
                .map(ReactiveSubscription.Message::getMessage)
                .subscribe(
                        this::handleRedisMessage,
                        error -> log.error("Redis subscription error: {}", error.getMessage())
                );
    }

    @PreDestroy
    public void unsubscribe() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
            log.info("Redis Pub/Sub subscription cancelled");
        }
    }

    private void handleRedisMessage(String message) {
        try {
            RedisBroadcastMessage broadcastMsg = objectMapper.readValue(message, RedisBroadcastMessage.class);

            // CRITICAL: Skip messages from ourselves to prevent echo loops
            if (broadcastService.getNodeId().equals(broadcastMsg.getSourceNodeId())) {
                return;
            }

            log.debug("Received Redis broadcast for doc={} from node={}",
                    broadcastMsg.getDocumentId(), broadcastMsg.getSourceNodeId());

            // Convert to EditResponse and broadcast to local WebSocket sessions
            EditResponse response = EditResponse.builder()
                    .documentId(broadcastMsg.getDocumentId())
                    .userId(broadcastMsg.getUserId())
                    .sessionId(broadcastMsg.getSessionId())
                    .operation(broadcastMsg.getOperation())
                    .version(broadcastMsg.getVersion())
                    .acknowledged(false)
                    .build();

            editHandler.broadcastToAllLocalSessions(response);

        } catch (Exception e) {
            log.error("Failed to process Redis message: {}", e.getMessage());
        }
    }
}
