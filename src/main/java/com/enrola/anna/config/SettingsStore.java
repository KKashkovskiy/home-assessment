package com.enrola.anna.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Holds the live {@link Settings}: the yml defaults, or the overrides in {@code settings.json} in
 * the data directory laid over them. {@link #save} validates a candidate whole, the {@link
 * SettingsCheck} beans included, writes it, and swaps it in with one volatile write. A turn keeps
 * the record it read at its start and the next turn reads the new one. The {@link SettingsListener}
 * beans then hear it, which keeps the clock and the calendar in step with every save. A settings
 * file never stops the app: one that does not read or pass is set aside. Only the pipeline entries
 * ({@code ConversationService} for a start, {@code TurnPipeline} for a turn), the Configuration
 * controller and the console's shell advice, which reads it once per request, hold this store;
 * every other reader is handed the record, or the section of it that it needs.
 */
@Service
public class SettingsStore implements SmartInitializingSingleton {

  private static final Logger log = LoggerFactory.getLogger(SettingsStore.class);

  /** Its own mapper: the file keeps the record's names and ISO times whatever the web's are. */
  private static final JsonMapper JSON = JsonMapper.builder().build();

  /** A map, not a record: the file's table replaces the defaults' whole. */
  private static final String PRICE_TABLE = "priceTable";

  private final Path file;
  private final Settings defaults;
  private final List<SettingsListener> listeners;
  private final List<SettingsCheck> checks;
  private volatile Settings current;

  @Autowired
  public SettingsStore(
      SettingsDefaults defaults,
      @Value("${anna.data-dir}") Path dataDir,
      List<SettingsListener> listeners,
      List<SettingsCheck> checks) {
    this(defaults.settings(), dataDir, listeners, checks);
  }

  /**
   * Invalid {@code defaults} fail startup: the yml is under the owner's control. The listeners hear
   * the record loaded here; the checks first run on it in {@link #afterSingletonsInstantiated}.
   */
  public SettingsStore(
      Settings defaults,
      Path dataDir,
      List<SettingsListener> listeners,
      List<SettingsCheck> checks) {
    List<SettingsValidation.Problem> problems = SettingsValidation.validate(defaults);
    if (!problems.isEmpty()) {
      throw new IllegalStateException("application.yml settings are invalid: " + problems);
    }
    this.file = dataDir.resolve("settings.json");
    this.defaults = defaults;
    this.listeners = List.copyOf(listeners);
    this.checks = List.copyOf(checks);
    apply(load());
  }

  /** The record a turn reads once, at its start. */
  public Settings current() {
    return current;
  }

  /** Why a save of {@code candidate} would be refused; empty when it would not. */
  public List<SettingsValidation.Problem> problems(Settings candidate) {
    List<SettingsValidation.Problem> invalid = SettingsValidation.validate(candidate);
    return invalid.isEmpty() ? checked(candidate) : invalid;
  }

  /**
   * Validates {@code candidate} whole, writes it, swaps it in and runs the listeners. Throws {@link
   * SettingsInvalid} naming every problem, and then nothing has changed.
   */
  public synchronized Settings save(Settings candidate) {
    List<SettingsValidation.Problem> problems = problems(candidate);
    if (!problems.isEmpty()) {
      throw new SettingsInvalid(problems);
    }
    AtomicFiles.write(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(candidate));
    apply(candidate);
    log.info("settings saved to {}", file);
    return candidate;
  }

  /**
   * Saves {@code change} applied to the current record, reading and saving under one lock, so two
   * quick changes to different values both land.
   */
  public synchronized Settings update(UnaryOperator<Settings> change) {
    return save(change.apply(current));
  }

  /**
   * Runs {@code action} with the current record while no save can replace it, as activating a
   * prompt version does to check the version against the persona it will run with.
   */
  public synchronized void holding(Consumer<Settings> action) {
    action.accept(current);
  }

  /**
   * Runs the checks on the record loaded at boot once every bean is built, so after the prompt
   * versions they read are seeded, and before the server takes a request. Overrides that fail them
   * are set aside, logged, and the defaults run.
   */
  @Override
  public synchronized void afterSingletonsInstantiated() {
    List<SettingsValidation.Problem> problems = checked(current);
    if (problems.isEmpty() || current.equals(defaults)) {
      return;
    }
    log.warn("{} does not fit the prompt versions, so the defaults run: {}", file, problems);
    setAside();
    apply(defaults);
  }

  private List<SettingsValidation.Problem> checked(Settings candidate) {
    return checks.stream().flatMap(check -> check.problems(candidate).stream()).toList();
  }

  private void apply(Settings settings) {
    current = settings;
    listeners.forEach(listener -> listener.settingsChanged(settings));
  }

  /**
   * The file's values laid over the defaults when it reads and the result validates; otherwise the
   * defaults, with the file set aside as {@code settings.json.bad} so the next save starts clean.
   */
  private Settings load() {
    if (!Files.exists(file)) {
      return defaults;
    }
    Optional<Settings> overrides = read().filter(this::valid);
    if (overrides.isPresent()) {
      log.info("settings overrides loaded from {}", file);
      return overrides.get();
    }
    setAside();
    return defaults;
  }

  /**
   * The file over the defaults, record by record, so a file written by a build with fewer settings
   * keeps the values it has and takes the rest from the yml.
   */
  private Optional<Settings> read() {
    try {
      if (!(JSON.readTree(Files.readString(file)) instanceof ObjectNode overrides)) {
        log.warn("{} is not a JSON object, so the defaults run", file);
        return Optional.empty();
      }
      ObjectNode merged = JSON.valueToTree(defaults);
      overlay(merged, overrides);
      return Optional.of(JSON.treeToValue(merged, Settings.class));
    } catch (IOException | JacksonException e) {
      log.warn("{} is unreadable, so the defaults run: {}", file, e.getMessage());
      return Optional.empty();
    }
  }

  private static void overlay(ObjectNode base, ObjectNode overrides) {
    for (Map.Entry<String, JsonNode> field : overrides.properties()) {
      if (base.get(field.getKey()) instanceof ObjectNode section
          && field.getValue() instanceof ObjectNode sectionOverrides
          && !PRICE_TABLE.equals(field.getKey())) {
        overlay(section, sectionOverrides);
      } else {
        base.set(field.getKey(), field.getValue());
      }
    }
  }

  private boolean valid(Settings overrides) {
    List<SettingsValidation.Problem> problems = SettingsValidation.validate(overrides);
    if (!problems.isEmpty()) {
      log.warn("{} is invalid, so the defaults run: {}", file, problems);
    }
    return problems.isEmpty();
  }

  private void setAside() {
    Path bad = file.resolveSibling(file.getFileName() + ".bad");
    try {
      Files.move(file, bad, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      log.warn("could not rename {} to {}: {}", file, bad, e.getMessage());
    }
  }
}
