package com.enrola.anna.lead;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;

/**
 * An opted-out phone, keyed by E.164. Insert-only: the first opt-out is kept, so {@link #isNew()}
 * is always true and {@code save} inserts.
 */
public record Suppression(@Id String phone, Reason reason, String leadId, Instant createdAt)
    implements Persistable<String> {

  /**
   * How the opt-out was recognised. KEYWORD: a whole-message STOP keyword. MODEL: the model read an
   * opt-out in the lead's own words.
   */
  public enum Reason {
    KEYWORD,
    MODEL
  }

  @Override
  public String getId() {
    return phone;
  }

  @Override
  public boolean isNew() {
    return true;
  }
}
