package com.enrola.anna.lead;

import org.springframework.data.repository.CrudRepository;

/** Suppression rows keyed by E.164 phone. */
public interface SuppressionRepository extends CrudRepository<Suppression, String> {}
