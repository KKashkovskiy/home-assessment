package com.enrola.anna.guard;

import com.enrola.anna.lead.Lead;
import com.enrola.anna.lead.Phones;
import com.enrola.anna.prompt.Gsm7;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

/**
 * The checks every model-written reply must pass before it is sent, run on the reply as {@link
 * Gsm7} normalises it, so length is measured on what the lead receives. They return violations and
 * never call the model or write a row: the caller owns the repair. The deny-list is loaded when
 * this bean is built, so a broken file fails boot.
 */
@Component
public final class ReplyGuards {

  private static final int MAX_SLOTS = 3;

  /** Headings, bold, links and bullets: markdown that survives normalisation. */
  private static final Pattern MARKDOWN =
      Pattern.compile("^#{1,6}\\s|\\*\\*|\\[[^\\]]+\\]\\([^)]+\\)|^\\s*[-*]\\s", Pattern.MULTILINE);

  /**
   * A run of eight or more digits and phone punctuation, digits at both ends; the PII mask reuses
   * it.
   */
  public static final Pattern PHONE_LIKE = Pattern.compile("\\+?\\d[\\d\\s().-]{6,}\\d");

  private static final Pattern NOT_DIGIT = Pattern.compile("\\D");

  private final DenyList denyList = DenyList.bundled();

  /** A reply as it would be sent, normalised, and what it broke; it may be sent when nothing. */
  public record Checked(String text, List<Violation> violations) {

    public boolean passed() {
      return violations.isEmpty();
    }
  }

  /** {@code raw} normalised, with every check it fails. */
  public Checked validate(String raw, ReplyContext context) {
    String reply = Gsm7.normalise(raw);
    List<Violation> violations =
        Stream.of(
                failsIf(reply.isBlank(), ReplyCheck.EMPTY, "reply is empty"),
                length(reply, context.maxChars()),
                questions(reply, context),
                failsIf(reply.indexOf('!') >= 0, ReplyCheck.EXCLAMATION, "no exclamation marks"),
                canary(reply, context.canary()),
                format(raw, reply),
                failsIf(Urls.offList(reply), ReplyCheck.URL, "only comparato.au links are allowed"),
                contact(reply, context.lead()),
                denyList.violations(reply, context.lead()).stream(),
                TimeMentions.check(reply, context.offeredLabels(), context.bookingLabel()).stream(),
                slots(reply))
            .flatMap(Function.identity())
            .toList();
    return new Checked(reply, violations);
  }

  /** One line for the repair call: "check: note; check: note". */
  public static String note(List<Violation> violations) {
    return violations.stream()
        .map(violation -> violation.check().traceName() + ": " + violation.note())
        .collect(Collectors.joining("; "));
  }

  private static Stream<Violation> length(String reply, int maxChars) {
    return failsIf(
        reply.length() > maxChars,
        ReplyCheck.LENGTH,
        "reply is %d chars, limit %d".formatted(reply.length(), maxChars));
  }

  /** Exactly one question until the goal is met; none in a reply that closes the conversation. */
  private static Stream<Violation> questions(String reply, ReplyContext context) {
    long asked = reply.chars().filter(c -> c == '?').count();
    if (context.terminal()) {
      return failsIf(
          asked > 0, ReplyCheck.CLOSING_QUESTION, "closing reply must not ask a question");
    }
    return failsIf(
        !context.goalMet() && asked != 1,
        ReplyCheck.ONE_QUESTION,
        asked == 0 ? "no question; end with exactly one" : asked + " questions; ask exactly one");
  }

  /** The secret token from the prompt in a reply means the prompt leaked. */
  private static Stream<Violation> canary(String reply, String canary) {
    return failsIf(
        canary != null && reply.contains(canary), ReplyCheck.CANARY, "system prompt text leaked");
  }

  /** Backticks are not GSM-7, so normalisation drops them; they are looked for in the raw reply. */
  private static Stream<Violation> format(String raw, String reply) {
    return failsIf(
        raw.indexOf('`') >= 0 || MARKDOWN.matcher(reply).find(),
        ReplyCheck.FORMAT,
        "no code, markdown or bullet formatting");
  }

  private static Stream<Violation> contact(String reply, Lead lead) {
    return failsIf(
        echoesEmail(reply, lead.email()) || echoesPhone(reply, lead.phone()),
        ReplyCheck.PII,
        "do not repeat the lead's phone or email");
  }

  private static boolean echoesEmail(String reply, String email) {
    return email != null && reply.toLowerCase(Locale.ROOT).contains(email.toLowerCase(Locale.ROOT));
  }

  /** The lead's national digits inside one phone-shaped run, so other numbers never add up. */
  private static boolean echoesPhone(String reply, String phone) {
    String national = Phones.nationalDigits(phone);
    return PHONE_LIKE
        .matcher(reply)
        .results()
        .anyMatch(run -> NOT_DIGIT.matcher(run.group()).replaceAll("").contains(national));
  }

  private static Stream<Violation> slots(String reply) {
    return failsIf(
        TimeMentions.distinctTimes(reply) > MAX_SLOTS,
        ReplyCheck.MAX_SLOTS,
        "offer at most " + MAX_SLOTS + " times");
  }

  private static Stream<Violation> failsIf(boolean failed, ReplyCheck check, String note) {
    return failed ? Stream.of(new Violation(check, note)) : Stream.empty();
  }
}
