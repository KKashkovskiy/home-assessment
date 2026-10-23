package com.enrola.anna.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.enrola.anna.agent.EndReason;
import com.enrola.anna.conversation.ConversationRepository;
import com.enrola.anna.conversation.Direction;
import com.enrola.anna.conversation.InboundTurns;
import com.enrola.anna.conversation.Message;
import com.enrola.anna.conversation.MessageRepository;
import com.enrola.anna.scheduling.BookingRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The story loop over real H2 on the offline model, with the scripted lead or a fake one: a booking
 * then silence, the objection counters, a persona break, a scenario fault, a cap without a terminal
 * state, and the budget before each line.
 */
class StoryRunnerFakeTest extends EvalFixture {

  static final EvalBudget BUDGET = new EvalBudget(BigDecimal.TEN, EvalBudgetTest.FIVE_CENTS, "m");

  @Autowired StoryRunner runner;
  @Autowired Seeder seeder;
  @Autowired InboundTurns inbound;
  @Autowired TurnRowsReader reader;
  @Autowired ConversationRepository conversations;
  @Autowired MessageRepository messages;
  @Autowired BookingRepository bookings;
  @Autowired ObjectProvider<OfflineModel> offline;
  @Autowired ObjectProvider<ScenarioModeration> moderation;

  /** The confirmation counts as no reply after the goal; one closing reply, then silence. */
  @Test
  void booksAndProbesSilence() {
    Trial trial = runner.run(ScenarioFixtures.loadStory("story-lauren-books"), 1, BUDGET);

    assertThat(trial.status()).as("%s", trial.checks()).isEqualTo(TrialStatus.PASS);
    assertThat(trial.checks())
        .filteredOn(check -> check.name().equals("booking_count"))
        .singleElement()
        .satisfies(check -> assertThat(check.pass()).isTrue());
    assertThat(trial.stopReason())
        .isEqualTo("goal_met after 4 lead messages; 2 probes sent, 1 replied");
    assertThat(transcript(trial).stream().filter(StoryTurn::probe))
        .extracting(turn -> turn.replies().size())
        .containsExactly(1, 0);
    assertThat(inbound(trial)).endsWith("thanks", "ok");
  }

  /** Two objections: one retried, the second closes DECLINED, and the probes get nothing. */
  @Test
  void declineCountsRetries() {
    Trial trial = runner.run(ScenarioFixtures.loadStory("story-john-objects-twice"), 1, BUDGET);

    assertThat(trial.status()).as("%s", trial.checks()).isEqualTo(TrialStatus.PASS);
    assertThat(conversations.require(trial.conversationId()).endReason)
        .isEqualTo(EndReason.DECLINED);
    assertThat(trial.checks())
        .extracting(Check::name, Check::evidence)
        .contains(
            tuple("retries_max", "max=1 seen=1"), tuple("replies_after_end_max", "max=1 seen=0"));
    assertThat(trial.stopReason())
        .isEqualTo("goodbye after 2 lead messages; 2 probes sent, 0 replied");
  }

  /** The actor breaks character after one line: INVALID, the turn that ran kept with its cost. */
  @Test
  void personaBreakIsInvalid() {
    Simulator breaksOnTheSecondLine =
        new FakeLead(new BigDecimal("0.01")) {
          @Override
          public Next next(Persona persona, List<Message> transcript, Moment moment) {
            return transcript.size() == 1
                ? super.next(persona, transcript, moment)
                : new PersonaBreak("actor claimed to be an AI: \"As an AI\"", cost);
          }
        };

    Trial trial =
        runnerWith(breaksOnTheSecondLine)
            .run(ScenarioFixtures.loadStory("story-lauren-books"), 1, BUDGET);

    assertThat(trial.status()).isEqualTo(TrialStatus.INVALID);
    assertThat(trial.failedNames()).containsExactly("persona");
    assertThat(trial.costUsd()).isEqualByComparingTo("0.02");
    assertThat(transcript(trial)).hasSize(2);
    assertThat(trial.stopReason()).isEqualTo("persona break after 1 lead messages");
  }

  /** Scripted lines that run out are the scenario's fault: a hard FAIL, the turns so far kept. */
  @Test
  void runningOutOfLinesIsAScenarioFault() {
    Scenario.Story story =
        variant(
            ScenarioFixtures.loadStory("story-lauren-books"),
            List.of("goal_met"),
            "all of the above");

    Trial trial = runner.run(story, 1, BUDGET);

    assertThat(trial.status()).isEqualTo(TrialStatus.FAIL);
    assertThat(trial.checks().getLast())
        .isEqualTo(Check.hard("scenario", false, "offline_lines ran out after 1 lead messages"));
    assertThat(transcript(trial)).hasSize(2);
  }

  /** A cap with no terminal state ends the story unresolved, unprobed, judged by its end state. */
  @Test
  void capStopsWithoutTerminal() {
    Scenario.Story story =
        variant(
            ScenarioFixtures.loadStory("story-lauren-books"),
            List.of("3 lead messages have been sent"),
            "hi there",
            "what is this about",
            "hmm",
            "never sent");

    Trial trial = runner.run(story, 1, BUDGET);

    assertThat(trial.status()).isEqualTo(TrialStatus.FAIL);
    assertThat(trial.failedNames()).contains("goal_met", "booking_count");
    assertThat(transcript(trial)).hasSize(4).noneMatch(StoryTurn::probe);
    assertThat(trial.stopReason()).isEqualTo("message cap after 3 lead messages");
  }

  /**
   * Lines at $0.05 against a $0.14 cap: the third would leave no room for a typical call, so the
   * story stops after two, SKIPPED_BUDGET with what it spent.
   */
  @Test
  void theBudgetIsAskedBeforeEachLine() {
    var budget = new EvalBudget(new BigDecimal("0.14"), EvalBudgetTest.FIVE_CENTS, "m");

    Trial trial =
        runnerWith(new FakeLead(new BigDecimal("0.05")))
            .run(ScenarioFixtures.loadStory("story-lauren-books"), 1, budget);

    assertThat(trial.status()).isEqualTo(TrialStatus.SKIPPED_BUDGET);
    assertThat(trial.stopReason()).isEqualTo("budget ran out after 2 lead messages");
    assertThat(trial.costUsd()).isEqualByComparingTo("0.10");
  }

  @Test
  void aStoryWithoutLinesIsSkippedOffline() {
    Scenario.Story story = ScenarioFixtures.story("story-x");

    assertThat(runner.run(story, 1, BUDGET).status()).isEqualTo(TrialStatus.SKIPPED_KIND);
  }

  /** A lead that says "hi there" for {@code cost} a line. */
  private static class FakeLead implements Simulator {

    final BigDecimal cost;

    FakeLead(BigDecimal cost) {
      this.cost = cost;
    }

    @Override
    public boolean plays(Persona persona) {
      return true;
    }

    @Override
    public Next next(Persona persona, List<Message> transcript, Moment moment) {
      return new Line("hi there", cost);
    }
  }

  /** The runner of the context with {@code actor} as the lead. */
  private StoryRunner runnerWith(Simulator actor) {
    ObjectProvider<Simulator> lead =
        new ObjectProvider<>() {
          @Override
          public Simulator getObject() {
            return actor;
          }
        };
    return new StoryRunner(seeder, inbound, reader, messages, bookings, lead, offline, moderation);
  }

  private static List<StoryTurn> transcript(Trial trial) {
    return ((Trial.StoryDetail) trial.detail()).transcript();
  }

  private List<String> inbound(Trial trial) {
    return messages.transcript(trial.conversationId()).stream()
        .filter(message -> message.direction() == Direction.IN)
        .map(Message::text)
        .toList();
  }

  /** {@code story} with other stop conditions and offline lines. */
  private static Scenario.Story variant(
      Scenario.Story story, List<String> stopWhen, String... lines) {
    return new Scenario.Story(
        story.id(),
        story.title(),
        story.tags(),
        story.lead(),
        story.clock(),
        story.calendar(),
        story.persona(),
        stopWhen,
        story.expect(),
        story.judge(),
        new Scenario.Config(null, null, List.of(lines), null));
  }
}
