package com.enrola.anna.web;

import com.enrola.anna.config.UiProperties;
import com.enrola.anna.conversation.ConversationRepository;
import com.enrola.anna.conversation.TraceEvent;
import com.enrola.anna.conversation.TraceEventType;
import com.enrola.anna.conversation.TracePublisher;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The inspector's live log over Server-Sent Events: one stream per browser tab, its listener
 * released when the stream completes, times out or fails. A {@code turn} event says the STATE_DIFF
 * row closed a turn; every other row is an {@code event}. Each carries the turn number and the
 * row's type and name only, never text, so the stream is as safe as the application log. With the
 * live log off the answer is 204, which tells the browser not to reconnect. Open streams end when
 * the application stops, before the server's graceful shutdown would wait out each one.
 */
@RestController
public class TraceStream implements SmartLifecycle {

  private final TracePublisher publisher;
  private final ConversationRepository conversations;
  private final UiProperties ui;
  private final SseSubscribers open = new SseSubscribers();
  private volatile boolean running;

  public TraceStream(
      TracePublisher publisher, ConversationRepository conversations, UiProperties ui) {
    this.publisher = publisher;
    this.conversations = conversations;
    this.ui = ui;
  }

  @GetMapping(
      path = ConversationsController.LIST + "/{id}/events",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public ResponseEntity<SseEmitter> subscribe(
      @PathVariable @Pattern(regexp = ConversationApi.ID) String id) {
    if (!ui.sseEnabled()) {
      return ResponseEntity.noContent().build();
    }
    if (!conversations.existsById(id)) {
      return ResponseEntity.notFound().build();
    }
    SseEmitter emitter = new SseEmitter(Duration.ofMinutes(ui.sseTimeoutMin()).toMillis());
    open.register(emitter, publisher.subscribe(id, event -> forward(emitter, event)));
    open.send(
        emitter, SseEmitter.event().comment("live log")); // sends the headers: the tab is connected
    return ResponseEntity.ok(emitter);
  }

  /** A path segment that cannot be a conversation id names no stream. */
  @ExceptionHandler(HandlerMethodValidationException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public void notFound() {}

  /** Streams still open, each until it completes, times out or fails. */
  int openStreams() {
    return open.size();
  }

  @Override
  public void start() {
    running = true;
  }

  @Override
  public void stop() {
    running = false;
    open.completeAll();
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** What a tab hears of a trace row: where it belongs and what it is, never what it says. */
  private record LiveEvent(int turn, TraceEventType type, String name) {}

  private void forward(SseEmitter emitter, TraceEvent event) {
    open.send(
        emitter,
        SseEmitter.event()
            .name(event.type() == TraceEventType.STATE_DIFF ? "turn" : "event")
            .data(new LiveEvent(event.turnNo(), event.type(), event.name())));
  }
}
