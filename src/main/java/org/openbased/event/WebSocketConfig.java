package org.openbased.event;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final EventSocketHandler handler;

    public WebSocketConfig(EventSocketHandler handler) {
        this.handler = handler;
    }

    /**
     * Any origin may connect: the socket is authenticated with a bearer token, never with cookies, so a
     * foreign page cannot ride on a user's browser session.
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/v1/events").setAllowedOriginPatterns("*");
    }
}
