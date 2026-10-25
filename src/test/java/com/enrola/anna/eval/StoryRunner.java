package com.enrola.anna.eval;

import com.enrola.anna.conversation.ConflictException;
import com.enrola.anna.conversation.Conversation;
import com.enrola.anna.conversation.InboundTurns;
import com.enrola.anna.conversation.Message;
import com.enrola.anna.conversation.MessageRepository;
import com.enrola.anna.scheduling.BookingRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Runs one story trial through the product: the opener, then line by line from the simulated lead
 * through {@link InboundTurns}, the path the API takes, each turn read back from the rows and
 * checked, until a {@code stop_when} condition holds. After a terminal state (ended, goal met or
 * handed off) {@value #PROBES_AFTER_END} more lines probe that Anna stays quiet, since "no goodbye
 * loop" is only provable by talking on; then the end state is checked. A persona break makes the
 * trial INVALID and the budget running out SKIPPED_BUDGET, each keeping what it cost; a broken
 * scenario fails on the hard gate.
 */
final class StoryRunner {

  static final int PROBES_AFTER_END = 2;

  private final Seeder seeder;
  private final InboundTurns inbound;
  private final TurnRowsReader reader;
  private final MessageRepository messages;
  private final BookingRepository bookings;
  private final ObjectProvider<Simulator> simulator;
  private final ObjectProvider<OfflineModel> offline;
  private final ObjectProvider<ScenarioModeration> moderation;

  StoryRunner(
      Seeder seeder,
      InboundTurns inbound,
      TurnRowsReader reader,
      MessageRepository messages,
      BookingRepository bookings,
      ObjectProvider<Simulator> simulator,
      ObjectProvider<OfflineModel> offline,
      ObjectProvider<ScenarioModeration> moderation) {
    this.seeder = seeder;
    this.inbound = inbound;
    this.reader = reader;
    this.messages = messages;
    this.bookings = bookings;
    this.simulator = simulator;
    this.offline = offline;
    this.moderation = moderation;
  }

  /**
   * Trial {@code trialNo}, asking {@code budget} before every line; SKIPPED_KIND when the actor
   * cannot play the story, as offline without scripted lines. A story that cannot start is a
   * scenario fault, or a FAIL with the product's refusal.
   */
  Trial run(Scenario.Story story, int trialNo, EvalBudget budget) {
    Persona persona = Persona.of(story);
    if (!simulator.getObject().plays(persona)) {
      return Trial.skipped(story, trialNo, TrialStatus.SKIPPED_KIND);
    }
    try {
      return new Talk(story, persona, trialNo, budget).run();
    } catch (ScenarioException e) {
      return Trial.scenarioFault(story, trialNo, e.getMessage());
    } catch (ConflictException e) {
      return Trial.refused(story, trialNo, TurnRunner.refusal(e));
    }
  }

  /** One trial's conversation, line by line; its fields are what the trial records. */
  private final class Talk {

    private final Scenario.Story story;
    private final Persona persona;
    private final int trialNo;
    private final EvalBudget budget;
    private final Simulator actor = simulator.getObject();
    private final List<StopWhen.Condition> stop;
    private final long started = System.nanoTime();
    private final List<StoryTurn> transcript = new ArrayList<>();
    private final List<Check> checks = new ArrayList<>();
    private final Set<String> toolsCalled = new HashSet<>();
    private final StoryCounters counters = new StoryCounters();
    private Conversation current;
    private String lastReply;
    private String stopReason;
    private BigDecimal cost = BigDecimal.ZERO;
    private int modelCalls;
    private int leadMessages;
    private Simulator.Moment moment = Simulator.Moment.TALKING;

    /** {@code stop_when} parses, as the loader made sure. */
    Talk(Scenario.Story story, Persona persona, int trialNo, EvalBudget budget) {
      this.story = story;
      this.persona = persona;
      this.trialNo = trialNo;
      this.budget = budget;
      this.stop = StopWhen.parseAll(story.stopWhen());
    }

    /**
     * Seeds the conversation, talks until a condition holds, probes after a terminal state, then
     * checks the end. A scenario fault part way, such as scripted lines running out, fails the
     * trial on the hard gate and keeps the transcript so far.
     */
    Trial run() {
      current = seeder.seed(story);
      moderation.ifAvailable(screen -> screen.arm(story));
      String opener = messages.transcript(current.id).getFirst().text();
      transcript.add(new StoryTurn(0, null, List.of(opener), List.of(), BigDecimal.ZERO, moment));
      try {
        Optional<Trial> cut = talk();
        if (cut.isEmpty() && terminal()) {
          moment = Simulator.Moment.AFTER_END;
          cut = probe();
        }
        return cut.orElseGet(this::finished);
      } catch (ScenarioException e) {
        checks.add(Check.hard("scenario", false, e.getMessage()));
        return cut(TrialStatus.FAIL, "scenario fault");
      }
    }

    /**
     * Sends lines until a stop condition holds, which a cap always does, and returns a trial if the
     * story is cut short.
     */
    private Optional<Trial> talk() {
      Optional<StopWhen.Condition> hit = Optional.empty();
      while (hit.isEmpty()) {
        Optional<Trial> cut = exchange();
        if (cut.isPresent()) {
          return cut;
        }
        hit = StopConditions.firstMet(stop, current, leadMessages, lastReply);
      }
      stopReason = hit.get().describe() + " after " + leadMessages + " lead messages";
      return Optional.empty();
    }

    private Optional<Trial> probe() {
      for (int probe = 0; probe < PROBES_AFTER_END; probe++) {
        Optional<Trial> cut = exchange();
        if (cut.isPresent()) {
          return cut;
        }
      }
      return Optional.empty();
    }

    private boolean terminal() {
      return current.ended || current.goalMet || current.needsHuman;
    }

    /**
     * Sends one line from the actor as the lead's, or returns a trial instead when the budget has
     * no room for its turn or the line breaks the persona.
     */
    private Optional<Trial> exchange() {
      if (!budget.allowsAnotherCall(cost)) {
        return Optional.of(cut(TrialStatus.SKIPPED_BUDGET, "budget ran out"));
      }
      offline.ifAvailable(
          model -> model.arm(new StoryStandIn(story.clock().toLocalDate(), current)));
      Simulator.Next next = actor.next(persona, messages.transcript(current.id), moment);
      cost = cost.add(next.costUsd());
      return switch (next) {
        case Simulator.PersonaBreak broken -> {
          checks.add(Check.expectation("persona", false, broken.reason()));
          yield Optional.of(cut(TrialStatus.INVALID, "persona break"));
        }
        case Simulator.Line line -> {
          send(line);
          yield Optional.empty();
        }
      };
    }

    /** The line through the product, then the turn read back, counted and checked. */
    private void send(Simulator.Line line) {
      leadMessages++;
      inbound.handle(current.id, line.text(), "eval-" + story.id() + "-" + leadMessages);
      TurnRows rows = reader.latestTurn(current.id);
      current = rows.conversation();
      lastReply = rows.reply();
      cost = cost.add(rows.costUsd());
      modelCalls += rows.modelCalls();
      toolsCalled.addAll(rows.toolNames());
      counters.observe(current, rows.outbound().size());
      checks.addAll(Checks.suiteWide(story.expect(), rows));
      checks.addAll(StoryChecks.everyReply(story.expect().everyReply(), rows));
      transcript.add(
          new StoryTurn(
              rows.turnNo(),
              line.text(),
              rows.outbound().stream().map(Message::text).toList(),
              rows.toolCalls(),
              rows.costUsd().add(line.costUsd()),
              moment));
    }

    /** The end state, the transcript's regexes and the counters' limits. */
    private Trial finished() {
      int booked = bookings.findByConversationId(current.id).isPresent() ? 1 : 0;
      checks.addAll(Checks.endState(story.expect(), current, toolsCalled, booked));
      checks.addAll(StoryChecks.transcript(story.expect(), replies()));
      checks.addAll(StoryChecks.counters(story.expect(), counters));
      boolean passed = checks.stream().allMatch(Check::pass);
      return trial(passed ? TrialStatus.PASS : TrialStatus.FAIL, stopReason + probes());
    }

    private Trial cut(TrialStatus status, String why) {
      return trial(status, why + " after " + leadMessages + " lead messages");
    }

    /** Every reply Anna sent after the opener. */
    private List<String> replies() {
      return transcript.stream()
          .filter(turn -> turn.turnNo() > 0)
          .flatMap(turn -> turn.replies().stream())
          .toList();
    }

    /** {@code ; 2 probes sent, 1 replied}, or nothing when none was sent. */
    private String probes() {
      List<StoryTurn> sent = transcript.stream().filter(StoryTurn::probe).toList();
      long replied = sent.stream().filter(turn -> !turn.replies().isEmpty()).count();
      return sent.isEmpty() ? "" : "; %d probes sent, %d replied".formatted(sent.size(), replied);
    }

    private Trial trial(TrialStatus status, String reason) {
      return new Trial(
          story.id(),
          trialNo,
          status,
          List.copyOf(checks),
          List.of(),
          cost,
          modelCalls,
          current.id,
          new Trial.StoryDetail(List.copyOf(transcript), reason),
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }
  }
}
