package com.enrola.anna.web;

import com.enrola.anna.conversation.ConversationService;
import com.enrola.anna.conversation.TurnResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The platform's entry points. Start returns the opener, so no second call is needed to send it.
 */
@RestController
@RequestMapping("/api/conversations")
public class ConversationApi {

  private final ConversationService service;

  public ConversationApi(ConversationService service) {
    this.service = service;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public TurnResponse start(@Valid @RequestBody StartRequest request) {
    return service.start(request.leadId());
  }

  @GetMapping("/{id}")
  public TurnResponse view(
      @PathVariable
          @Pattern(
              regexp = "[A-Za-z0-9-]{1,40}",
              message = "must be 1 to 40 letters, digits or '-'")
          String id) {
    return service.view(id);
  }
}
