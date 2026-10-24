package com.enrola.anna.web;

import com.enrola.anna.conversation.NotFoundException;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.ModelAndView;

/**
 * The error page of every {@link Console} controller: the Conversations page, unfiltered, with a
 * notice in fixed words in place of the stack trace or JSON a request would otherwise get.
 */
@ControllerAdvice(annotations = Console.class)
public class ConsoleErrors {

  private final ShellModelAdvice shells;
  private final ConsolePages pages;

  public ConsoleErrors(ShellModelAdvice shells, ConsolePages pages) {
    this.shells = shells;
    this.pages = pages;
  }

  /** A lead or conversation that does not exist, or a path segment that cannot be an id. */
  @ExceptionHandler({NotFoundException.class, HandlerMethodValidationException.class})
  public ModelAndView notFound(HttpSession session) {
    return errorPage(HttpStatus.NOT_FOUND, Notice.NOT_FOUND, session);
  }

  /** A filter or flag outside its values, or a missing one, as from a hand-edited link. */
  @ExceptionHandler({
    BindException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class
  })
  public ModelAndView badRequest(HttpSession session) {
    return errorPage(HttpStatus.BAD_REQUEST, Notice.BAD_REQUEST, session);
  }

  /** Exception handlers skip the advice's model attributes, so the shell is added here. */
  private ModelAndView errorPage(HttpStatus status, Notice notice, HttpSession session) {
    ConsolePage page =
        pages.page(ConversationFilter.NONE, PiiMask.enabled(session)).withNotice(notice);
    return new ModelAndView(
        ConversationsController.PAGE,
        Map.of("shell", shells.shell(shells.settings(), session), "page", page),
        status);
  }
}
