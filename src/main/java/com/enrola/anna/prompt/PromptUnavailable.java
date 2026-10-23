package com.enrola.anna.prompt;

/**
 * A prompt version that cannot be loaded for a turn: its row is gone, or it no longer passes {@link
 * PromptValidation}. A turn that meets one runs without the model.
 */
public abstract sealed class PromptUnavailable extends RuntimeException
    permits PromptVersionMissing, PromptVersionCorrupt {

  PromptUnavailable(String message) {
    super(message);
  }
}
