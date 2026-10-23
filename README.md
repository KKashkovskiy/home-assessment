# Anna, Comparato's SMS appointment-setting agent

Enrola builds SMS agents for its customers. Comparato is one of them: an Australian health-insurance comparison
service with roughly 750 leads a week. Anna texts each lead, holds a short conversation in a fixed register, and
books a free 15-minute call with a human advisor. I wrote this README before the first class. The Quick start
describes the repo as it stands; every section after it is the plan, which each commit makes a little more true. It
is in decision order, not feature order: what I assumed, what is in, what is out, what each choice costs, where the
next person starts.

## Quick start

You need Java 25 on the path and nothing else. With SDKMAN, any Temurin 25 from `sdk list java` works; an Eclipse
Temurin 25 download does too. From the repo root:

```
./mvnw verify            # build, lint, format check, tests
./mvnw spring-boot:run   # Tomcat on http://localhost:8080
```

The first run downloads Maven and the dependencies. The database is an embedded H2 file under `data/`, created on
first boot and ignored by git.

No key is needed to build, test or run. Keys come from the environment only: export them in the shell, or copy
`.env.example` to `.env`, fill it in and load it with `set -a; . ./.env; set +a`. Spring does not read `.env` on its
own, and git ignores it. With `OPENAI_API_KEY` set, the app makes one small request at boot and logs
`model check ok: gpt-5.1`; a failure is a warning unless `anna.agent.strict-model-check` is true. Without it the log
says `model check skipped`. The key travels as a request header; the key itself is in no stored row or log line.

The server has no pages yet, so the root URL answers with a 404. The platform API is there: three leads are seeded
on first boot (`lauren`, `john`, `jane`). Start a conversation and read it back:

```
curl -s -X POST localhost:8080/api/conversations -H 'content-type: application/json' -d '{"lead_id":"lauren"}'
curl -s localhost:8080/api/conversations/<conversation_id>
```

Start answers 201 with the template opener as `reply`, the conversation's state and its transcript. A second start
for the same lead answers 409 with the open conversation's id (`?force=true` opens a second one anyway), an unknown
lead or conversation 404, a malformed request 400 and a non-JSON body 415; those errors carry `{"error", "message",
"conversation_id"}`. Post the lead's replies to the conversation:

```
curl -s -X POST localhost:8080/api/conversations/<conversation_id>/messages -H 'content-type: application/json' \
  -d '{"text":"yeah, save money","provider_message_id":"a1"}'
```

Each inbound is stored once: a retry with the same `provider_message_id` gets the first response back and writes
nothing. With a key, the model reads the whole transcript, replayed from the database on every turn, plus the turn's
context, and returns one JSON decision under a strict schema: intent, stage, flags, facts gathered and a line for
the advisor, then the reply. The reply comes back in the response; the decision, tokens, cost, latency, the model's
reasoning summary (never sent to the lead) and the raw request and response JSON are stored on the turn, with trace
events beside it. The model has no calendar to read, so it can ask for a call but cannot offer times. Without a key,
or when a call still fails after the SDK's retries or returns something that is not a decision, an ordinary reply is
stored, `needs_human` is set with `handoff_reason` `ERROR`, nothing is sent and the conversation stays open.

A whole message of STOP, STOPALL, UNSUBSCRIBE, OPTOUT, CANCEL, END or QUIT, ignoring case, punctuation and spaces,
ends the conversation `UNSUBSCRIBED`, returns the fixed confirmation and suppresses the phone: every later start for
it answers 409 `SUPPRESSED`, forced or not, and later messages on any of its conversations are stored without a
reply, save the safety template below.

Every inbound passes these guards before the model sees it, and every rule about how a conversation ends and stays
ended is code, not prompt. With a key, OpenAI's moderations endpoint screens each message but a STOP first, with its
own timeout (`anna.moderation.timeout`, 5 seconds, no retry); when it fails, or there is no key, the turn goes on and
the trace says moderation was unavailable. The checks run in this order and the first that matches decides the
turn; the rows after the call act on the model's decision.

| Check | What happens |
|---|---|
| Whole-message STOP keyword | ends `UNSUBSCRIBED`, suppresses the phone, sends the confirmation, even after another end; a repeat is silent |
| Moderation self-harm score at or above `anna.moderation.self-harm` | the Lifeline template once per conversation, flag `VULNERABLE`, handoff `SELF_HARM`; ends `SAFETY`, or after a hard end adds `SAFETY` to the history only |
| Ended hard: `UNSUBSCRIBED`, `ABUSIVE`, `SAFETY`, `OFF_TOPIC_LIMIT` | stored, no model call, no reply |
| Moderation harassment or threat score at or above its threshold | ends `ABUSIVE`, no reply, no model call |
| No key, or spend at `anna.budgets.app-cap-usd` or `conversation-cap-usd` | no model call, `needs_human` with `ERROR`, stays open |
| Ended soft: `BOOKED`, `DECLINED`, `MAX_TURNS`, `HANDOFF` | the model only classifies; RESCHEDULE or HANDOFF_REQUEST gets one `HANDOFF_ACK`, anything else at most one closing reply of 160 characters or fewer with no question mark, then silence flagged `LOOP_SUPPRESSED` |
| `anna.limits.max-turns` messages sent | `MAX_TURNS_CLOSE`, ends `MAX_TURNS` |
| After the call: intent OPT_OUT, ABUSIVE or SELF_HARM | the model's reply is discarded for the outcome of the matching row above; an opt-out is suppressed as `MODEL` |
| After the call: an objection beyond `anna.limits.objection-retries` | `DECLINED_CLOSE`, ends `DECLINED`; busy with a day named is a deferral, not counted |
| After the call: an OFF_TOPIC or INJECTION streak at `anna.limits.off-topic-limit` | `OFF_TOPIC_CLOSE`, ends `OFF_TOPIC_LIMIT`; a verbatim repeat adds to the streak whatever the model called it |
| After the call: `send_reply` false before any end | a bad decision: `needs_human` with `ERROR`, no reply |

The model may end a conversation `DECLINED` or `HANDOFF`, and a request for a person or a complaint ends it
`HANDOFF`; a fixed close or the model's own sign-off is the one closing reply. `goal_met`, `stage: BOOKED` and any
other end reason from the model are traced as `OVERRIDE` and ignored, because only a booking meets the goal.
`end_reason` only moves up, `UNSUBSCRIBED` over `SAFETY` over `ABUSIVE` over the rest, and every transition stays in
`end_reason_history`; `needs_human` is sticky and `handoff_reason` is the latest. Spend is the sum of `turn.cost_usd`.
A lead whose last conversation ended `ABUSIVE` gets 409 `ABUSIVE` on a plain start; only `?force=true` texts them
again. Moderation and the pre-guards write a `GUARD` trace event every turn, pass or not, the checks after the call
write one when they act, and each turn ends with a `STATE_DIFF` event naming the fields it changed. One gap is known:
while moderation is down, a self-harm message after a hard end gets silence, because the model is never asked on a
hard-terminal conversation.

Once a turn carries health information, message bodies from that turn on come back as `null` with
`"redacted": "HEALTH_INFO"`, unless the GET asks with `?includeHealth=true`.

A message for a conversation that is mid-turn waits two seconds, then gets 409 `BUSY`. Conversations, turns and
suppressions are rows, so a restart loses nothing.

`schema.sql` adds missing tables and columns but never changes existing ones, so delete `data/` after pulling a change
to one.

## What Anna does

Anna opens with a template, never a model call: who is texting, why, one situation question, and how to opt out.
From the lead's first reply a model drives a fixed script to one outcome, a booked 15-minute call with a human
advisor at a time the lead picked from real availability in their own timezone. Guards run in code around the model
call, not in the prompt: opt-out, abuse, self-harm, length, claims, goodbye loops, and what counts as a booking.

## Assumptions and defaults

Each assumption has a default the code is built to and the person whose word changes it. Later commits and the open
questions at the end of the build cite them by number.

**A1 Consent.** Comparato warrants SMS consent for every lead it sends. Optional consent fields on the lead record are
stored verbatim and never interpreted; the agent does not decide who may be texted. Default: trust the lead feed.
Sign-off: Comparato.

**A2 Persona and disclosure.** The agent is "Anna". The opener says she is an AI assistant, behind a flag that
defaults on. The sample transcript does not disclose, so this is a choice, and it is the one I would most expect to
be overruled. Default: disclose. Sign-off: Comparato, with Enrola's view on the record.

**A3 Opener claims.** No member counts and no savings claims in the opener. The sample's "find better value" promises
a savings outcome, which Anna never promises, in the opener or in a model reply. The register follows the sample
transcript: short, plain, Australian. Default: "review what they've got". Sign-off: Comparato and the product
designer, who owns the wording.

**A4 Sender identity.** Comparato is named in the opener. "Reply STOP to opt out" is always present and never varied,
because STOP handling relies on that exact instruction having been given. Later replies do not repeat it. Default:
as stated. Sign-off: Comparato.

**A5 External systems.** The Lead API and Calendly are deterministic fakes. The only live service is OpenAI. Neither
fake gets an HTTP client, not even behind configuration, so nothing pretends to be integrated when it is not.
Default: fakes. Sign-off: Enrola's CTO, who asked for stubs.

**A6 Scale.** One instance, an embedded H2 file database, in-process locks. At an assumed one-in-five reply rate, 750
leads a week is about 150 engaged conversations; at up to eight model turns each, that is at most 1,200 model calls a
week. One instance is idle almost all the time. Default: one instance. Sign-off: Enrola's CTO.

**A7 Keys.** Evals use a separate OpenAI key with a separate budget. Letting evals fall back to the app key is an
explicit opt-in flag, off by default, so a runaway suite cannot spend the production budget. Default: separate key,
no fallback. Sign-off: Enrola's CTO.

**A8 Lead timezone.** Derived from the lead's state. An unknown or missing state uses the advisor's zone and flags
the conversation `TZ_ASSUMED` so a person can check before the call. Default: advisor zone on unknown. Sign-off:
Comparato, who knows where the advisors sit.

**A9 Delivery.** The agent never sends SMS. The platform posts each inbound message to the API and reads the reply
from the response; the agent owns what is said, the platform owns that it was sent. Default: as stated. Sign-off:
Enrola's CTO.

## Included

The whole conversation engine: template opener, model-driven script, guards in code, scheduling in the lead's
timezone, booking as a database row, terminal states and the silence that follows them. The platform API with
idempotent inbound, so a message delivered twice is answered once. Four UI pages: Conversations, Configuration,
Analytics, Evals. Prompt versioning with an editor, activation, diff and per-conversation pinning, so "v3 is better
than v2" is a sentence with evidence behind it. An eval harness with turn scenarios, story scenarios, deterministic
gates and advisory judges, runnable at no cost without a key.

The build order is vertical slices. The gates come first, because nothing can be reviewed until format, lint and
tests exist. The opener and persistence come before the model, because they prove the API shape and the lead record
without spending a cent. Inbound persistence and STOP come before the model too, because the hardest requirement in
the brief is that a reply two days later still works, and STOP must work when the model is down. Only then the
model call, then the guards, then scheduling, then prompt versioning, then the UI in order of value, then the evals,
then the final docs.

## Left out and why

A real SMS gateway, real Calendly and Lead API clients, authentication, multi-tenant, multi-instance locking,
operator takeover, replay of old conversations against a new prompt, judge calibration, OpenTelemetry export,
follow-up nudges and reminders, a kill switch. None is needed to put the reviewer in front of a working agent, and
each would add code before the code that exists has been judged.

No Docker Compose, CI configuration or second Maven module: one reviewer, one laptop, one `./mvnw`. No Lombok,
MapStruct, Guava or Commons Lang: records and the standard library cover the project, and Lombok would hide fields
from Error Prone and from the reader.

Flyway is not there from the start. Until the database holds something a person typed, the designer's saved prompt
versions, every row can be replayed and a `schema.sql` of `IF NOT EXISTS` statements is enough. Dependencies join
the build with the code that first uses them, so the OpenAI SDK arrives with the first model call and Thymeleaf with
the first page.

## Trade-offs

A deterministic shell around one model call, over any agent framework. More code I own, less I can blame on a
library, and every rule that matters becomes code with a test rather than a sentence in a prompt.

H2 in file mode over Postgres. Zero setup for the reviewer and a database that survives a restart, which is what
the two-day pause needs. The cost is a migration if a second instance is ever needed.

Thymeleaf fragments over a JavaScript front end. Less interactivity and one rendering of every piece of markup, in
exchange for one language, one build and no bundler.

Fakes with no HTTP path over clients behind interfaces. The first real integration is new code rather than a swap,
and in exchange nothing in the repo claims to be tested against a service it has never seen.

Error Prone over Checkstyle or PMD. Spotless already owns style; Error Prone finds bugs, and every warning it raises
fails the build. Maven over Gradle because it is Enrola's stack and `./mvnw` has to work on a laptop with nothing
installed. Spring Data JDBC over JPA because rows written once per turn gain nothing from a persistence context.

## Extension

Everything customer-specific is data, not code. The prompt, knowledge pack, templates, decision schema and tools
form one versioned bundle edited in the UI, and runtime settings form one record. A second customer means a second
bundle, a second settings record and a second scenario set, plus a tenant column once that customer is signed and
not before. The pipeline, the guards and the fakes stay as they are.

## Privacy

Lead messages and the lead record go to OpenAI, which processes them outside Australia, and Comparato's privacy
notice should say so. The rules that follow: every model request sets `store` to false, so OpenAI keeps no
retrievable copy. Anna never asks about health conditions; a conversation where the lead volunteers one is flagged,
and its message bodies stay hidden in the API and the UI unless asked for. Logs carry identifiers and outcomes,
never message bodies or phone numbers. Keys come from the environment and are shown only as set or not set.
