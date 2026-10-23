package com.enrola.anna.lead;

import com.enrola.anna.AppClock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Leads the console enters by hand, beside the seeded three. The phone is stored in E.164 and the
 * consent evidence verbatim, stamped with the time it was entered.
 */
@Service
public class LeadService {

  /**
   * What the start form collects for a custom lead, already checked by the form. {@code phone} is
   * any Australian spelling; blank optional fields are stored as null.
   */
  public record CustomLead(
      String givenName,
      String phone,
      String email,
      String state,
      String currentProvider,
      String currentPremium,
      String consentSource) {}

  private final LeadRepository leads;
  private final AppClock clock;

  public LeadService(LeadRepository leads, AppClock clock) {
    this.leads = leads;
    this.clock = clock;
  }

  /** The lead a custom start uses, and whether it was already there. */
  public record Entered(Lead lead, boolean existing) {}

  /**
   * A new lead row for the form's details, or, when a lead already has this phone, that lead
   * unchanged, so its opt-out and open conversation still apply and nothing typed is saved. Throws
   * {@link IllegalArgumentException} for a phone that is not Australian.
   */
  public Entered createCustom(CustomLead custom) {
    String phone = Phones.toE164(custom.phone());
    return leads
        .findFirstByPhone(phone)
        .map(existing -> new Entered(existing, true))
        .orElseGet(() -> new Entered(leads.save(newLead(custom, phone)), false));
  }

  /** An unguessable id, like a conversation's. */
  private Lead newLead(CustomLead custom, String phone) {
    Instant now = clock.now();
    String consent = blankToNull(custom.consentSource());
    return new Lead(
        "l-" + UUID.randomUUID(),
        custom.givenName().strip(),
        phone,
        blankToNull(custom.email()),
        blankToNull(custom.state()),
        blankToNull(custom.currentProvider()),
        blankToNull(custom.currentPremium()),
        consent,
        consent == null ? null : now,
        now);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
