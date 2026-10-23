package com.enrola.anna.conversation;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Hands every trace event {@link TraceLog} writes to whoever follows its conversation, in process:
 * one instance is the stated scale, and a multi-instance deployment replaces this one class with a
 * broker. A listener that throws loses that event and stops no other listener.
 */
@Component
public class TracePublisher {

  private static final Logger log = LoggerFactory.getLogger(TracePublisher.class);

  private final Map<String, List<Consumer<TraceEvent>>> listeners = new ConcurrentHashMap<>();

  /**
   * Follows one conversation; running the returned action stops following it. Both change the
   * conversation's list inside the map's own lock, so an unsubscribe that empties and drops the
   * list cannot lose a listener added at the same moment.
   */
  public Runnable subscribe(String conversationId, Consumer<TraceEvent> listener) {
    listeners.compute(
        conversationId,
        (_, following) -> {
          List<Consumer<TraceEvent>> list =
              following == null ? new CopyOnWriteArrayList<>() : following;
          list.add(listener);
          return list;
        });
    return () ->
        listeners.computeIfPresent(
            conversationId,
            (_, following) -> {
              following.remove(listener);
              return following.isEmpty() ? null : following;
            });
  }

  void publish(TraceEvent event) {
    for (Consumer<TraceEvent> listener :
        listeners.getOrDefault(event.conversationId(), List.of())) {
      try {
        listener.accept(event);
      } catch (RuntimeException e) {
        log.debug("trace listener dropped event {} of {}", event.id(), event.conversationId(), e);
      }
    }
  }

  /** How many listeners follow the conversation. */
  public int listeners(String conversationId) {
    return listeners.getOrDefault(conversationId, List.of()).size();
  }
}
