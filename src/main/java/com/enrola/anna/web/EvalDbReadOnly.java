package com.enrola.anna.web;

import com.enrola.anna.conversation.ConversationRepository;
import com.enrola.anna.conversation.MessageRepository;
import com.enrola.anna.conversation.NotFoundException;
import com.enrola.anna.conversation.TraceEventRepository;
import com.enrola.anna.conversation.TurnRepository;
import com.enrola.anna.prompt.PromptVersionRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.convert.ConversionException;
import org.springframework.dao.DataAccessException;
import org.springframework.data.jdbc.core.JdbcAggregateTemplate;
import org.springframework.data.jdbc.core.convert.DataAccessStrategyFactory;
import org.springframework.data.jdbc.core.convert.JdbcConverter;
import org.springframework.data.jdbc.core.convert.QueryMappingConfiguration;
import org.springframework.data.jdbc.core.dialect.JdbcDialect;
import org.springframework.data.jdbc.repository.support.JdbcRepositoryFactory;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a turn of an eval run from the run's own database, {@code evals/runs/<id>/db}, opened
 * read-only for as long as the read takes, through the app's own repositories and the inspector's
 * {@link TraceView}, so a failed trial's turn shows as the Conversations page would show it. It
 * never touches the app's database. A run database this build cannot open or read, one from an
 * older schema say, comes back unavailable rather than as an error page.
 */
@Component
public class EvalDbReadOnly {

  private static final Logger log = LoggerFactory.getLogger(EvalDbReadOnly.class);

  private final Path runs;
  private final JdbcConverter converter;
  private final JdbcDialect dialect;
  private final JsonMapper json;

  public EvalDbReadOnly(
      @Value("${anna.evals-dir}") Path evalsDir,
      JdbcConverter converter,
      JdbcDialect dialect,
      JsonMapper json) {
    this.runs = evalsDir.toAbsolutePath().normalize().resolve("runs");
    this.converter = converter;
    this.dialect = dialect;
    this.json = json;
  }

  /**
   * Turn {@code turnNo} of the run's conversation, masked as the session asks; empty when the run's
   * database is not there or does not read, and {@link NotFoundException} when it has no such
   * conversation or turn.
   */
  public Optional<TurnView> turn(String runId, String conversationId, int turnNo, boolean mask) {
    if (!runId.matches(EvalReports.RUN_ID)) {
      return Optional.empty();
    }
    Path database = runs.resolve(runId).resolve("db");
    if (!Files.exists(database.resolveSibling("db.mv.db"))) {
      return Optional.empty();
    }
    SingleConnectionDataSource db =
        new SingleConnectionDataSource(
            "jdbc:h2:file:" + database + ";ACCESS_MODE_DATA=r;IFEXISTS=TRUE", true);
    try {
      return Optional.of(
          traces(db)
              .turn(conversationId, turnNo, mask)
              .orElseThrow(() -> new NotFoundException("turn", conversationId + "/" + turnNo)));
    } catch (DataAccessException | ConversionException e) {
      log.warn("eval run {} trace unavailable: {}", runId, e.getClass().getSimpleName());
      return Optional.empty();
    } finally {
      db.destroy();
    }
  }

  /** The inspector's reader over the app's repositories, bound to the run's database. */
  private TraceView traces(SingleConnectionDataSource db) {
    var operations = new NamedParameterJdbcTemplate(db);
    var strategy =
        new DataAccessStrategyFactory(
                converter, operations, dialect, QueryMappingConfiguration.EMPTY)
            .create();
    var repositories = new JdbcRepositoryFactory(new JdbcAggregateTemplate(converter, strategy));
    return new TraceView(
        repositories.getRepository(ConversationRepository.class),
        repositories.getRepository(MessageRepository.class),
        repositories.getRepository(TurnRepository.class),
        repositories.getRepository(TraceEventRepository.class),
        repositories.getRepository(PromptVersionRepository.class),
        json);
  }
}
