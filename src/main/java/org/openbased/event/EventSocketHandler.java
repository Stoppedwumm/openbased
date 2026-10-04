package org.openbased.event;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.openbased.library.Library;
import org.openbased.library.LibraryRepository;
import org.openbased.security.AccessService;
import org.openbased.security.ApiAuthentication;
import org.openbased.security.ApiPrincipal;
import org.openbased.security.Scopes;
import org.openbased.user.User;
import org.openbased.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * {@code WS /api/v1/events}. Each subscriber only receives events about resources it may access:
 * media and scan events for visible libraries, playback and upload events for its own user, and user
 * events when it holds {@code users.read}.
 */
@Component
public class EventSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(EventSocketHandler.class);

    private final EventBus events;
    private final ObjectMapper objectMapper;
    private final UserRepository users;
    private final LibraryRepository libraries;
    private final Map<String, Subscriber> subscribers = new ConcurrentHashMap<>();
    private Runnable unsubscribe;

    public EventSocketHandler(EventBus events, ObjectMapper objectMapper, UserRepository users,
            LibraryRepository libraries) {
        this.events = events;
        this.objectMapper = objectMapper;
        this.users = users;
        this.libraries = libraries;
    }

    private record Subscriber(WebSocketSession session, ApiPrincipal principal) {
    }

    @PostConstruct
    void subscribe() {
        unsubscribe = events.subscribe(this::broadcast);
    }

    @PreDestroy
    void close() {
        if (unsubscribe != null) {
            unsubscribe.run();
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        if (!(session.getPrincipal() instanceof ApiAuthentication auth)) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        WebSocketSession decorated = new ConcurrentWebSocketSessionDecorator(session, 10_000, 1024 * 1024);
        subscribers.put(session.getId(), new Subscriber(decorated, auth.getPrincipal()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        subscribers.remove(session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // The event stream is one-way; client messages are ignored.
    }

    private void broadcast(Event event) {
        if (subscribers.isEmpty()) {
            return;
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (IOException e) {
            log.warn("Cannot serialize event {}", event.type(), e);
            return;
        }
        Library library = event.libraryId() == null ? null : libraries.findById(event.libraryId()).orElse(null);
        for (Subscriber subscriber : subscribers.values()) {
            try {
                if (canSee(subscriber.principal(), event, library)) {
                    subscriber.session().sendMessage(new TextMessage(payload));
                }
            } catch (IOException | RuntimeException e) {
                log.debug("Dropping event subscriber {}", subscriber.session().getId(), e);
                subscribers.remove(subscriber.session().getId());
            }
        }
    }

    /** Connections end when the token they were opened with expires; clients reconnect with a fresh one. */
    @Scheduled(fixedDelay = 30_000)
    void closeExpired() {
        for (Subscriber subscriber : subscribers.values()) {
            if (subscriber.principal().isExpired()) {
                subscribers.remove(subscriber.session().getId());
                try {
                    subscriber.session().close(new CloseStatus(4001, "Token expired"));
                } catch (IOException e) {
                    log.debug("Error closing event socket", e);
                }
            }
        }
    }

    private boolean canSee(ApiPrincipal principal, Event event, Library library) {
        if (principal.userId() == null || principal.isExpired()) {
            return false;
        }
        User user = users.findById(principal.userId()).filter(User::isEnabled).orElse(null);
        if (user == null) {
            return false;
        }
        if (event.userId() != null) {
            return event.userId().equals(user.getId())
                    || (user.isAdmin() && principal.scopes().contains(Scopes.SERVER_ADMIN));
        }
        if (event.type() == EventType.USER_CREATED || event.type() == EventType.USER_UPDATED) {
            return principal.scopes().contains(Scopes.USERS_READ) && user.hasPermission(Scopes.USERS_READ);
        }
        if (event.libraryId() != null) {
            if (library == null || !principal.scopes().contains(Scopes.MEDIA_READ)) {
                // Removal events for deleted libraries only reach administrators.
                return library == null && user.isAdmin();
            }
            return AccessService.canAccess(user, library);
        }
        return true;
    }
}
