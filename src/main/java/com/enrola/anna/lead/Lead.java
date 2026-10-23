package com.enrola.anna.lead;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;

/**
 * A lead as the platform gave it to us. Phone is E.164. Consent fields are optional and stored
 * verbatim. Insert-only: a lead row is never updated, so {@link #isNew()} is always true and {@code
 * save} inserts, which a record with a pre-assigned id would otherwise not do.
 */
public record Lead(
    @Id String id,
    String givenName,
    String phone,
    String email,
    String state,
    String currentProvider,
    String currentPremium,
    String consentSource,
    Instant consentAt,
    Instant createdAt)
    implements Persistable<String> {

  /** True when the provider is a fund name; false for null, blank or "none" in any case. */
  public boolean hasProvider() {
    return currentProvider != null
        && !currentProvider.isBlank()
        && !currentProvider.strip().equalsIgnoreCase("none");
  }

  @Override
  public String getId() {
    return id;
  }

  @Override
  public boolean isNew() {
    return true;
  }
}
