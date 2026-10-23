package com.enrola.anna.prompt;

import com.enrola.anna.AppClock;
import com.enrola.anna.config.PersonaProperties;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Prompt versions in the table, which {@link PromptSeed} fills from the repo at boot. One version
 * is active for new conversations; each conversation pins the id it started on and every turn loads
 * that one, so activating a version never changes a conversation in flight. Saving inserts the next
 * id and never updates; {@link #activate} is the only write to an existing row.
 */
@Service
public class PromptStore {

  private static final Logger log = LoggerFactory.getLogger(PromptStore.class);

  private final PromptVersionRepository versions;
  private final PersonaProperties persona;
  private final AppClock clock;
  private final String canary = UUID.randomUUID().toString();

  /** The five artifacts of a version to save and a note on it; the store assigns the id. */
  public record SaveRequest(
      String promptMd,
      String knowledgeMd,
      String schemaJson,
      String toolsJson,
      String templatesJson,
      String note) {}

  /** How a save came out: the new id, or the errors that refused it; warnings either way. */
  public sealed interface SaveResult {

    record Saved(String id, List<String> warnings) implements SaveResult {}

    record Rejected(List<String> errors, List<String> warnings) implements SaveResult {}
  }

  /** A version without its artifacts. */
  public record VersionSummary(
      String id, Instant createdAt, String createdBy, String note, boolean active) {}

  public PromptStore(PromptVersionRepository versions, PersonaProperties persona, AppClock clock) {
    this.versions = versions;
    this.persona = persona;
    this.clock = clock;
  }

  /** The version new conversations pin. */
  public String activeId() {
    return versions
        .findByActiveTrue()
        .map(PromptVersion::id)
        .orElseThrow(() -> new IllegalStateException("no active prompt version"));
  }

  /**
   * The artifacts of version {@code id}: {@link PromptVersionMissing} without a row, {@link
   * PromptVersionCorrupt} when the row no longer passes {@link PromptValidation}.
   */
  public PromptBundle bundle(String id) {
    PromptVersion version = versions.findById(id).orElseThrow(() -> new PromptVersionMissing(id));
    PromptVersion.Columns columns = version.parse();
    PromptValidation.Result check = PromptValidation.validate(version, columns, persona);
    if (!check.ok()) {
      throw new PromptVersionCorrupt(id, check.errors());
    }
    return new PromptBundle(
        id,
        version.promptMd(),
        version.knowledgeMd(),
        columns.schema().orElseThrow(),
        List.copyOf(columns.tools().orElseThrow().values()),
        new Templates(columns.templates().orElseThrow(), persona),
        canary);
  }

  /** Throws what {@link #bundle} would for {@code id}; returns when a turn could load it. */
  void requireLoadable(String id) {
    bundle(id);
  }

  /**
   * Validates, then inserts the next id, inactive. Two saves racing for one id: the second fails on
   * the primary key, and its caller asks to reload rather than retrying.
   */
  public SaveResult save(SaveRequest request) {
    PromptVersion version =
        new PromptVersion(
            "v" + (highestNumber() + 1),
            clock.now(),
            PromptVersion.CONSOLE,
            request.note(),
            false,
            request.promptMd(),
            request.knowledgeMd(),
            request.schemaJson(),
            request.toolsJson(),
            request.templatesJson());
    PromptValidation.Result check = PromptValidation.validate(version, persona);
    if (!check.ok()) {
      return new SaveResult.Rejected(check.errors(), check.warnings());
    }
    versions.save(version);
    log.info("prompt version {} saved", version.id());
    return new SaveResult.Saved(version.id(), check.warnings());
  }

  /**
   * Makes {@code id} the version new conversations pin. A version that cannot load is refused
   * before anything changes, so the active one stays.
   */
  public void activate(String id) {
    requireLoadable(id);
    versions.activate(id);
    log.info("prompt version {} activated", id);
  }

  /** Every version, lowest id first. */
  public List<VersionSummary> list() {
    return StreamSupport.stream(versions.findAll().spliterator(), false)
        .sorted(Comparator.comparingInt(version -> PromptVersion.number(version.id())))
        .map(
            version ->
                new VersionSummary(
                    version.id(),
                    version.createdAt(),
                    version.createdBy(),
                    version.note(),
                    version.active()))
        .toList();
  }

  private int highestNumber() {
    return list().stream().mapToInt(version -> PromptVersion.number(version.id())).max().orElse(0);
  }
}
