package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Streams released once on completion, timeout or error, a failed send ending its stream, and an
 * end releasing every stream: the bookkeeping both live logs share.
 */
class SseSubscribersTest {

  final SseSubscribers subscribers = new SseSubscribers();
  final AtomicInteger released = new AtomicInteger();

  @ParameterizedTest
  @ValueSource(strings = {"completion", "timeout", "error"})
  void aStreamIsReleasedOnceWhenItCompletesTimesOutOrFails(String end) {
    Stream stream = new Stream();
    subscribers.register(stream, released::incrementAndGet);

    stream.fire(end);
    stream.fire("completion");

    assertThat(subscribers.size()).isZero();
    assertThat(released).hasValue(1);
  }

  @Test
  void aSendThatFailsCompletesTheStreamWithTheError() {
    Stream gone = new Stream();
    gone.failSends = true;
    subscribers.register(gone, released::incrementAndGet);

    subscribers.sendAll("line", "hello");

    assertThat(gone.failure).isInstanceOf(IOException.class);
  }

  @Test
  void completeAllReleasesAndCompletesEveryStream() {
    Stream one = new Stream();
    Stream two = new Stream();
    subscribers.register(one, released::incrementAndGet);
    subscribers.register(two, released::incrementAndGet);

    subscribers.completeAll();

    assertThat(subscribers.size()).isZero();
    assertThat(released).hasValue(2);
    assertThat(one.completed && two.completed).isTrue();
  }

  /** An emitter outside a request, which keeps its callbacks so the test can fire them. */
  static final class Stream extends SseEmitter {

    private Runnable completion;
    private Runnable timeout;
    private Consumer<Throwable> error;
    boolean failSends;
    boolean completed;
    Throwable failure;

    @Override
    public synchronized void onCompletion(Runnable callback) {
      completion = callback;
    }

    @Override
    public synchronized void onTimeout(Runnable callback) {
      timeout = callback;
    }

    @Override
    public synchronized void onError(Consumer<Throwable> callback) {
      error = callback;
    }

    @Override
    public synchronized void send(SseEventBuilder builder) throws IOException {
      if (failSends) {
        throw new IOException("the tab went away");
      }
    }

    @Override
    public synchronized void complete() {
      completed = true;
    }

    @Override
    public synchronized void completeWithError(Throwable cause) {
      failure = cause;
    }

    void fire(String end) {
      switch (end) {
        case "completion" -> completion.run();
        case "timeout" -> timeout.run();
        default -> error.accept(new IOException("broken pipe"));
      }
    }
  }
}
