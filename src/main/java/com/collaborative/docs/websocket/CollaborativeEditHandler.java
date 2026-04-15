package com.collaborative.docs.websocket;

import com.collaborative.docs.dto.EditRequest;
import com.collaborative.docs.dto.EditResponse;
import com.collaborative.docs.service.DocumentService;
import com.collaborative.docs.service.RedisBroadcastService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Handles WebSocket connections for collaborative editing.
 *
 * Connection URL pattern: ws://host:port/ws/docs/{documentId}?userId=xxx
 *
 * Flow:
 * 1. Client connects -> session registered in SessionRegistry
 * 2. Client sends EditRequest JSON -> processEdit -> broadcast EditResponse to all sessions
 * 3. Client disconnects -> session unregistered
 *
 * CHALLENGE: Spring WebFlux WebSocket API is fully reactive.
 * You can't just "send a message whenever you want" — you have to merge
 * incoming messages with outgoing messages into a single Publisher pipeline.
 *
 * SOLUTION: We use a Sinks.Many (think of it as a reactive queue).
 * When we want to send a message to a client, we push it into their sink.
 * The WebSocket's outbound is subscribed to the sink's flux.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CollaborativeEditHandler implements WebSocketHandler {

    private final DocumentService documentService;
    private final RedisBroadcastService redisBroadcastService;
    private final SessionRegistry sessionRegistry;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        // Extract documentId from the URI path: /ws/docs/{documentId}
        String path = session.getHandshakeInfo().getUri().getPath();
        String documentId = extractDocumentId(path);

        // Extract userId from query params
        String query = session.getHandshakeInfo().getUri().getQuery();
        String userId = extractQueryParam(query, "userId");

        log.info("WebSocket connected: session={}, doc={}, user={}", session.getId(), documentId, userId);

        // Register this session
        sessionRegistry.register(documentId, session);

        // Create a sink for outgoing messages to this specific client
        Sinks.Many<String> outboundSink = Sinks.many().multicast().onBackpressureBuffer();

        // Handle incoming messages (edits from this client)
        Mono<Void> inbound = session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .flatMap(payload -> handleIncomingMessage(payload, session, outboundSink))
                .doOnError(e -> log.error("Error processing message from session {}: {}", session.getId(), e.getMessage()))
                .doFinally(signal -> {
                    sessionRegistry.unregister(session);
                    outboundSink.tryEmitComplete();
                    log.info("WebSocket disconnected: session={}, doc={}", session.getId(), documentId);
                })
                .then();

        // Send outgoing messages from the sink to the client
        Mono<Void> outbound = session.send(
                outboundSink.asFlux()
                        .map(session::textMessage)
        );

        // Store the sink so we can push messages to this client from anywhere
        session.getAttributes().put("outboundSink", outboundSink);
        session.getAttributes().put("documentId", documentId);
        session.getAttributes().put("userId", userId);

        // Run inbound and outbound concurrently
        return Mono.zip(inbound, outbound).then();
    }

    private Mono<Void> handleIncomingMessage(String payload, WebSocketSession session,
                                              Sinks.Many<String> senderSink) {
        try {
            EditRequest request = objectMapper.readValue(payload, EditRequest.class);

            return documentService.processEdit(request)
                    .flatMap(response -> {
                        // Send ACK to the sender
                        response.setAcknowledged(true);
                        String ackJson = toJson(response);
                        if (ackJson != null) {
                            senderSink.tryEmitNext(ackJson);
                        }

                        // Broadcast to other local sessions (same server node)
                        broadcastToLocalSessions(response, session);

                        // Publish to Redis for other server nodes
                        return redisBroadcastService.broadcast(response);
                    })
                    .onErrorResume(e -> {
                        log.error("Error processing edit: {}", e.getMessage());
                        return Mono.empty();
                    });
        } catch (JsonProcessingException e) {
            log.error("Invalid message format: {}", e.getMessage());
            return Mono.empty();
        }
    }

    /**
     * Broadcast an edit to all LOCAL sessions for a document (except the sender).
     * Remote server nodes get the edit via Redis Pub/Sub.
     */
    @SuppressWarnings("unchecked")
    public void broadcastToLocalSessions(EditResponse response, WebSocketSession excludeSession) {
        String json = toJson(EditResponse.builder()
                .documentId(response.getDocumentId())
                .userId(response.getUserId())
                .sessionId(response.getSessionId())
                .operation(response.getOperation())
                .version(response.getVersion())
                .acknowledged(false)  // not acknowledged for other users
                .build());

        if (json == null) return;

        sessionRegistry.getSessions(response.getDocumentId()).forEach(session -> {
            if (session.isOpen() && !session.getId().equals(excludeSession != null ? excludeSession.getId() : "")) {
                Sinks.Many<String> sink = (Sinks.Many<String>) session.getAttributes().get("outboundSink");
                if (sink != null) {
                    sink.tryEmitNext(json);
                }
            }
        });
    }

    /**
     * Broadcast to ALL local sessions (used when receiving from Redis — no sender to exclude).
     */
    @SuppressWarnings("unchecked")
    public void broadcastToAllLocalSessions(EditResponse response) {
        String json = toJson(response);
        if (json == null) return;

        sessionRegistry.getSessions(response.getDocumentId()).forEach(session -> {
            if (session.isOpen()) {
                Sinks.Many<String> sink = (Sinks.Many<String>) session.getAttributes().get("outboundSink");
                if (sink != null) {
                    sink.tryEmitNext(json);
                }
            }
        });
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize message: {}", e.getMessage());
            return null;
        }
    }

    private String extractDocumentId(String path) {
        // Path: /ws/docs/{documentId}
        String[] parts = path.split("/");
        return parts.length >= 4 ? parts[3] : "unknown";
    }

    private String extractQueryParam(String query, String param) {
        if (query == null) return "anonymous";
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=");
            if (kv.length == 2 && kv[0].equals(param)) {
                return kv[1];
            }
        }
        return "anonymous";
    }
}
