package com.enrola.anna.web;

import com.enrola.anna.AppClock;
import com.enrola.anna.agent.AgentService;
import com.enrola.anna.agent.ModelCheck;
import com.enrola.anna.config.AgentProperties;
import com.enrola.anna.config.BudgetsProperties;
import com.enrola.anna.config.SchedulingProperties;
import com.enrola.anna.conversation.SpendCap;
import com.enrola.anna.prompt.PromptStore;
import com.enrola.anna.scheduling.LeadZones;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link ShellModel} for a request, from the clock, the spend, the model and the mask.
 */
@Component
public class ShellModels {

  private static final DateTimeFormatter FIXED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  private final AppClock clock;
  private final ZoneId advisorZone;
  private final SpendCap spend;
  private final BudgetsProperties budgets;
  private final AgentProperties agent;
  private final AgentService agentService;
  private final ObjectProvider<ModelCheck> modelCheck;
  private final PromptStore prompts;

  public ShellModels(
      AppClock clock,
      SchedulingProperties scheduling,
      SpendCap spend,
      BudgetsProperties budgets,
      AgentProperties agent,
      AgentService agentService,
      ObjectProvider<ModelCheck> modelCheck,
      PromptStore prompts) {
    this.clock = clock;
    this.advisorZone = scheduling.advisorZone();
    this.spend = spend;
    this.budgets = budgets;
    this.agent = agent;
    this.agentService = agentService;
    this.modelCheck = modelCheck;
    this.prompts = prompts;
  }

  public ShellModel shell(HttpSession session) {
    return new ShellModel(
        clock.fixed() ? "FIXED" : "REAL",
        clock.fixed() ? fixedAtLabel(clock.now()) : null,
        spend.appSpend(),
        budgets.appCapUsd(),
        agent.model(),
        agent.reasoningEffort(),
        prompts.activeId(),
        agentStatus(),
        PiiMask.enabled(session));
  }

  /** "2026-07-26 10:00 AEST", in the advisors' zone. */
  private String fixedAtLabel(Instant now) {
    return FIXED_AT.format(now.atZone(advisorZone))
        + " "
        + LeadZones.abbreviation(advisorZone, now);
  }

  /** A model client without a boot check, as in tests with a scripted model, is ready. */
  private AgentStatus agentStatus() {
    if (!agentService.available()) {
      return AgentStatus.NO_KEY;
    }
    ModelCheck check = modelCheck.getIfAvailable();
    return check == null ? AgentStatus.READY : AgentStatus.after(check.result());
  }
}
