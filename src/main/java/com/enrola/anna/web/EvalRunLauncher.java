package com.enrola.anna.web;

import com.enrola.anna.config.UiProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Runs the eval suite in a child process with the command {@code evals/run.sh} runs, one run at a
 * time, and streams its output to the page over Server-Sent Events: a {@code line} event per line,
 * a {@code note} event as well for the lines worth reading (progress, warnings, errors), a {@code
 * progress} event per {@code [eval] i/n} or {@code [judge] i/m} line, and {@code finished} once the
 * process and every process it started have ended. A child keeps the harness's test-scope code and
 * its fresh database out of the console's JVM, and a crash in it cannot take the console down. The
 * last run's lines are kept, so a page loaded during or after it shows them and a stream opened
 * late replays them first. Cancel, and the app stopping, end the whole process tree.
 */
@Component
public class EvalRunLauncher implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(EvalRunLauncher.class);

  /** The lines kept of a run; the oldest go first. */
  private static final int KEPT_LINES = 500;

  private static final Pattern PROGRESS = Pattern.compile("^\\[(eval|judge)] (\\d+)/(\\d+) ");
  private static final Pattern NOTE =
      Pattern.compile("^\\[(eval|judge)] |\\b(WARN|WARNING|ERROR)\\b|Exception");
  private static final DateTimeFormatter RUN_ID =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH-mm-ss'Z'").withZone(ZoneOffset.UTC);
  private static final Duration CANCEL_GRACE = Duration.ofSeconds(5);

  /**
   * What starts a run: the harness's directory, the Maven executable that stands first (the
   * wrapper, or a stand-in in a test), and the app database the run's row goes to.
   */
  record Command(Path evalsDir, List<String> maven, String appDb) {

    Command {
      evalsDir = evalsDir.toAbsolutePath().normalize();
      maven = List.copyOf(maven);
    }

    /**
     * The command {@code run.sh} runs for the same flags, and the app's own database for the row,
     * which {@code run.sh} leaves at its default: the wrapper, the suite, the run id and database,
     * the request's properties, then the goal.
     */
    List<String> forRun(String runId, RunRequest request) {
      List<String> command = new ArrayList<>(maven);
      command.addAll(
          List.of(
              "-q",
              "-Dtest=EvalSuiteTest",
              "-Dsurefire.failIfNoSpecifiedTests=false",
              "-Deval.runId=" + runId,
              "-Deval.appDb=" + appDb));
      command.addAll(request.toArgs());
      command.add("test");
      return command;
    }

    Path repoRoot() {
      return evalsDir.getParent();
    }

    Path runs() {
      return evalsDir.resolve("runs");
    }
  }

  /** Trials or judge questions done of their total, as the latest progress line said. */
  public record Progress(String phase, int done, int total) {}

  /** How the process ended: its exit code, and whether the page cancelled it. */
  public record Ending(int exitCode, boolean cancelled) {}

  /** A run as the page shows it at one moment; {@code ending} is null while it runs. */
  public record RunState(
      String id,
      RunRequest request,
      Instant started,
      List<String> lines,
      Progress progress,
      Ending ending) {

    public boolean running() {
      return ending == null;
    }

    /** The lines worth reading: progress, warnings and errors. */
    public List<String> notes() {
      return lines.stream().filter(EvalRunLauncher::note).toList();
    }
  }

  /** What a start came to: the run under its id, or the run already going. */
  public sealed interface Launch permits Started, Busy {}

  public record Started(String runId) implements Launch {}

  public record Busy(String runId) implements Launch {}

  private final Command command;
  private final Clock clock;
  private final UiProperties ui;
  private Run last;
  private volatile boolean lifecycleRunning;

  /**
   * Run ids and start times are wall time, as {@code run.sh}'s {@code date -u} is: they name a
   * launch, and the console's fixed clock would make them collide.
   */
  @Autowired
  public EvalRunLauncher(
      @Value("${anna.evals-dir}") Path evalsDir,
      @Value("${spring.datasource.url}") String appDb,
      UiProperties ui) {
    this(
        new Command(
            evalsDir,
            List.of(evalsDir.toAbsolutePath().normalize().resolveSibling("mvnw").toString()),
            appDb),
        Clock.systemUTC(),
        ui);
  }

  EvalRunLauncher(Command command, Clock clock, UiProperties ui) {
    this.command = command;
    this.clock = clock;
    this.ui = ui;
  }

  /** Starts {@code request} under a new run id, unless a run is still going. */
  public synchronized Launch start(RunRequest request) throws IOException {
    if (last != null && last.running()) {
      return new Busy(last.id);
    }
    String runId = claimRunId();
    Process process =
        new ProcessBuilder(command.forRun(runId, request))
            .directory(command.repoRoot().toFile())
            .redirectErrorStream(true)
            .start();
    Run run = new Run(runId, request, process, clock.instant());
    last = run;
    Thread.ofVirtual().name("eval-run-" + runId).start(() -> pump(run));
    return new Started(runId);
  }

  /** Ends the running process tree; false when nothing runs. */
  public synchronized boolean cancel() {
    if (last == null || !last.running()) {
      return false;
    }
    last.cancel();
    return true;
  }

  /** The running run, or else the last one this app started. */
  public synchronized Optional<RunState> last() {
    return Optional.ofNullable(last).map(Run::state);
  }

  /**
   * A stream of the running run, its kept lines first; empty when nothing runs or the live log is
   * off, so the page reloads instead.
   */
  public Optional<SseEmitter> subscribe() {
    return subscribe(new SseEmitter(Duration.ofMinutes(ui.sseTimeoutMin()).toMillis()));
  }

  /** {@link #subscribe()} on a given emitter. */
  synchronized Optional<SseEmitter> subscribe(SseEmitter emitter) {
    if (!ui.sseEnabled() || last == null || !last.running()) {
      return Optional.empty();
    }
    return Optional.of(last.subscribe(emitter));
  }

  /** Streams open on the running run. */
  synchronized int subscribers() {
    return last == null ? 0 : last.subscribers.size();
  }

  @Override
  public void start() {
    lifecycleRunning = true;
  }

  /** A run outliving the app would spend with nobody watching, so it ends with the app. */
  @Override
  public void stop() {
    cancel();
    lifecycleRunning = false;
  }

  @Override
  public boolean isRunning() {
    return lifecycleRunning;
  }

  /** A progress line, a warning or an error: what the page shows before the full output. */
  static boolean note(String line) {
    return NOTE.matcher(line).find();
  }

  private static void pump(Run run) {
    try (BufferedReader output = run.process.inputReader(StandardCharsets.UTF_8)) {
      for (String line = output.readLine(); line != null; line = output.readLine()) {
        run.append(line);
      }
    } catch (IOException e) {
      run.append("[page] the run's output broke off: " + e.getClass().getSimpleName());
    } finally {
      int exitCode = exitCode(run.process);
      run.awaitCancelledTree();
      run.end(exitCode);
    }
  }

  private static int exitCode(Process process) {
    try {
      return process.waitFor();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      return -1;
    }
  }

  /** The run id {@code run.sh} would take now, with a counter while the folder exists. */
  private String claimRunId() throws IOException {
    Files.createDirectories(command.runs());
    String base = RUN_ID.format(clock.instant());
    for (int n = 1; ; n++) {
      String runId = n == 1 ? base : base + "-" + n;
      try {
        Files.createDirectory(command.runs().resolve(runId));
        return runId;
      } catch (FileAlreadyExistsException _) {
        // taken by an earlier run this second; the next counter
      }
    }
  }

  /** One launched run; its pump thread appends and ends it, the page reads and cancels it. */
  private static final class Run {

    private final String id;
    private final RunRequest request;
    private final Process process;
    private final Instant started;
    private final Deque<String> lines = new ArrayDeque<>();
    private final SseSubscribers subscribers = new SseSubscribers();
    private Progress progress;
    private Ending ending;

    /** The processes the run had started when it was cancelled; null until then. */
    private volatile List<ProcessHandle> cancelledTree;

    Run(String id, RunRequest request, Process process, Instant started) {
      this.id = id;
      this.request = request;
      this.process = process;
      this.started = started;
    }

    synchronized boolean running() {
      return ending == null;
    }

    synchronized RunState state() {
      return new RunState(id, request, started, List.copyOf(lines), progress, ending);
    }

    synchronized void append(String line) {
      lines.addLast(line);
      if (lines.size() > KEPT_LINES) {
        lines.removeFirst();
      }
      subscribers.sendAll("line", line);
      if (note(line)) {
        subscribers.sendAll("note", line);
      }
      Matcher matched = PROGRESS.matcher(line);
      if (matched.find()) {
        progress =
            new Progress(
                matched.group(1),
                Integer.parseInt(matched.group(2)),
                Integer.parseInt(matched.group(3)));
        subscribers.sendAll("progress", progress);
      }
    }

    synchronized void end(int exitCode) {
      ending = new Ending(exitCode, cancelledTree != null);
      subscribers.sendAll("finished", id);
      subscribers.completeAll();
    }

    /**
     * Asks every process of the run to stop, then, after {@link #CANCEL_GRACE}, kills each one
     * still alive, whatever became of the one Maven started: a forked test JVM must not outlive it.
     */
    synchronized void cancel() {
      List<ProcessHandle> tree = new ArrayList<>(process.descendants().toList());
      tree.add(process.toHandle());
      cancelledTree = List.copyOf(tree);
      tree.forEach(ProcessHandle::destroy);
      Thread.ofVirtual()
          .start(
              () -> {
                pause(CANCEL_GRACE);
                tree.stream()
                    .filter(ProcessHandle::isAlive)
                    .forEach(ProcessHandle::destroyForcibly);
              });
    }

    /**
     * A cancelled run is not over until every process it had started is gone, which the kill after
     * the grace sees to; a process the system will not end in twice the grace is logged and left.
     */
    void awaitCancelledTree() {
      List<ProcessHandle> tree = cancelledTree;
      if (tree == null) {
        return;
      }
      for (ProcessHandle handle : tree) {
        try {
          handle.onExit().get(CANCEL_GRACE.multipliedBy(2).toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        } catch (ExecutionException | TimeoutException e) {
          log.warn("eval run {}: process {} outlived its cancel", id, handle.pid());
        }
      }
    }

    /** The kept lines, their notes and the progress go first, then what follows. */
    synchronized SseEmitter subscribe(SseEmitter emitter) {
      subscribers.register(emitter, () -> {});
      for (String line : lines) {
        subscribers.send(emitter, SseEmitter.event().name("line").data(line));
        if (note(line)) {
          subscribers.send(emitter, SseEmitter.event().name("note").data(line));
        }
      }
      if (progress != null) {
        subscribers.send(emitter, SseEmitter.event().name("progress").data(progress));
      }
      return emitter;
    }

    private static void pause(Duration duration) {
      try {
        Thread.sleep(duration);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
