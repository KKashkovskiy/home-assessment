package com.enrola.anna.web;

import com.enrola.anna.conversation.NotFoundException;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.ModelAndView;

/**
 * What the inspector fetches beside the page that holds it: one turn's card, which the live log
 * swaps in, the turn's stored JSON, and the prompt dialog. Fragments, not pages, so an unknown
 * conversation is a bare 404.
 */
@Controller
public class InspectorController {

  private final TraceView traces;

  public InspectorController(TraceView traces) {
    this.traces = traces;
  }

  /** The turn's card, open, as the live log appends it once the turn has finished. */
  @GetMapping(ConversationsController.LIST + "/{id}/turns/{n}")
  public ModelAndView turn(
      @PathVariable @Pattern(regexp = ConversationApi.ID) String id,
      @PathVariable int n,
      HttpSession session) {
    TurnView turn =
        traces.turn(id, n, PiiMask.enabled(session)).orElseThrow(() -> unfinished(id, n));
    return new ModelAndView(
        "fragments/inspector :: turn",
        Map.of("turn", turn, "open", true, "rawOpen", ShellController.rawJsonShown(session)));
  }

  /** The turn's stored decision, request and response JSON, masked like the page. */
  @GetMapping(
      path = ConversationsController.LIST + "/{id}/turns/{n}/raw",
      produces = MediaType.APPLICATION_JSON_VALUE)
  @ResponseBody
  public String raw(
      @PathVariable @Pattern(regexp = ConversationApi.ID) String id,
      @PathVariable int n,
      HttpSession session) {
    return traces.raw(id, n, PiiMask.enabled(session)).orElseThrow(() -> unfinished(id, n));
  }

  /** The prompt dialog: as sent on {@code turn}, or the pinned version without one. */
  @GetMapping(ConversationsController.LIST + "/{id}/prompt")
  public ModelAndView prompt(
      @PathVariable @Pattern(regexp = ConversationApi.ID) String id,
      @RequestParam(required = false) Integer turn,
      HttpSession session) {
    return new ModelAndView(
        "fragments/inspector :: prompt",
        Map.of("prompt", traces.prompt(id, turn, PiiMask.enabled(session))));
  }

  /** A turn not finished, an unknown conversation, or a path segment that cannot be an id. */
  @ExceptionHandler({NotFoundException.class, HandlerMethodValidationException.class})
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public void notFound() {}

  private static NotFoundException unfinished(String conversationId, int turnNo) {
    return new NotFoundException("turn", conversationId + "/" + turnNo);
  }
}
