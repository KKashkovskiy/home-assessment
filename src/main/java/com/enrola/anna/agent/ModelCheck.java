package com.enrola.anna.agent;

import com.enrola.anna.config.Settings;
import com.enrola.anna.config.SettingsDefaults;
import com.enrola.anna.config.SettingsStore;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Checks that the model the settings name answers a request shaped like a turn's, with the same
 * model, reasoning and output cap. It runs at boot, where a failure is a warning, or a failed boot
 * when {@code anna.agent.strict-model-check} is true; and again, in the background, after each save
 * that changes what a turn would send, where a failure only shows in the console. {@link #result()}
 * tells the console how the latest check went. Present only with a key, like {@link
 * OpenAiModelClient}; without one {@link AgentService} logs the skip.
 */
@Component
@ConditionalOnExpression(OpenAiClientConfig.WHEN_KEY_SET)
public class ModelCheck implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(ModelCheck.class);

  /** A check after a save runs off the request that saved, which may wait seconds otherwise. */
  private static final Executor BACKGROUND =
      task -> Thread.ofVirtual().name("model-check").start(task);

  /** How the latest check went; PENDING until it has answered. */
  public enum Result {
    PENDING,
    PASSED,
    FAILED
  }

  private final ModelClient client;
  private final boolean strict;
  private volatile ModelRequest asked;
  private volatile Result result = Result.PENDING;

  public ModelCheck(ModelClient client, SettingsStore settings, SettingsDefaults defaults) {
    this.client = client;
    this.strict = defaults.agent().strictModelCheck();
    this.asked = pingFor(settings.current());
    settings.onChange(this::recheckIfChanged);
  }

  @Override
  public void run(ApplicationArguments args) {
    ModelRequest ping = asked;
    Optional<String> failure = check(ping);
    if (failure.isPresent() && strict) {
      throw new IllegalStateException(
          "model check failed for " + ping.model() + ": " + failure.get());
    }
  }

  public Result result() {
    return result;
  }

  private static ModelRequest pingFor(Settings settings) {
    return ModelRequest.ping(settings.agent(), settings.limits().maxOutputTokens());
  }

  /**
   * A save that leaves the model, its reasoning and its output cap as they were changes nothing.
   */
  private synchronized void recheckIfChanged(Settings settings) {
    ModelRequest ping = pingFor(settings);
    if (ping.equals(asked)) {
      return;
    }
    asked = ping;
    result = Result.PENDING;
    BACKGROUND.execute(() -> check(ping));
  }

  /**
   * Asks {@code ping} and records how it went, unless a later save asked something else since; the
   * failure's reason, or empty when the model answered.
   */
  private Optional<String> check(ModelRequest ping) {
    Optional<String> failure =
        switch (client.call(ping)) {
          case ModelResponse.Decided _ -> Optional.empty();
          case ModelResponse.Failed failed -> Optional.of(failed.code().name());
          case ModelResponse.ToolCalls _ -> Optional.of("called a tool none was offered");
        };
    synchronized (this) {
      if (ping.equals(asked)) {
        result = failure.isEmpty() ? Result.PASSED : Result.FAILED;
      }
    }
    failure.ifPresentOrElse(
        why -> log.warn("model check failed for {}: {}", ping.model(), why),
        () -> log.info("model check ok: {}", ping.model()));
    return failure;
  }
}
