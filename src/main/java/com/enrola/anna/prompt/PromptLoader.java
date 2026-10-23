package com.enrola.anna.prompt;

import com.enrola.anna.config.PersonaProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loads the bundle {@code anna.prompt.version} names from {@code classpath:prompts/<version>/} once
 * at boot; a missing or unreadable file fails boot. It is the only bundle this process has.
 */
@Component
public class PromptLoader {

  private final PromptBundle bundle;

  public PromptLoader(
      ResourceLoader loader,
      PersonaProperties persona,
      JsonMapper json,
      @Value("${anna.prompt.version}") String version) {
    String dir = "classpath:prompts/" + version + "/";
    this.bundle =
        new PromptBundle(
            version,
            read(loader, dir + version + ".md"),
            read(loader, dir + "knowledge.md"),
            json.readTree(read(loader, dir + "agent-decision.schema.json")),
            List.copyOf(json.readTree(read(loader, dir + "tools.json")).values()),
            new Templates(loader, dir + "templates/", persona),
            UUID.randomUUID().toString());
  }

  /** The bundle new conversations start on. */
  public PromptBundle active() {
    return bundle;
  }

  /**
   * The bundle a conversation is pinned to. A version this process does not have throws rather than
   * answering with another prompt.
   */
  public PromptBundle load(String version) {
    if (!bundle.id().equals(version)) {
      throw new IllegalArgumentException("prompt version " + version + " is not loaded");
    }
    return bundle;
  }

  private static String read(ResourceLoader loader, String location) {
    try {
      return loader.getResource(location).getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("prompt file unreadable: " + location, e);
    }
  }
}
