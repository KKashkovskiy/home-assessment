package com.enrola.anna.lead;

import org.springframework.data.repository.CrudRepository;

/** The lead source. A fake by decision: an H2 table seeded at boot, no HTTP client behind it. */
public interface LeadRepository extends CrudRepository<Lead, String> {}
