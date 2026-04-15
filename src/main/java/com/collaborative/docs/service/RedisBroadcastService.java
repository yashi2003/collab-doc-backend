package com.collaborative.docs.service;

import com.collaborative.docs.dto.EditResponse;
import com.collaborative.docs.dto.RedisBroadcastMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Publishes operations to Redis Pub/Sub for cross-node broadcasting.
 *
 * WHY REDIS PUB/SUB?
 * Without it, each server node only knows about its own WebSocket connections.
 * If User A is on Node 1 and User B is on Node 2, A's edits would never reach B.
 *
 * Redis Pub/Sub acts as a lightweight message bus:
 *   Node 1 publishes: "Hey, doc-123 was edited!"
 *   Node 2 subscribes: "Got it, let me forward that to my local connections for doc-123."
 *
 * CHALLENGE: Infinite echo loops.
 * If Node 1 publishes an op, Node 2 receives it and also publishes it,
 * then Node 1 receives that and publishes again... infinite loop!
 *
 * SOLUTION: Each message carries a sourceNodeId. When a node receives a message,
 * it checks: "Is this from me?" If yes, it ignores it.
 *
 * Channel naming: "collab:doc:{documentId}" — one channel per document
 * This means nodes only receive messages for documents they have active sessions for.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisBroadcastService {

    private static final String CHANNEL_PREFIX = "collab:doc:";

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.node-id}")
    private String nodeId;

    /**
     * Publish an operation to Redis so other server nodes can broadcast it.
     */
    public Mono<Void> broadcast(EditResponse response) {
        RedisBroadcastMessage message = RedisBroadcastMessage.builder()
                .documentId(response.getDocumentId())
                .userId(response.getUserId())
                .sessionId(response.getSessionId())
                .operation(response.getOperation())
                .version(response.getVersion())
                .sourceNodeId(nodeId)
                .build();

        try {
            String json = objectMapper.writeValueAsString(message);
            String channel = CHANNEL_PREFIX + response.getDocumentId();

            return redisTemplate.convertAndSend(channel, json)
                    .doOnSuccess(receivers -> log.debug(
                            "Published to Redis channel={}, receivers={}", channel, receivers))
                    .then();
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize broadcast message: {}", e.getMessage());
            return Mono.empty();
        }
    }

    public String getNodeId() {
        return nodeId;
    }
}
