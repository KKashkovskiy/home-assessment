package com.enrola.anna.web;

import com.enrola.anna.lead.LeadService;
import com.enrola.anna.lead.Phones;
import com.enrola.anna.scheduling.LeadZones;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The start panel: a seeded lead's id, or {@code custom} with the lead's details bound as {@code
 * custom.*}. The limits are the lead table's column widths, so a bad field is a message beside it,
 * never a failed insert.
 */
public record StartForm(String lead, LeadService.CustomLead custom) {

  public static final StartForm EMPTY =
      new StartForm(
          "lauren", new LeadService.CustomLead(null, null, null, "VIC", null, null, null));

  private static final String CUSTOM = "custom";
  private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+");
  private static final LeadService.CustomLead BLANK =
      new LeadService.CustomLead(null, null, null, null, null, null, null);

  /** One check on one field; {@code broken} when the field fails it. */
  private record Rule(String field, boolean broken, String message) {}

  public boolean customSelected() {
    return CUSTOM.equals(lead);
  }

  /** Empty when the form can be acted on; otherwise one message per bad field, keyed by name. */
  public Map<String, String> errors() {
    return rules().stream()
        .filter(Rule::broken)
        .collect(
            Collectors.toMap(Rule::field, Rule::message, (first, _) -> first, LinkedHashMap::new));
  }

  private List<Rule> rules() {
    if (!customSelected()) {
      return List.of(new Rule("lead", blankOrLongerThan(lead, 40), "Pick a lead"));
    }
    LeadService.CustomLead details = custom == null ? BLANK : custom;
    return List.of(
        new Rule(
            "givenName",
            blankOrLongerThan(details.givenName(), 80),
            "Given name is required, up to 80 characters"),
        new Rule(
            "phone",
            !australian(details.phone()),
            "Must be an Australian number, e.g. 0412 345 678 or +61412345678"),
        new Rule(
            "email",
            filled(details.email())
                && (longerThan(details.email(), 200)
                    || !EMAIL.matcher(details.email().strip()).matches()),
            "Must be an email address, up to 200 characters"),
        new Rule(
            "state",
            filled(details.state()) && !LeadZones.known(details.state()),
            "Pick a state, or unknown"),
        new Rule(
            "currentProvider", longerThan(details.currentProvider(), 40), "Up to 40 characters"),
        new Rule("currentPremium", longerThan(details.currentPremium(), 40), "Up to 40 characters"),
        new Rule(
            "consentSource", longerThan(details.consentSource(), 200), "Up to 200 characters"));
  }

  private static boolean australian(String phone) {
    try {
      Phones.toE164(phone == null ? "" : phone);
      return true;
    } catch (IllegalArgumentException _) {
      return false;
    }
  }

  private static boolean filled(String value) {
    return value != null && !value.isBlank();
  }

  private static boolean longerThan(String value, int max) {
    return value != null && value.strip().length() > max;
  }

  private static boolean blankOrLongerThan(String value, int max) {
    return !filled(value) || longerThan(value, max);
  }
}
