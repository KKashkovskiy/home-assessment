package com.enrola.anna.prompt;

/** No row for the id: a pinned version deleted by hand, or an activation of an unknown id. */
public final class PromptVersionMissing extends PromptUnavailable {

  public PromptVersionMissing(String id) {
    super("prompt version " + id + " does not exist");
  }
}
