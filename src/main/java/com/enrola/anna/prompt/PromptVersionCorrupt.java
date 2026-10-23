package com.enrola.anna.prompt;

import java.util.List;

/**
 * The row exists but fails {@link PromptValidation} under the running configuration: edited in the
 * table by hand, or made too long by a persona change after it was saved. The active version is
 * loaded at boot and on activation, so for it this fails the boot or the activation instead.
 */
public final class PromptVersionCorrupt extends PromptUnavailable {

  public PromptVersionCorrupt(String id, List<String> errors) {
    super("prompt version " + id + " cannot be loaded: " + String.join("; ", errors));
  }
}
