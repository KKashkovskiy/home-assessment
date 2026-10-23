package com.enrola.anna.guard;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.anna.agent.OpenAiClientConfig;
import com.enrola.anna.config.AgentProperties;
import com.enrola.anna.config.ModerationProperties;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.moderations.ModerationCreateResponse;
import com.openai.services.blocking.ModerationService;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Moderation that fails in any way never fails the turn; nothing here leaves the machine. */
class OpenAiModerationClientTest {

  static final ModerationProperties MODERATION =
      new ModerationProperties(0.5, 0.5, 0.3, Duration.ofSeconds(2), 0);

  @Test
  void unreachableEndpointIsUnavailable() {
    OpenAIClient unreachable =
        OpenAIOkHttpClient.builder().apiKey("sk-test").baseUrl("http://127.0.0.1:9").build();

    assertThat(new OpenAiModerationClient(unreachable, MODERATION).screen("you useless bot"))
        .isEqualTo(ModerationVerdict.UNAVAILABLE);
  }

  @Test
  void answerWithNoResultIsUnavailable() {
    OpenAIClient client =
        moderationAnswering(
            () ->
                ModerationCreateResponse.builder()
                    .id("modr-1")
                    .model("omni-moderation-latest")
                    .results(List.of())
                    .build());

    assertThat(new OpenAiModerationClient(client, MODERATION).screen("hello"))
        .isEqualTo(ModerationVerdict.UNAVAILABLE);
  }

  @Test
  void anyFailureOfTheCallIsUnavailable() {
    OpenAIClient client =
        moderationAnswering(
            () -> {
              throw new IllegalStateException("not the SDK's");
            });

    assertThat(new OpenAiModerationClient(client, MODERATION).screen("hello"))
        .isEqualTo(ModerationVerdict.UNAVAILABLE);
  }

  @Test
  void existsOnlyWithAKey() {
    ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withBean(
                AgentProperties.class,
                () ->
                    new AgentProperties(
                        "gpt-5.1", null, null, Duration.ofSeconds(1), 0, 16, 4, false))
            .withBean(ModerationProperties.class, () -> MODERATION)
            .withUserConfiguration(OpenAiClientConfig.class, OpenAiModerationClient.class);

    runner
        .withPropertyValues("OPENAI_API_KEY=sk-test")
        .run(context -> assertThat(context).hasSingleBean(ModerationClient.class));
    runner
        .withPropertyValues("OPENAI_API_KEY= ")
        .run(context -> assertThat(context).doesNotHaveBean(ModerationClient.class));
  }

  /**
   * A client whose moderation call returns or throws what {@code answer} does; nothing else works.
   */
  private static OpenAIClient moderationAnswering(Supplier<ModerationCreateResponse> answer) {
    ModerationService moderations =
        proxy(
            ModerationService.class,
            (self, method) -> {
              if (!method.getName().equals("create")) {
                throw new UnsupportedOperationException(method.getName());
              }
              return answer.get();
            });
    return proxy(
        OpenAIClient.class,
        (self, method) ->
            switch (method.getName()) {
              case "withOptions" -> self;
              case "moderations" -> moderations;
              default -> throw new UnsupportedOperationException(method.getName());
            });
  }

  private static <T> T proxy(Class<T> type, BiFunction<Object, Method, Object> answer) {
    return type.cast(
        Proxy.newProxyInstance(
            type.getClassLoader(),
            new Class<?>[] {type},
            (self, method, args) -> answer.apply(self, method)));
  }
}
