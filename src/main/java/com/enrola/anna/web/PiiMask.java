package com.enrola.anna.web;

import com.enrola.anna.guard.ReplyGuards;
import com.enrola.anna.lead.Lead;
import jakarta.servlet.http.HttpSession;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks what identifies the lead before the console renders it: the lead's phone and email,
 * anything phone- or email-shaped in a message or other free text, and free text about a
 * conversation that carries health information. The name is not PII for this console. The toggle
 * lives in the session and no attribute means masked, so a fresh window fails closed, and a value
 * the mask cannot read is hidden whole.
 */
public final class PiiMask {

  static final String SESSION_KEY = "piiMask";

  /** What stands in for text hidden because the conversation carries health information. */
  static final String HIDDEN = "[hidden by the PII mask: HEALTH_INFO]";

  private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");
  private static final Pattern WHOLE_EMAIL = Pattern.compile("(\\S)\\S*(@\\S+)");

  /** Every digit after the first three characters, except the last three digits of the run. */
  private static final Pattern MASKABLE_DIGIT = Pattern.compile("(?<=.{3})\\d(?=(?:\\D*\\d){3})");

  private PiiMask() {}

  /** True unless the session explicitly turned the mask off. */
  public static boolean enabled(HttpSession session) {
    return !Boolean.FALSE.equals(session.getAttribute(SESSION_KEY));
  }

  /** The lead with phone and email masked, and phone- or email-shaped text in the consent note. */
  public static Lead apply(Lead lead) {
    return new Lead(
        lead.id(),
        lead.givenName(),
        maskPhone(lead.phone()),
        maskEmail(lead.email()),
        lead.state(),
        lead.currentProvider(),
        lead.currentPremium(),
        lead.consentSource() == null ? null : maskText(lead.consentSource()),
        lead.consentAt(),
        lead.createdAt());
  }

  /** Phone numbers and email addresses a lead typed into a message. */
  public static String maskText(String text) {
    String phones =
        ReplyGuards.PHONE_LIKE
            .matcher(text)
            .replaceAll(run -> Matcher.quoteReplacement(maskPhone(run.group())));
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
