package com.enrola.anna.web;

import com.enrola.anna.conversation.ConversationService;
import com.enrola.anna.conversation.InboundTurns;
import com.enrola.anna.conversation.Source;
import com.enrola.anna.conversation.TurnResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The platform's entry points. Start returns the opener and an inbound message returns the reply,
 * or null for none, so the platform sends what the response says and needs no second call.
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationApi {

  /** A conversation id as a path segment; the console checks its own paths against it too. */
  static final String ID = "[A-Za-z0-9-]{1,40}";

  private static final String ID_RULE = "must be 1 to 40 letters, digits or '-'";

  private final ConversationService conversations;
  private final InboundTurns inboundTurns;

  public ConversationApi(ConversationService conversations, InboundTurns inboundTurns) {
    this.conversations = conversations;
    this.inboundTurns = inboundTurns;
  }

  /** {@code force=true} opens a second conversation for a lead whose first is still open. */
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public TurnResponse start(
      @Valid @RequestBody StartRequest request,
      @RequestParam(defaultValue = "false") boolean force) {
    return force
        ? conversations.forceStart(request.leadId())
        : conversations.start(request.leadId(), Source.API);
  }

  @PostMapping("/{id}/messages")
  public TurnResponse message(
      @PathVariable @Pattern(regexp = ID, message = ID_RULE) String id,
      @Valid @RequestBody InboundRequest request) {
    return inboundTurns.handle(id, request.text(), request.providerMessageId());
  }

  /** Bodies a turn with health information hid come back only with {@code includeHealth=true}. */
  @GetMapping("/{id}")
  public TurnResponse view(
      @PathVariable @Pattern(regexp = ID, message = ID_RULE) String id,
      @RequestParam(defaultValue = "false") boolean includeHealth) {
    return includeHealth ? conversations.viewWithHealth(id) : conversations.view(id);
  }
}
