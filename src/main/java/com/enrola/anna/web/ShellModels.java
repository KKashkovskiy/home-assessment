package com.enrola.anna.web;

import com.enrola.anna.AppClock;
import com.enrola.anna.agent.AgentService;
import com.enrola.anna.agent.ModelCheck;
import com.enrola.anna.config.Settings;
import com.enrola.anna.config.SettingsStore;
import com.enrola.anna.conversation.SpendCap;
import com.enrola.anna.prompt.PromptStore;
import com.enrola.anna.scheduling.LeadZones;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Builds the {@link ShellModel} for a request from the clock, the current settings, the spend, the
 * model check and the mask. The clock is read from {@link AppClock} itself, so a scenario that
 * stopped it shows too.
 */
@Component
public class ShellModels {

  private final AppClock clock;
  private final SettingsStore settings;
  private final SpendCap spend;
  private final AgentService agentService;
  private final ObjectProvider<ModelCheck> modelCheck;
  private final PromptStore prompts;

  public ShellModels(
      AppClock clock,
      SettingsStore settings,
      SpendCap spend,
      AgentService agentService,
      ObjectProvider<ModelCheck> modelCheck,
      PromptStore prompts) {
    this.clock = clock;
    this.settings = settings;
    this.spend = spend;
    this.agentService = agentService;
    this.modelCheck = modelCheck;
    this.prompts = prompts;
  }

  /** The clock's time is in the advisors' zone, cut to the minute the strip's input shows. */
  public ShellModel shell(HttpSession session) {
    Settings current = settings.current();
    ZoneId advisorZone = current.scheduling().advisorZone();
    Instant now = clock.now();
    return new ShellModel(
        clock.fixed() ? Settings.ClockMode.FIXED : Settings.ClockMode.REAL,
        LocalDateTime.ofInstant(now, advisorZone).truncatedTo(ChronoUnit.MINUTES),
        LeadZones.abbreviation(advisorZone, now),
        current.calendarPreset(),
        spend.appSpend(),
        current.budgets().appCap(),
        current.agent().model(),
        current.agent().reasoning().value(),
        prompts.activeId(),
        agentStatus(),
        PiiMask.enabled(session));
  }

  /** A model client without a check, as in tests with a scripted model, is ready. */
  private AgentStatus agentStatus() {
    if (!agentService.available()) {
      return AgentStatus.NO_KEY;
    }
    ModelCheck check = modelCheck.getIfAvailable();
    return check == null ? AgentStatus.READY : AgentStatus.after(check.result());
  }
}
