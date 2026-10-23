package com.enrola.anna.agent;

import com.enrola.anna.config.SettingsDefaults;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One HTTP client for every OpenAI call, so the model and the moderation endpoint share its pool,
 * per-attempt timeout and retries, which are read once here and need a restart. Exists only when
 * {@code OPENAI_API_KEY} is set; the key goes into the request header and nowhere else.
 */
@Configuration
@ConditionalOnExpression(OpenAiClientConfig.WHEN_KEY_SET)
public class OpenAiClientConfig {

  /** The condition for the beans that talk to OpenAI: {@code OPENAI_API_KEY} is set. */
  public static final String WHEN_KEY_SET = "!'${OPENAI_API_KEY:}'.isBlank()";

  @Bean
  OpenAIClient openAiClient(@Value("${OPENAI_API_KEY}") String apiKey, SettingsDefaults defaults) {
    return OpenAIOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(defaults.agent().timeout())
        .maxRetries(defaults.agent().maxRetries())
        .build();
  }
}
