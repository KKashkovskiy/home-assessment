package com.enrola.anna.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Fan-out per conversation: a throwing listener costs no other its event, and unsubscribe sticks.
 */
class TracePublisherTest {

  static final TraceEvent EVENT =
      new TraceEvent(
          "e1",
          "c-1",
          1,
          1,
          TraceEventType.GUARD,
          "moderation",
          TraceOutcome.PASS,
          "{}",
          Instant.EPOCH);

  final TracePublisher publisher = new TracePublisher();
  final List<String> heard = new ArrayList<>();

  @Test
  void publishSurvivesAThrowingListener() {
    publisher.subscribe(
        "c-1",
        _ -> {
          throw new IllegalStateException("tab gone");
        });
    publisher.subscribe("c-1", event -> heard.add(event.id()));

    publisher.publish(EVENT);

    assertThat(heard).containsExactly("e1");
  }

  @Test
  void unsubscribeStopsDeliveryAndOtherConversationsNeverHear() {
    Runnable unsubscribe = publisher.subscribe("c-1", event -> heard.add(event.id()));
    publisher.subscribe("c-2", event -> heard.add("c-2 heard " + event.id()));

    publisher.publish(EVENT);
    unsubscribe.run();
    publisher.publish(EVENT);

    assertThat(heard).containsExactly("e1");
    assertThat(publisher.listeners("c-1")).isZero();
    assertThat(publisher.listeners("c-2")).isOne();
  }

  /**
   * An unsubscribe that empties the conversation's list races a subscribe to it: the new listener
   * must still hear what is published after its subscribe returned.
   */
  @Test
  void aListenerSubscribedDuringAnUnsubscribeStillHears() throws InterruptedException {
    AtomicInteger missed = new AtomicInteger();
    Runnable churn =
        () -> {
          for (int i = 0; i < 50_000; i++) {
            AtomicBoolean heard = new AtomicBoolean();
            Runnable unsubscribe = publisher.subscribe("c-1", _ -> heard.set(true));
            publisher.publish(EVENT);
            if (!heard.get()) {
              missed.incrementAndGet();
            }
            unsubscribe.run();
          }
        };
    Thread first = new Thread(churn);
    Thread second = new Thread(churn);

    first.start();
    second.start();
    first.join();
    second.join();

    assertThat(missed).hasValue(0);
    assertThat(publisher.listeners("c-1")).isZero();
  }
}
