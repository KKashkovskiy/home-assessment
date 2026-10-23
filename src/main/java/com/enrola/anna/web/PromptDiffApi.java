package com.enrola.anna.web;

import com.enrola.anna.prompt.PromptStore;
import com.enrola.anna.prompt.PromptVersionMissing;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * The Configuration page's diff: the stored text of one file in two versions, for Monaco's diff
 * editor to render. JSON only, errors included, since the page's script is its one caller.
 */
@RestController
public class PromptDiffApi {

  /** Both sides of a diff and the language Monaco highlights them as. */
  public record DiffTexts(Side a, Side b, String language) {

    /** One side: a version and its text of the file. */
    public record Side(String id, String text) {}
  }

  /** A diff that cannot be answered; the message is fixed and never repeats the request. */
  public record DiffError(String error, String message) {}

  private final PromptStore prompts;

  public PromptDiffApi(PromptStore prompts) {
    this.prompts = prompts;
  }

  @GetMapping(path = "/configuration/diff", produces = MediaType.APPLICATION_JSON_VALUE)
  public DiffTexts diff(
      @RequestParam @Pattern(regexp = ConfigurationController.VERSION_ID) String a,
      @RequestParam @Pattern(regexp = ConfigurationController.VERSION_ID) String b,
      @RequestParam PromptFile file) {
    return new DiffTexts(side(a, file), side(b, file), file.language());
  }

  @ExceptionHandler(PromptVersionMissing.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  DiffError missing() {
    return new DiffError("NOT_FOUND", "a and b must both be saved prompt versions");
  }

  @ExceptionHandler({
    HandlerMethodValidationException.class,
    MethodArgumentTypeMismatchException.class,
    MissingServletRequestParameterException.class
  })
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  DiffError invalid() {
    return new DiffError(
        "VALIDATION",
        "a and b must be version ids such as v2, and file one of PROMPT, PACK, SCHEMA, TOOLS or"
            + " TEMPLATES");
  }

  private DiffTexts.Side side(String id, PromptFile file) {
    return new DiffTexts.Side(id, file.read(prompts.get(id)));
  }
}
