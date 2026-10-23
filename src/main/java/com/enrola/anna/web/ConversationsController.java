package com.enrola.anna.web;

import com.enrola.anna.config.UiProperties;
import com.enrola.anna.conversation.BusyException;
import com.enrola.anna.conversation.ConflictException;
import com.enrola.anna.conversation.ConversationService;
import com.enrola.anna.conversation.Direction;
import com.enrola.anna.conversation.InboundTurns;
import com.enrola.anna.conversation.Source;
import com.enrola.anna.conversation.TurnResponse;
import com.enrola.anna.lead.LeadService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.Pattern;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

/**
 * The Conversations page: list, chat, state and the start panel. A second client of the services
 * the platform API calls, never a second pipeline. Every action is a form post answered with a
 * redirect, so the page works without JavaScript; the composer's script sends with {@code
 * X-Requested-With} and gets the turn back as a fragment instead.
 */
@Console
@Controller
public class ConversationsController {

  /** The list's path, which every redirect and filter link starts from. */
  public static final String LIST = "/conversations";

  static final String PAGE = "conversations";

  /** The composer: typed text, or the text of the quick-reply chip that was pressed. */
  public record SendForm(String text, String quick) {

    String message() {
      return quick != null && !quick.isBlank() ? quick : Objects.requireNonNullElse(text, "");
    }

    /** Why the message cannot be sent, or null when it can. */
    String problem() {
      if (message().isBlank()) {
        return "Type something as the lead.";
      }
      return message().length() > InboundRequest.MAX_TEXT
          ? "A message is capped at "
              + InboundRequest.MAX_TEXT
              + " characters, the platform's transport limit."
          : null;
    }
  }

  private final ConversationService conversations;
  private final LeadService leads;
  private final InboundTurns inboundTurns;
  private final ConsoleViews views;
  private final TraceView traces;
  private final ConsolePages pages;
  private final UiProperties ui;

  public ConversationsController(
      ConversationService conversations,
      LeadService leads,
      InboundTurns inboundTurns,
      ConsoleViews views,
      TraceView traces,
      ConsolePages pages,
      UiProperties ui) {
    this.conversations = conversations;
    this.leads = leads;
    this.inboundTurns = inboundTurns;
    this.views = views;
    this.traces = traces;
    this.pages = pages;
    this.ui = ui;
  }

  @GetMapping("/")
  public String root() {
    return "redirect:" + LIST;
  }

  /** {@code start=true} opens the start panel, the link the start button follows without script. */
  @GetMapping(LIST)
  public String list(
      @ModelAttribute("filter") ConversationFilter filter,
      @RequestParam(required = false) String error,
      @RequestParam(defaultValue = "false") boolean start,
      HttpSession session,
      Model model) {
    ConsolePage page =
        pages
            .page(filter, PiiMask.enabled(session))
            .withStart(start ? ConsolePage.StartPanel.OPEN : ConsolePage.StartPanel.CLOSED)
            .withNotice(Notice.fromCode(error));
    model.addAttribute("page", page);
    return PAGE;
  }

  @GetMapping(LIST + "/{id}")
  public String show(
      @PathVariable @Pattern(regexp = ConversationApi.ID) String id,
      @ModelAttribute("filter") ConversationFilter filter,
      @RequestParam(required = false) String error,
      HttpSession session,
      Model model) {
    boolean mask = PiiMask.enabled(session);
    ConsolePage.Open open =
        new ConsolePage.Open(
            views.build(id, mask),
            new InspectorView(
                traces.turns(id, mask), ShellController.rawJsonShown(session), ui.sseEnabled()));
    model.addAttribute("page", pages.page(filter, open, mask).withNotice(Notice.fromCode(error)));
    return PAGE;
  }

  /** A form with a bad field comes back open with the field marked; nothing is written. */
  @PostMapping(LIST + "/start")
  public String start(@ModelAttribute StartForm form, HttpSession session, Model model) {
    Map<String, String> errors = form.errors();
    if (!errors.isEmpty()) {
      ConsolePage page =
          pages
              .page(ConversationFilter.NONE, PiiMask.enabled(session))
              .withStart(new ConsolePage.StartPanel(form, errors, true));
      model.addAttribute("page", page);
      return PAGE;
    }
    try {
      return form.customSelected() ? startCustom(form.custom()) : startSeeded(form.lead());
    } catch (ConflictException e) {
      return conflict(e, null);
    }
  }

  @PostMapping(LIST + "/{id}/reset")
  public String reset(@PathVariable @Pattern(regexp = ConversationApi.ID) String id) {
    try {
      return redirectTo(conversations.reset(id).conversationId());
    } catch (ConflictException e) {
      return conflict(e, id);
    }
  }

  /** Without script: the turn runs and the page reloads with it. */
  @PostMapping(LIST + "/{id}/send")
  public String send(
      @PathVariable @Pattern(regexp = ConversationApi.ID) String id,
      @ModelAttribute SendForm form) {
    if (form.problem() != null) {
      return redirectTo(id);
    }
    try {
      inboundTurns.handle(id, form.message(), null);
      return redirectTo(id);
    } catch (BusyException _) {
      return redirectTo(id) + "?error=" + BusyException.CODE;
    }
  }

  /**
   * The composer's script: the turn's thread items as a fragment, its root carrying the turn number
   * and the composer's next hint; or a problem line with 400 for an empty or oversized message and
   * 409 while another turn runs.
   */
  @PostMapping(path = LIST + "/{id}/send", headers = "X-Requested-With")
  public ModelAndView sendFromComposer(
      @PathVariable @Pattern(regexp = ConversationApi.ID) String id,
      @ModelAttribute SendForm form,
      HttpSession session) {
    if (form.problem() != null) {
      return problem(HttpStatus.BAD_REQUEST, form.problem());
    }
    try {
      TurnResponse turn = inboundTurns.handle(id, form.message(), null);
      ConsoleView view = views.build(id, PiiMask.enabled(session));
      int turnNo = lastInboundTurn(turn);
      return new ModelAndView(
          "fragments/turn :: turn",
          Map.of("items", view.turn(turnNo), "view", view, "turnNo", turnNo));
    } catch (BusyException _) {
      return problem(
          HttpStatus.CONFLICT,
          "409 BUSY: another turn is running for this conversation. Try again.");
    }
  }

  private String startSeeded(String leadId) {
    return redirectTo(conversations.start(leadId, Source.SIM).conversationId());
  }

  /** A phone that already has a lead starts that lead, and the page says the typed details went. */
  private String startCustom(LeadService.CustomLead custom) {
    LeadService.Entered entered = leads.createCustom(custom);
    String started = conversations.start(entered.lead().id(), Source.SIM).conversationId();
    return redirectTo(started) + (entered.existing() ? "?error=" + Notice.LEAD_REUSED : "");
  }

  private static ModelAndView problem(HttpStatus status, String message) {
    return new ModelAndView("fragments/turn :: problem", Map.of("message", message), status);
  }

  /** The turn a send just ran: the latest inbound in the response's transcript. */
  private static int lastInboundTurn(TurnResponse turn) {
    return turn.transcript().reversed().stream()
        .filter(message -> message.direction() == Direction.IN)
        .findFirst()
        .orElseThrow()
        .turn();
  }

  /** A conflict that names a conversation, ACTIVE or ABUSIVE, opens that one. */
  private static String conflict(ConflictException e, String fallbackId) {
    String id = e.conversationId() != null ? e.conversationId() : fallbackId;
    return "redirect:" + LIST + (id == null ? "" : "/" + id) + "?error=" + e.code();
  }

  private static String redirectTo(String conversationId) {
    return "redirect:" + LIST + "/" + conversationId;
  }
}
