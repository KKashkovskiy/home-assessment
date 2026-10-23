package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** With the live log off the page is whole and says so, and the stream tells tabs to stop. */
@TestPropertySource(properties = "anna.ui.sse-enabled=false")
class TraceStreamOffTest extends ConsoleFixture {

  @Test
  void pageRendersAndTheStreamIs204() throws Exception {
    String lauren = start("lauren");

    String page = body(mvc.perform(get("/conversations/" + lauren)).andExpect(status().isOk()));

    assertThat(page)
        .contains(
            "<div id=\"turns\"",
            "data-live=\"false\"",
            ">live log off, reload to see new turns</span>");
    mvc.perform(get("/conversations/" + lauren + "/events")).andExpect(status().isNoContent());
  }
}
