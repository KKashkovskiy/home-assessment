package com.enrola.anna.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Same key waits, a different key does not, waiting too long is BUSY, and failure unlocks. */
class TurnLocksTest {

  @Test
  void sameKeySerialises() throws InterruptedException {
    TurnLocks locks = new TurnLocks(Duration.ofSeconds(2));
    AtomicLong holderDone = new AtomicLong();
    Thread holder =
        holdConversation(
            locks,
            () -> {
              pause(Duration.ofMillis(300));
              holderDone.set(System.nanoTime());
            });

    long waiterStarted = locks.forConversation("x", System::nanoTime);
    holder.join();

    assertThat(waiterStarted).isGreaterThanOrEqualTo(holderDone.get());
  }

  @Test
  void differentKeysDoNotWait() throws InterruptedException {
    TurnLocks locks = new TurnLocks(Duration.ofSeconds(2));
    CountDownLatch release = new CountDownLatch(1);
    Thread holder = holdConversation(locks, () -> await(release));

    assertTimeout(Duration.ofMillis(100), () -> locks.forConversation("y", () -> "ran"));
    assertTimeout(Duration.ofMillis(100), () -> locks.forLead("x", () -> "ran"));
    release.countDown();
    holder.join();
  }

  @Test
  void waitingPastTheLimitIsBusy() throws InterruptedException {
    TurnLocks locks = new TurnLocks(Duration.ofMillis(50));
    CountDownLatch release = new CountDownLatch(1);
    Thread holder = holdConversation(locks, () -> await(release));

    assertThatThrownBy(() -> locks.forConversation("x", () -> "never"))
        .isInstanceOf(BusyException.class)
        .hasMessage("conversation x is processing a turn");
    release.countDown();
    holder.join();
  }

  /** Another thread, not this reentrant one, proves the lock was released. */
  @Test
  void failedWorkReleasesTheLock() throws Exception {
    TurnLocks locks = new TurnLocks(Duration.ofMillis(50));

    assertThatThrownBy(
            () ->
                locks.forConversation(
                    "x",
                    () -> {
                      throw new IllegalStateException("work failed");
                    }))
        .hasMessage("work failed");

    assertThat(CompletableFuture.supplyAsync(() -> locks.forConversation("x", () -> "ran")).get())
        .isEqualTo("ran");
  }

  /** Takes conversation x's lock on a new thread, runs {@code whileHeld}, returns once it holds. */
  private static Thread holdConversation(TurnLocks locks, Runnable whileHeld)
      throws InterruptedException {
    CountDownLatch acquired = new CountDownLatch(1);
    Thread holder =
        new Thread(
            () ->
                locks.forConversation(
                    "x",
                    () -> {
                      acquired.countDown();
                      whileHeld.run();
                      return null;
                    }));
    holder.start();
    acquired.await();
    return holder;
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private static void pause(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
