package com.enrola.anna.guard;

import com.enrola.anna.lead.Lead;
import com.enrola.anna.lead.Phones;
import com.enrola.anna.lead.Suppression;
import com.enrola.anna.lead.SuppressionRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * The phones that opted out. Once a phone is here no conversation starts for it, whichever lead
 * carries it. One row per phone; the first opt-out wins and is never updated.
 */
@Service
public class Suppressions {

  private final SuppressionRepository suppressions;

  public Suppressions(SuppressionRepository suppressions) {
    this.suppressions = suppressions;
  }

  public boolean optedOut(String phone) {
    return suppressions.existsById(Phones.toE164(phone));
  }

  /** Idempotent: a second opt-out for the same phone returns the first row and writes nothing. */
  public Suppression record(Lead lead, Suppression.Reason reason, Instant at) {
    String phone = Phones.toE164(lead.phone());
    return suppressions
        .findById(phone)
        .orElseGet(() -> suppressions.save(new Suppression(phone, reason, lead.id(), at)));
  }
}
