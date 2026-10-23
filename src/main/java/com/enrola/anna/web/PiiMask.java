package com.enrola.anna.web;

import com.enrola.anna.guard.ReplyGuards;
import com.enrola.anna.lead.Lead;
import jakarta.servlet.http.HttpSession;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/**
 * Masks what identifies the lead before the console renders it: the lead's phone and email,
 * anything phone- or email-shaped in a message or other free text, and free text about a
 * conversation that carries health information. The name is not PII for this console, though
 * anything phone- or email-shaped typed into it is. The toggle lives in the session and no
 * attribute means masked, so a fresh window fails closed, and a value the mask cannot read is
 * hidden whole.
 */
public final class PiiMask {

  static final String SESSION_KEY = "piiMask";

  /** What stands in for text hidden because the conversation carries health information. */
  static final String HIDDEN = "[hidden by the PII mask: HEALTH_INFO]";

  private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");
  private static final Pattern WHOLE_EMAIL = Pattern.compile("(\\S)\\S*(@\\S+)");

  private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

  /** Every digit after the first three characters, except the last three digits of the run. */
  private static final Pattern MASKABLE_DIGIT = Pattern.compile("(?<=.{3})\\d(?=(?:\\D*\\d){3})");

  private PiiMask() {}

  /** True unless the session explicitly turned the mask off. */
  public static boolean enabled(HttpSession session) {
    return !Boolean.FALSE.equals(session.getAttribute(SESSION_KEY));
  }

  /**
   * The lead with phone and email masked, and phone- or email-shaped text in the fields a custom
   * lead's form takes as typed: the name, the premium and the consent note.
   */
  public static Lead apply(Lead lead) {
    return new Lead(
        lead.id(),
        maskText(lead.givenName()),
        maskPhone(lead.phone()),
        maskEmail(lead.email()),
        lead.state(),
        lead.currentProvider(),
        maskText(lead.currentPremium()),
        maskText(lead.consentSource()),
        lead.consentAt(),
        lead.createdAt());
  }

  /**
   * Phone numbers and email addresses a lead typed into a message, or into any other free text;
   * null stays null. An ISO date is phone-shaped but no phone, so it stays as it is.
   */
  public static String maskText(String text) {
    if (text == null) {
      return null;
    }
    String phones =
        ReplyGuards.PHONE_LIKE
            .matcher(text)
            .replaceAll(
                run ->
                    Matcher.quoteReplacement(
                        ISO_DATE.matcher(run.group()).matches()
                            ? run.group()
                            : maskPhone(run.group())));
    return EMAIL.matcher(phones).replaceAll("***@***");
  }

  /**
   * Free text about the lead that the model wrote, such as its summary or a fact it extracted:
   * hidden whole once the conversation carries health information, otherwise with phones and emails
   * masked.
   */
  public static String freeText(String text, boolean healthInfo) {
    if (text == null) {
      return null;
    }
    return healthInfo ? HIDDEN : maskText(text);
  }

  /**
   * {@link #maskText} over every string in a JSON tree, keys, numbers and structure untouched, so a
   * masked request or response is still the same JSON.
   */
  public static JsonNode maskJson(JsonNode node) {
    return switch (node) {
      case ObjectNode object -> {
        ObjectNode masked = object.objectNode();
        object
            .properties()
            .forEach(field -> masked.set(field.getKey(), maskJson(field.getValue())));
        yield masked;
      }
      case ArrayNode array -> {
        ArrayNode masked = array.arrayNode();
        array.forEach(item -> masked.add(maskJson(item)));
        yield masked;
      }
      default -> node.isString() ? StringNode.valueOf(maskText(node.stringValue())) : node;
    };
  }

  /** {@code +61412345678} becomes {@code +61••••••678}: the country code and last three kept. */
  static String maskPhone(String phone) {
    return phone == null ? null : MASKABLE_DIGIT.matcher(phone).replaceAll("•");
  }

  /** {@code lauren@example.com} becomes {@code l***@example.com}; anything else {@code ***}. */
  static String maskEmail(String email) {
    if (email == null) {
      return null;
    }
    Matcher whole = WHOLE_EMAIL.matcher(email);
    return whole.matches() ? whole.group(1) + "***" + whole.group(2) : "***";
  }
}
