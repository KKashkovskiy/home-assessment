package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.enrola.anna.config.UiProperties;
import com.enrola.anna.web.EvalRunLauncher.Busy;
import com.enrola.anna.web.EvalRunLauncher.Command;
import com.enrola.anna.web.EvalRunLauncher.Launch;
import com.enrola.anna.web.EvalRunLauncher.Progress;
import com.enrola.anna.web.EvalRunLauncher.RunState;
import com.enrola.anna.web.EvalRunLauncher.Started;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * The launcher over a shell script standing in for Maven, so the plumbing is proved without a
 * minutes-long build: lines, progress and the end reach a stream, a late stream gets the kept lines
 * first, one run at a time, cancel ends the process, and the command is the one {@code run.sh} runs
 * for the same flags.
 */
class EvalRunLauncherTest {

  static final Instant NOW = Instant.parse("2026-10-24T01:02:03Z");

  static final String APP_DB = "jdbc:h2:file:/srv/anna/data/anna;AUTO_SERVER=TRUE";

  @TempDir Path repo;
  EvalRunLauncher launcher;

  @AfterEach
  void nothingLeftRunning() {
    if (launcher != null) {
      launcher.cancel();
    }
  }

  @Test
  void streamsLinesAndProgressThenEndsAndKeepsTheRun() throws IOException {
    launcher =
        launcher(
            "sleep 1; echo '[eval] 1/2 turn-a PASS'; echo noise; echo '[eval] 2/2 turn-b FAIL';"
                + " echo '[judge] 1/1 turn-a#1 J-X PASS'");
    Started started = (Started) launcher.start(offline());
    Events events = new Events();
    launcher.subscribe(events);

    RunState state = finished();

    assertThat(state.id()).isEqualTo(started.runId()).isEqualTo("2026-10-24T01-02-03Z");
    assertThat(state.lines()).hasSize(4).contains("noise");
    assertThat(state.notes()).hasSize(3).doesNotContain("noise");
    assertThat(state.progress()).isEqualTo(new Progress("judge", 1, 1));
    assertThat(state.ending()).isEqualTo(new EvalRunLauncher.Ending(0, false));
    assertThat(events.names())
        .containsExactly(
            "line",
            "note",
            "progress",
            "line",
            "line",
            "note",
            "progress",
            "line",
            "note",
            "progress",
            "finished");
    assertThat(events.text()).contains("\"phase\":\"eval\",\"done\":2,\"total\":2");
    assertThat(launcher.subscribers()).isZero();
  }

  @Test
  void aLateStreamGetsTheKeptLinesFirst() throws IOException {
    launcher = launcher("echo early; sleep 2; echo late");
    launcher.start(offline());
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> launcher.last().orElseThrow().lines().contains("early"));
    Events events = new Events();

    launcher.subscribe(events);
    finished();

    assertThat(events.text()).containsSubsequence("data:early", "data:late", "data:");
    assertThat(events.names()).containsExactly("line", "line", "finished");
  }

  @Test
  void aSecondStartWhileOneRunsIsBusyWithTheRunningId() throws IOException {
    launcher = launcher("sleep 5");
    Started first = (Started) launcher.start(offline());

    Launch second = launcher.start(offline());

    assertThat(second).isEqualTo(new Busy(first.runId()));
  }

  /**
   * A child that ignores the polite stop, as a forked test JVM may, is killed after the grace
   * although the process Maven started has long gone, and the run ends only once it is.
   */
  @Test
  void cancelEndsEveryProcessOfTheRunBeforeTheRunEnds() throws IOException {
    Path pid = repo.resolve("child.pid");
    launcher = launcher("sh -c 'trap \"\" TERM; sleep 30' & echo $! > " + pid + "; wait");
    launcher.start(offline());
    await().atMost(Duration.ofSeconds(5)).until(() -> Files.exists(pid) && Files.size(pid) > 0);
    ProcessHandle child =
        ProcessHandle.of(Long.parseLong(Files.readString(pid).strip())).orElseThrow();

    assertThat(launcher.cancel()).isTrue();

    RunState state = finished();
    try {
      assertThat(child.isAlive()).isFalse();
      assertThat(state.ending().cancelled()).isTrue();
      assertThat(state.ending().exitCode()).isNotZero();
      assertThat(launcher.cancel()).isFalse();
    } finally {
      child.destroyForcibly();
    }
  }

  @Test
  void aRunInTheSameSecondTakesTheNextCounter() throws IOException {
    launcher = launcher("true");
    launcher.start(offline());
    finished();

    Started again = (Started) launcher.start(offline());

    assertThat(again.runId()).isEqualTo("2026-10-24T01-02-03Z-2");
    assertThat(repo.resolve("evals/runs/2026-10-24T01-02-03Z-2")).isDirectory();
  }

  /** Each request against the flags run.sh is given for it, through run.sh and a fake mvnw. */
  @ParameterizedTest(name = "{1}")
  @MethodSource("requestsAndTheirFlags")
  void commandIsTheOneRunShRunsForTheSameFlagsAndTheAppsDatabase(RunRequest request, String flags)
      throws IOException, InterruptedException {
    List<String> expected = new ArrayList<>(runShWithFakeMaven(flags.split(" ")));
    String runId = expected.get(3).substring("-Deval.runId=".length());
    expected.add(4, "-Deval.appDb=" + APP_DB);

    assertThat(new Command(repo.resolve("evals"), List.of(), APP_DB).forRun(runId, request))
        .containsExactlyElementsOf(expected);
  }

  static Stream<Arguments> requestsAndTheirFlags() {
    return Stream.of(
        Arguments.of(RunRequest.PRESETS.get("core-offline"), "--tag core --judges none --offline"),
        Arguments.of(RunRequest.PRESETS.get("core-key"), "--tag core --judges none --budget 3"),
        Arguments.of(RunRequest.PRESETS.get("full-judges"), "--budget 10"),
        Arguments.of(
            RunRequest.PRESETS.get("stories-k3"),
            "--kind story --trials 3 --judges none --budget 8"),
        Arguments.of(
            new RunRequest(
                List.of("turn-a", "turn-b"),
                "turn-abuse-*",
                "",
                "turn",
                "p0",
                1,
                0.3,
                "advisory",
                false,
                new BigDecimal("2.50"),
                true),
            "--scenario turn-a,turn-b --filter turn-abuse-* --kind turn --priority p0"
                + " --validity-threshold 0.3 --budget 2.50 --dry-run"));
  }

  /** What run.sh hands Maven, one argument a line, with a stand-in that prints its arguments. */
  private List<String> runShWithFakeMaven(String... flags)
      throws IOException, InterruptedException {
    Path script = Files.createDirectories(repo.resolve("evals")).resolve("run.sh");
    Files.copy(Path.of("evals/run.sh"), script);
    Path mvnw = repo.resolve("mvnw");
    Files.writeString(mvnw, "#!/bin/sh\nprintf '%s\\n' \"$@\"\n");
    Files.setPosixFilePermissions(mvnw, PosixFilePermissions.fromString("rwxr-xr-x"));
    List<String> command = new ArrayList<>(List.of("sh", script.toString()));
    command.addAll(Arrays.asList(flags));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor()).isZero();
    return output.lines().filter(line -> !line.startsWith("report: ")).toList();
  }

  private EvalRunLauncher launcher(String script) {
    return new EvalRunLauncher(
        new Command(repo.resolve("evals"), List.of("sh", "-c", script, "sh"), APP_DB),
        Clock.fixed(NOW, ZoneOffset.UTC),
        new UiProperties(true, 30));
  }

  private RunState finished() {
    await().atMost(Duration.ofSeconds(15)).until(() -> !launcher.last().orElseThrow().running());
    return launcher.last().orElseThrow();
  }

  private static RunRequest offline() {
    return RunRequest.PRESETS.get("core-offline");
  }

  /** A stream that keeps what it is sent, as the browser would read it. */
  static final class Events extends SseEmitter {

    private final List<String> sent = new ArrayList<>();

    @Override
    public synchronized void send(SseEventBuilder builder) {
      StringBuilder event = new StringBuilder();
      builder.build().forEach(part -> event.append(text(part.getData())));
      sent.add(event.toString());
    }

    synchronized List<String> names() {
      return sent.stream()
          .map(event -> event.lines().findFirst().orElseThrow().replace("event:", ""))
          .toList();
    }

    synchronized String text() {
      return String.join("", sent);
    }

    private static String text(Object data) {
      return data instanceof String text ? text : new String(json(data), StandardCharsets.UTF_8);
    }

    private static byte[] json(Object data) {
      return new JsonMapper().writeValueAsBytes(data);
    }
  }
}
