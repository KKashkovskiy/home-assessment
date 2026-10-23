package com.enrola.anna.web;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The Evals page's live log over Server-Sent Events: the running run's kept lines, then each new
 * line, its progress and its end, as {@link EvalRunLauncher} sends them. With no run going, or the
 * live log off, the answer is 204, which tells the browser not to reconnect; the page shows what
 * the run left on its next load.
 */
@RestController
public class EvalRunStream {

  private final EvalRunLauncher launcher;

  public EvalRunStream(EvalRunLauncher launcher) {
    this.launcher = launcher;
  }

  @GetMapping(
      path = EvalsController.PATH + "/runs/current/events",
      produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public ResponseEntity<SseEmitter> subscribe() {
    return launcher
        .subscribe()
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }
}
