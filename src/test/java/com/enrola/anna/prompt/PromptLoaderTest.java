package com.enrola.anna.prompt;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class PromptLoaderTest {

  final PromptLoader loader = new PromptLoader(new DefaultResourceLoader());

  /**
   * The repo is the owner's to fix: a version directory with a file missing stops the boot, saying
   * which.
   */
  @Test
  void missingFileFailsNamingIt() {
    assertThatIllegalStateException()
        .isThrownBy(() -> loader.load("v99", Instant.EPOCH))
        .withMessageContaining("prompts/v99/templates/OPENER_PROVIDER.txt");
  }
}
