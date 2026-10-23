package com.enrola.anna.web;

/**
 * A message above a console page: a refused start or send, a page that is not there, a lead found
 * by its phone. {@code code} is stable, for the page's markup and its tests; {@code text} is for
 * people and never repeats what the request sent.
 */
public record Notice(Level level, String code, String text) {

  /** How the notice reads: information, a refusal, or a failed request. */
  public enum Level {
    INFO,
    WARN,
    FAIL
  }

  static final String LEAD_REUSED = "LEAD_REUSED";

  static final Notice NOT_FOUND =
      new Notice(
          Level.FAIL, "NOT_FOUND", "404 NOT_FOUND: that lead or conversation does not exist.");

  static final Notice BAD_REQUEST =
      new Notice(
          Level.FAIL,
          "BAD_REQUEST",
          "400 BAD_REQUEST: the request had a value the console does not take, so here is the"
              + " full list.");

  /** The notice a redirect named in its {@code error} parameter; null for any other value. */
  static Notice fromCode(String code) {
    return switch (code == null ? "" : code) {
      case "SUPPRESSED" ->
          new Notice(
              Level.WARN, code, "409 SUPPRESSED: this phone opted out, so nothing can start.");
      case "ACTIVE" ->
          new Notice(
              Level.INFO,
              code,
              "409 ACTIVE: this lead already has an open conversation, shown here.");
      case "ABUSIVE" ->
          new Notice(
              Level.WARN,
              code,
              "409 ABUSIVE: this lead's last conversation ended abusive; only the API's force"
                  + " parameter starts another.");
      case "BUSY" ->
          new Notice(Level.WARN, code, "409 BUSY: another turn was running; nothing was sent.");
      case LEAD_REUSED ->
          new Notice(
              Level.INFO,
              code,
              "That phone already belongs to this lead, so the lead was started as stored and the"
                  + " details typed were not saved.");
      default -> null;
    };
  }
}
