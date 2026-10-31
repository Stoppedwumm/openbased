package org.openbased.event;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * In-process event bus. Events published inside a transaction are delivered after it commits.
 */
@Component
public class EventBus {

    private static final Logger log = LoggerFactory.getLogger(EventBus.class);

    private final List<Consumer<Event>> subscribers = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "openbased-events");
        t.setDaemon(true);
        return t;
    });

    public Runnable subscribe(Consumer<Event> subscriber) {
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }

    public void publish(EventType type, Map<String, Object> data) {
        publish(new Event(type, Instant.now(), data, null, null));
    }

    public void publishForLibrary(EventType type, String libraryId, Map<String, Object> data) {
        publish(new Event(type, Instant.now(), data, libraryId, null));
    }

    public void publishForUser(EventType type, String userId, Map<String, Object> data) {
        publish(new Event(type, Instant.now(), data, null, userId));
    }

    public void publish(Event event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(event);
                }
            });
        } else {
            dispatch(event);
        }
    }

    private void dispatch(Event event) {
        executor.execute(() -> {
            for (Consumer<Event> subscriber : subscribers) {
                try {
                    subscriber.accept(event);
                } catch (RuntimeException e) {
                    log.warn("Event subscriber failed for {}", event.type(), e);
                }
            }
        });
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
