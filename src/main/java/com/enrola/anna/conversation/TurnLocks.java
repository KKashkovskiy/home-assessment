package com.enrola.anna.conversation;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Serialises turns per conversation and starts and opt-outs per lead, in this process. A second
 * caller waits up to {@code anna.turns.lock-wait}, then gets {@link BusyException} and nothing
 * runs. Callers check the id exists first, so the map holds one small lock per real conversation
 * and lead locked since boot. ponytail: in-process and never evicted; a database advisory lock
 * replaces it when a second instance exists.
 */
@Component
public class TurnLocks {

  private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();
  private final Duration wait;

  public TurnLocks(@Value("${anna.turns.lock-wait}") Duration wait) {
    this.wait = wait;
  }

  public <T> T forConversation(String conversationId, Supplier<T> work) {
    return withLock(
        "conversation:" + conversationId, work, () -> BusyException.conversation(conversationId));
  }

  public <T> T forLead(String leadId, Supplier<T> work) {
    return withLock("lead:" + leadId, work, () -> BusyException.lead(leadId));
  }

  private <T> T withLock(String key, Supplier<T> work, Supplier<BusyException> busy) {
    ReentrantLock lock = locks.computeIfAbsent(key, _ -> new ReentrantLock(true));
    try {
      if (!lock.tryLock(wait.toMillis(), TimeUnit.MILLISECONDS)) {
        throw busy.get();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw busy.get();
    }
    try {
      return work.get();
    } finally {
      lock.unlock();
    }
  }
}
