package com.enrola.anna.lead;

import java.util.Optional;
import org.springframework.data.repository.CrudRepository;

/** The lead source. A fake by decision: an H2 table seeded at boot, no HTTP client behind it. */
public interface LeadRepository extends CrudRepository<Lead, String> {

  /** The first lead with this E.164 phone; the console reuses it rather than adding a twin. */
  Optional<Lead> findFirstByPhone(String phone);
}
