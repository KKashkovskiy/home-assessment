package com.enrola.anna.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

/** The status strip and banner with no key, on a fixed clock, and a PII mask that fails closed. */
class ShellModelAdviceTest extends ConsoleFixture {

  @Test
  void noKeyShowsTheBannerAndTheModelAsUnavailable() throws Exception {
    String page = body(mvc.perform(get("/conversations")));

    assertThat(page).contains("data-banner=\"NO_KEY\"", "data-agent=\"NO_KEY\"");
    assertThat(page).contains("<b>gpt-5.1</b> unavailable", "prompt v1", "$0.00 of $10.00");
  }

  @Test
  void everyConsolePageGetsTheShell() throws Exception {
    assertThat(body(mvc.perform(get("/evals")))).contains("data-banner=\"NO_KEY\"");
  }

  @Test
  void fixedClockShowsTheFixedBadgeInTheAdvisorsZone() throws Exception {
    assertThat(body(mvc.perform(get("/conversations"))))
        .contains("Clock <b>FIXED</b>", "2026-07-26 10:00 AEST");
  }

  @Test
  void maskIsOnWithoutASessionAttributeAndTheToggleTurnsItOffForThatSessionOnly() throws Exception {
    String id = start("lauren");
    MockHttpSession session = new MockHttpSession();

    assertThat(body(mvc.perform(get("/conversations/" + id))))
        .contains("+61••••••001, l***@example.com", "aria-pressed=\"true\"")
        .doesNotContain("+61400000001", "lauren@example.com");
    mvc.perform(
            post("/ui/pii-mask")
                .param("on", "false")
                .header("Referer", "http://elsewhere.example/conversations/" + id + "?q={x}")
                .session(session))
        .andExpect(status().is3xxRedirection())
        .andExpect(header().string("Location", "/conversations/" + id + "?q={x}"));
    assertThat(body(mvc.perform(get("/conversations/" + id).session(session))))
        .contains("+61400000001, lauren@example.com", "aria-pressed=\"false\"");
    assertThat(body(mvc.perform(get("/conversations/" + id)))).contains("+61••••••001");
  }

  @Test
  void toggleNeverRedirectsOffThisHost() throws Exception {
    mvc.perform(
            post("/ui/pii-mask")
                .param("on", "true")
                .header("Referer", "http://x.example//evil.example/a"))
        .andExpect(header().string("Location", "/conversations"));
  }

  @Test
  void toggleWithoutAValueIs400InTheShell() throws Exception {
    assertThat(body(mvc.perform(post("/ui/pii-mask")).andExpect(status().isBadRequest())))
        .contains("data-notice=\"BAD_REQUEST\"", "class=\"tabs\"");
  }
}
