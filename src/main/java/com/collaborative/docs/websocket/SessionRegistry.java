package com.collaborative.docs.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages WebSocket sessions grouped by document ID.
 *
 * When a user connects to edit document "doc-123", their WebSocket session
 * is registered under "doc-123". When an edit is broadcast, we find all
 * sessions for that document and send the update.
 *
 * CHALLENGE: Thread safety. Multiple users can connect/disconnect concurrently.
 * SOLUTION: ConcurrentHashMap + ConcurrentHashMap.newKeySet() for thread-safe sets.
 *
 * Interview talking point: "I used ConcurrentHashMap for the session registry
 * because WebSocket connections and disconnections can happen from multiple
 * Netty event loop threads simultaneously."
 */
@Slf4j
@Component
public class SessionRegistry {

    // documentId -> set of active WebSocket sessions
    private final Map<String, Set<WebSocketSession>> documentSessions = new ConcurrentHashMap<>();

    // sessionId -> documentId (reverse lookup for cleanup on disconnect)
    private final Map<String, String> sessionToDocument = new ConcurrentHashMap<>();

    public void register(String documentId, WebSocketSession session) {
        documentSessions
                .computeIfAbsent(documentId, k -> ConcurrentHashMap.newKeySet())
                .add(session);
        sessionToDocument.put(session.getId(), documentId);
        log.info("Session {} registered for document {}. Active sessions: {}",
                session.getId(), documentId, getSessionCount(documentId));
    }

    public void unregister(WebSocketSession session) {
        String documentId = sessionToDocument.remove(session.getId());
        if (documentId != null) {
            Set<WebSocketSession> sessions = documentSessions.get(documentId);
            if (sessions != null) {
                sessions.remove(session);
                if (sessions.isEmpty()) {
                    documentSessions.remove(documentId);
                }
            }
            log.info("Session {} unregistered from document {}. Active sessions: {}",
                    session.getId(), documentId, getSessionCount(documentId));
        }
    }

    public Set<WebSocketSession> getSessions(String documentId) {
        return documentSessions.getOrDefault(documentId, Collections.emptySet());
    }

    public int getSessionCount(String documentId) {
        Set<WebSocketSession> sessions = documentSessions.get(documentId);
        return sessions != null ? sessions.size() : 0;
    }

    public int getTotalSessionCount() {
        return sessionToDocument.size();
    }
}
