package com.enrola.anna.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.stereotype.Component;

/**
 * Reads the {@code prompts/vN/} directories on the classpath into version rows for {@link
 * PromptStore} to seed. The directory in the repo is the record of what shipped; the table is what
 * runs. A missing or unreadable file fails the seed, and with it the boot.
 */
@Component
public class PromptLoader {

  private final ResourcePatternResolver resources;

  public PromptLoader(ResourceLoader loader) {
    this.resources = ResourcePatternUtils.getResourcePatternResolver(loader);
  }

  /** The ids with a directory under {@code prompts/}, lowest first. */
  public List<String> availableVersions() {
    Resource[] prompts;
    try {
      prompts = resources.getResources("classpath*:prompts/v*/v*.md");
    } catch (IOException e) {
      throw new IllegalStateException("prompts/ unreadable", e);
    }
    return Stream.of(prompts)
        .map(PromptLoader::directoryOf)
        .distinct()
        .sorted(Comparator.comparingInt(PromptVersion::number))
        .toList();
  }

  /**
   * The five artifacts of {@code prompts/<id>/} as an inactive row made by the repo at {@code now}.
   */
  public PromptVersion load(String id, Instant now) {
    String dir = "classpath:prompts/" + id + "/";
    Map<String, String> templates = new LinkedHashMap<>();
    for (Template template : Template.values()) {
      templates.put(template.name(), read(dir + "templates/" + template.name() + ".txt"));
    }
    return new PromptVersion(
        id,
        now,
        PromptVersion.REPO,
        "seeded from prompts/" + id,
        false,
        read(dir + id + ".md"),
        read(dir + "knowledge.md"),
        read(dir + "agent-decision.schema.json"),
        read(dir + "tools.json"),
        PromptVersion.templatesJsonOf(templates));
  }

  /** The name of the directory {@code prompt} sits in: {@code v1} for {@code prompts/v1/v1.md}. */
  private static String directoryOf(Resource prompt) {
    try {
      String path = prompt.getURL().getPath();
      String directory = path.substring(0, path.lastIndexOf('/'));
      return directory.substring(directory.lastIndexOf('/') + 1);
    } catch (IOException e) {
      throw new IllegalStateException("prompt file unreadable: " + prompt.getDescription(), e);
    }
  }

  private String read(String location) {
    Resource resource = resources.getResource(location);
    try {
      return resource.getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("prompt file unreadable: " + resource.getDescription(), e);
    }
  }
}
