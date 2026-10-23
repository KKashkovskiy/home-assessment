package com.enrola.anna.agent;

import com.enrola.anna.config.AgentProperties;
import com.enrola.anna.config.PersonaProperties;
import com.enrola.anna.config.SmsProperties;
import com.enrola.anna.prompt.PromptBundle;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Asks the model for one decision: builds the request from the bundle, the transcript and the
 * turn's context, and calls the client. Changes no state itself; the tool executor the caller hands
 * in may. The caller maps the output to a {@link Decision}, records the call and applies it.
 * Without {@code OPENAI_API_KEY} there is no client and {@link #available()} is false.
 */
@Service
public class AgentService {

  private static final Logger log = LoggerFactory.getLogger(AgentService.class);

  private final ModelClient client;
  private final AgentProperties agent;
  private final PersonaProperties persona;
  private final SmsProperties sms;

  public AgentService(
      ObjectProvider<ModelClient> client,
      AgentProperties agent,
      PersonaProperties persona,
      SmsProperties sms) {
    this.client = client.getIfAvailable();
    this.agent = agent;
    this.persona = persona;
    this.sms = sms;
    if (this.client == null) {
      log.warn("model check skipped: OPENAI_API_KEY not set; inbound turns hand off with ERROR");
    }
  }

  public boolean available() {
    return client != null;
  }

  /**
   * One call: {@code transcript} replayed whole, then {@code context} as the last item. The
   * bundle's tools are offered, {@code tools} answers the model's calls of them, and {@code
   * anna.agent.max-tool-rounds} caps how many rounds of calls it answers.
   */
  public ModelResponse decide(
      PromptBundle bundle,
      List<ModelRequest.InputItem> transcript,
      TurnContext context,
      ToolExecutor tools) {
    return call(
        bundle,
        transcript,
        context,
        new ModelRequest.Tools(bundle.tools(), tools, agent.maxToolRounds()));
  }

  /** Like {@link #decide}, with no tools offered: after an end the model only classifies. */
  public ModelResponse classify(
      PromptBundle bundle, List<ModelRequest.InputItem> transcript, TurnContext context) {
    return call(bundle, transcript, context, ModelRequest.Tools.NONE);
  }

  private ModelResponse call(
      PromptBundle bundle,
      List<ModelRequest.InputItem> transcript,
      TurnContext context,
      ModelRequest.Tools tools) {
    List<ModelRequest.InputItem> input = new ArrayList<>(transcript);
    input.add(new ModelRequest.UserText(context.render()));
    return client.call(
        new ModelRequest(
            agent.model(),
            bundle.render(persona, sms),
            input,
            bundle.schema().get("name").stringValue(),
            bundle.schema().get("schema"),
            tools,
            agent.reasoningEffort(),
            agent.reasoningSummary(),
            agent.maxOutputTokens()));
  }
}
