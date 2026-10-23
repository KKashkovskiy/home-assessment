package com.enrola.anna.web;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Open Server-Sent Event streams of one kind, the console's live logs. Each stream is released
 * once, when it completes, times out or fails, and its release runs what was registered with it. A
 * send to a tab that went away fails, and completing the stream with that error releases it, so the
 * set never grows with tabs nobody reads.
 */
final class SseSubscribers {

  private final Map<SseEmitter, Runnable> open = new ConcurrentHashMap<>();

  /** Holds {@code emitter} open; {@code onRelease} runs once, when it is released. */
  SseEmitter register(SseEmitter emitter, Runnable onRelease) {
    open.put(emitter, onRelease);
    emitter.onCompletion(() -> release(emitter));
    emitter.onTimeout(() -> release(emitter));
    emitter.onError(_ -> release(emitter));
    return emitter;
  }

  /** Sends {@code event} to {@code emitter}; a write that fails completes it with the error. */
  void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
    try {
      emitter.send(event);
    } catch (IOException | IllegalStateException e) {
      emitter.completeWithError(e);
    }
  }

  /** Sends the event {@code name} with {@code data} to every open stream. */
  void sendAll(String name, Object data) {
    List.copyOf(open.keySet())
        .forEach(emitter -> send(emitter, SseEmitter.event().name(name).data(data)));
  }

  /** Releases and completes every open stream, as when what they follow has ended. */
  void completeAll() {
    for (SseEmitter emitter : List.copyOf(open.keySet())) {
      release(emitter);
      emitter.complete();
    }
  }

  int size() {
    return open.size();
  }

  private void release(SseEmitter emitter) {
    Runnable onRelease = open.remove(emitter);
    if (onRelease != null) {
      onRelease.run();
    }
  }
}
