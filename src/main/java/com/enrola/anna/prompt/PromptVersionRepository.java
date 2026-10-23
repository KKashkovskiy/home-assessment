package com.enrola.anna.prompt;

import java.util.Optional;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

/** Prompt version rows. The one update flips {@code active}; content columns have none. */
public interface PromptVersionRepository extends CrudRepository<PromptVersion, String> {

  Optional<PromptVersion> findByActiveTrue();

  /**
   * One statement makes {@code id} the only active row. Callers check that {@code id} exists: for
   * an unknown id no row stays active.
   */
  @Modifying
  @Query("update prompt_version set active = (id = :id)")
  void activate(String id);
}
