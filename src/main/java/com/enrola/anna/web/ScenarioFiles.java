package com.enrola.anna.web;

import com.enrola.anna.config.AtomicFiles;
import com.enrola.anna.conversation.NotFoundException;
import com.enrola.anna.eval.RubricLoader;
import com.enrola.anna.eval.ScenarioLoader;
import com.enrola.anna.guard.DenyList;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The files of the eval harness the Evals page reads and writes: the scenarios, the rubrics, the
 * judge and simulator prompts, and the deny-list the guards ship with. A save is checked as the
 * harness or the app would load the file and lands in one atomic move, or is refused with every
 * problem and nothing written. A name resolves only to a file of its kind inside its directory,
 * after {@code ..} and symbolic links resolve, so no request reaches any other file.
 */
@Component
public class ScenarioFiles {

  /** A scenario id as the schema shapes it: the kind's prefix, then lowercase and digits. */
  public static final String ID = "(turn|story)-[a-z0-9-]{1,31}";

  /** The deny-list under its path in the repo; the harness reads the app's own. */
  static final String DENY_LIST = "guards/deny-list.yaml";

  private static final String RUBRIC = "rubrics/J-[A-Z-]+\\.yaml";
  private static final List<String> PROMPTS =
      List.of("prompts/judge.md", "prompts/simulator.md", "prompts/simulator-closing.md");

  /** After its id, valid as it stands but for the empty message, which the editor asks for. */
  private static final String TURN_SKELETON =
      """
      kind: turn
      title: Describe the behaviour under test
      tags: [core]
      lead: lauren
      clock: "2026-07-26T10:00:00+10:00"
      calendar: normal
      history:
        - agent: "Hi Lauren, it's Anna, Comparato's AI assistant. We've been helping a lot of Bupa members review what they've got lately. Are you looking to save money or improve your cover? Reply STOP to opt out"
      message: ""
      expect:
        goal_met: false
        conversation_ended: false
        ends_with_question: true
      """;

  /** After its id, valid as it stands but for the empty persona, which the editor asks for. */
  private static final String STORY_SKELETON =
      """
      kind: story
      title: Describe the conversation under test
      tags: [core]
      lead: lauren
      clock: "2026-07-26T10:00:00+10:00"
      calendar: normal
      persona: ""
      stop_when: [goal_met, ended, "8 turns"]
      expect:
        goal_met: true
        booking_count: 1
        every_reply:
          max_chars: 320
          one_question_max: true
      """;

  /** A kind of harness file: how a save of it is checked, and what the page says of that. */
  public enum HarnessKind {
    RUBRIC("Saved only when the rubric loads as the judges load it."),
    PROMPT("Saved as typed; an empty prompt is refused."),
    DENY_LIST("Saved only when every rule's examples match and miss as the file says.");

    private final String hint;

    HarnessKind(String hint) {
      this.hint = hint;
    }

    public String hint() {
      return hint;
    }

    static HarnessKind of(String name) {
      if (name.equals(ScenarioFiles.DENY_LIST)) {
        return HarnessKind.DENY_LIST;
      }
      return name.startsWith("rubrics/") ? RUBRIC : PROMPT;
    }

    /** Why {@code text} cannot be saved as the file {@code name}, as its loader would say. */
    List<String> problems(String name, String text) {
      return switch (this) {
        case RUBRIC ->
            RubricLoader.problem(Path.of(name).getFileName().toString(), text).stream().toList();
        case PROMPT -> text.isBlank() ? List.of("a prompt must not be empty") : List.of();
        case DENY_LIST -> DenyList.selfTest(text);
      };
    }
  }

  /** A scenario file as the list shows it; a file that does not load keeps its problems. */
  public record ScenarioRow(
      String id, String kind, String title, List<String> tags, List<String> problems) {

    /** {@code p0} or {@code p1} when a tag says so, which {@code --priority} selects on. */
    public String priority() {
      return tags.contains("p0") ? "p0" : tags.contains("p1") ? "p1" : "";
    }

    public boolean loads() {
      return problems.isEmpty();
    }
  }

  private final Path evals;
  private final Path scenarios;
  private final Path denyList;
  private final ScenarioLoader loader;

  /** {@code evalsDir} sits in the repo root, beside {@code src/}, as the harness expects. */
  public ScenarioFiles(@Value("${anna.evals-dir}") Path evalsDir) {
    this.evals = evalsDir.toAbsolutePath().normalize();
    this.scenarios = evals.resolve("scenarios");
    this.denyList = evals.getParent().resolve("src/main/resources").resolve(DENY_LIST);
    this.loader = new ScenarioLoader(evals.resolve("schema/scenario.schema.json"));
  }

  /** Every scenario file in id order, each validated, so a broken one shows with its problems. */
  public List<ScenarioRow> list() {
    try (Stream<Path> files = Files.list(scenarios)) {
      return files
          .filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
          .map(file -> file.getFileName().toString())
          .filter(name -> name.endsWith(".yaml"))
          .map(name -> name.substring(0, name.length() - ".yaml".length()))
          .sorted()
          .map(this::row)
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot list " + scenarios, e);
    }
  }

  public boolean exists(String id) {
    return id.matches(ID) && Files.exists(scenarioFile(id));
  }

  /** The scenario's file as on disk; {@link NotFoundException} for an id with no file. */
  public String text(String id) {
    if (!exists(id)) {
      throw new NotFoundException("scenario", id);
    }
    return read(scenarioFile(id));
  }

  /**
   * Validates {@code yaml} as {@code <id>.yaml} and writes it; the problems instead, if any. A
   * scenario opened as {@code original}, empty for a new one, saves over its own file only, never
   * over another scenario's.
   */
  public List<String> save(String id, String original, String yaml) {
    List<String> problems =
        !id.equals(original) && exists(id)
            ? List.of("id is another scenario's; choose another")
            : problems(id, yaml);
    if (problems.isEmpty()) {
      AtomicFiles.write(scenarioFile(id), yaml);
    }
    return problems;
  }

  /** Why {@code yaml} would not load as {@code <id>.yaml}; empty when it would. */
  public List<String> problems(String id, String yaml) {
    if (!id.matches(ID)) {
      return List.of("id must match (turn|story)-[a-z0-9-]+, up to 36 characters");
    }
    return loader.validate(yaml, id) instanceof ScenarioLoader.Invalid invalid
        ? invalid.problems()
        : List.of();
  }

  /** A new scenario of {@code kind} under the first free {@code <kind>-new-N} id, not yet saved. */
  public String skeleton(String kind) {
    String id = firstFree(n -> kind + "-new-" + n);
    return "id: "
        + id
        + "\n"
        + switch (kind) {
          case "turn" -> TURN_SKELETON;
          case "story" -> STORY_SKELETON;
          default -> throw new IllegalArgumentException("kind must be turn or story");
        };
  }

  /** The first id no file has of {@code <id>-copy}, {@code <id>-copy-2} and on. */
  public String copyId(String id) {
    return firstFree(n -> n == 1 ? id + "-copy" : id + "-copy-" + n);
  }

  /** The harness files there are, rubrics first, by the names {@link #read} takes. */
  public List<String> harnessFiles() {
    try (Stream<Path> rubrics = Files.list(evals.resolve("rubrics"))) {
      List<String> names =
          rubrics
              .filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
              .map(file -> "rubrics/" + file.getFileName())
              .filter(name -> name.matches(RUBRIC))
              .sorted()
              .toList();
      List<String> prompts =
          PROMPTS.stream().filter(name -> Files.isRegularFile(evals.resolve(name))).toList();
      return Stream.of(names, prompts, List.of(DENY_LIST)).flatMap(List::stream).toList();
    } catch (IOException e) {
      throw new UncheckedIOException("cannot list the rubrics", e);
    }
  }

  /** The harness file {@code name}; {@link NotFoundException} for a name not on the list. */
  public String read(String name) {
    return read(harnessFile(name));
  }

  /**
   * Writes {@code text}, its line breaks as the repo keeps them whatever the browser sent, to the
   * harness file {@code name} if it passes that file's check.
   */
  public List<String> saveFile(String name, String text) {
    Path file = harnessFile(name);
    String normalised = text.replace("\r\n", "\n");
    List<String> problems = HarnessKind.of(name).problems(name, normalised);
    if (problems.isEmpty()) {
      AtomicFiles.write(file, normalised);
    }
    return problems;
  }

  private String firstFree(IntFunction<String> candidate) {
    return IntStream.iterate(1, n -> n + 1)
        .mapToObj(candidate)
        .filter(id -> !exists(id))
        .findFirst()
        .orElseThrow();
  }

  private ScenarioRow row(String id) {
    String yaml = read(scenarioFile(id));
    return switch (loader.validate(yaml, id)) {
      case ScenarioLoader.Valid valid ->
          new ScenarioRow(
              id,
              valid.scenario().kind(),
              valid.scenario().title(),
              valid.scenario().tags(),
              List.of());
      case ScenarioLoader.Invalid invalid ->
          new ScenarioRow(
              id, id.startsWith("story-") ? "story" : "turn", "", List.of(), invalid.problems());
    };
  }

  private Path scenarioFile(String id) {
    return confined(scenarios, scenarios.resolve(id + ".yaml"));
  }

  /** The file a listed name stands for, existing and inside its directory. */
  private Path harnessFile(String name) {
    if (name.equals(DENY_LIST)) {
      return denyList;
    }
    if (name.matches(RUBRIC) || PROMPTS.contains(name)) {
      Path file = confined(evals, evals.resolve(name));
      if (Files.isRegularFile(file)) {
        return file;
      }
    }
    throw new NotFoundException("harness file", "of that name");
  }

  /**
   * {@code file} when it lies inside {@code dir} once {@code ..} and symbolic links resolve; a 404
   * otherwise, so a link planted in the directory reaches nothing outside it.
   */
  private static Path confined(Path dir, Path file) {
    Path normal = file.normalize();
    if (!normal.startsWith(dir) || normal.equals(dir) || !real(normal).startsWith(real(dir))) {
      throw new NotFoundException("file", "of that name");
    }
    return normal;
  }

  /** {@code path} with every link resolved; for a file not there yet, its directory's. */
  private static Path real(Path path) {
    try {
      return Files.exists(path)
          ? path.toRealPath()
          : path.getParent().toRealPath().resolve(path.getFileName());
    } catch (IOException e) {
      throw new NotFoundException("file", "of that name");
    }
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + file.getFileName(), e);
    }
  }
}
