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
events beside it. Any time it offers comes from the calendar described below. Without a key, or when a call still
fails after the SDK's retries or returns something that is not a decision, an ordinary reply is stored, `needs_human`
is set with `handoff_reason` `ERROR`, nothing is sent and the conversation stays open.

A whole message of STOP, STOPALL, UNSUBSCRIBE, OPTOUT, CANCEL, END or QUIT, ignoring case, punctuation and spaces,
ends the conversation `UNSUBSCRIBED`, returns the fixed confirmation and suppresses the phone: every later start for
it answers 409 `SUPPRESSED`, forced or not, and later messages on any of its conversations are stored without a
reply, save the safety template below. The one exception is CANCEL alone after a booking, which means the call, not
the messages.

Every inbound passes these guards before the model sees it, and every rule about how a conversation ends and stays
ended is code, not prompt. With a key, OpenAI's moderations endpoint screens each message but a STOP first, with its
own timeout (`anna.moderation.timeout`, 5 seconds, no retry); when it fails, or there is no key, the turn goes on and
the trace says moderation was unavailable. The checks run in this order and the first that matches decides the
turn; the rows after the call act on the model's decision.

| Check | What happens |
|---|---|
| CANCEL alone after a booking, before a hard end | `BOOKING_CHANGE_HANDOFF`, `needs_human` with `RESCHEDULE`, the booking kept, no model call; once acknowledged, a repeat gets nothing, flagged `LOOP_SUPPRESSED` |
| Whole-message STOP keyword | ends `UNSUBSCRIBED`, suppresses the phone, sends the confirmation, even after another end; a repeat is silent |
| Moderation self-harm score at or above `anna.moderation.self-harm` | the Lifeline template once per conversation, flag `VULNERABLE`, handoff `SELF_HARM`; ends `SAFETY`, or after a hard end adds `SAFETY` to the history only |
| Ended hard: `UNSUBSCRIBED`, `ABUSIVE`, `SAFETY`, `OFF_TOPIC_LIMIT` | stored, no model call, no reply |
| Moderation harassment or threat score at or above its threshold | ends `ABUSIVE`, no reply, no model call |
| The conversation's prompt version does not load, no key, or spend at `anna.budgets.app-cap-usd` or `conversation-cap-usd` | no model call, `needs_human` with `ERROR`, stays open |
| Ended soft: `BOOKED`, `DECLINED`, `MAX_TURNS`, `HANDOFF` | the model only classifies; RESCHEDULE or HANDOFF_REQUEST gets one `HANDOFF_ACK`, or after a booking RESCHEDULE gets `BOOKING_CHANGE_HANDOFF` with the booking kept; anything else at most one closing reply of 160 characters or fewer with no question mark, then silence flagged `LOOP_SUPPRESSED` |
| `anna.limits.max-turns` messages sent | `MAX_TURNS_CLOSE`, ends `MAX_TURNS` |
| After the call: intent OPT_OUT, ABUSIVE or SELF_HARM | the model's reply is discarded for the outcome of the matching row above; an opt-out is suppressed as `MODEL` |
| After the call: an objection beyond `anna.limits.objection-retries` | `DECLINED_CLOSE`, ends `DECLINED`; busy with a day named is a deferral, not counted |
| After the call: an OFF_TOPIC or INJECTION streak at `anna.limits.off-topic-limit` | `OFF_TOPIC_CLOSE`, ends `OFF_TOPIC_LIMIT`; a verbatim repeat adds to the streak whatever the model called it |
| After the call: `send_reply` false before any end | a bad decision: `needs_human` with `ERROR`, no reply |

The model may end a conversation `DECLINED` or `HANDOFF`, and a request for a person or a complaint ends it
`HANDOFF`; a fixed close or the model's own sign-off is the one closing reply. `goal_met`, `stage: BOOKED` and any
other end reason from the model are traced as `OVERRIDE` and ignored, because only a booking meets the goal: the
booking sets all three, and the confirmation on that turn leaves room for one closing reply. `end_reason` only moves
up, `UNSUBSCRIBED` over `SAFETY` over `ABUSIVE` over the rest, and every transition stays in `end_reason_history`;
STOP or abuse after a booking keeps the booking and hands off `BOOKING_REVIEW`, so a person cancels the call.
`needs_human` is sticky and `handoff_reason` is the latest. Spend is the sum of `turn.cost_usd`.
A lead whose last conversation ended `ABUSIVE` gets 409 `ABUSIVE` on a plain start; only `?force=true` texts them
again. Moderation and the pre-guards write a `GUARD` trace event every turn, pass or not, the checks after the call
write one when they act, and each turn ends with a `STATE_DIFF` event naming the fields it changed. One gap is known:
while moderation is down, a self-harm message after a hard end gets silence, because the model is never asked on a
hard-terminal conversation.

Every reply the model writes passes these checks before it is sent, on the text as the lead's phone will show it:
curly quotes, dashes and ellipses become plain GSM-7 characters and emoji are dropped, so the stored `char_count` is
what was measured. Every reply that would be sent writes one `GUARD` trace event per check, pass or not.

| Check | Fails when |
|---|---|
| `empty`, `length` | nothing is left, or more than `anna.sms.max-chars` (320) characters |
| `one-question`, `closing-question` | not exactly one question before the goal; any question in a reply that closes the conversation |
| `exclamation`, `format`, `canary` | an exclamation mark; code, markdown or bullets; the prompt's secret token, which means the prompt leaked |
| `url`, `pii` | a link off comparato.au or any email address; the lead's phone number or email |
| `deny` | a rule in `guards/deny-list.yaml` matches a sentence with no negation before it in its clause: savings promises, "all funds", human claims, product advice, member results, superlatives, and any fund name or dollar amount for a lead with no fund |
| `time-mention`, `max-slots` | a clock time that is not in the latest availability result or the booking; more than three times |

A reply that fails goes back to the model once, with the same transcript and context plus a note naming the failed
checks; the rejected reply never joins the transcript. A rewrite that passes is sent, marked `repaired` and flagged
`LENGTH_REPAIRED`; the turn keeps the first decision's classification and ending and records both calls' cost. When the
rewrite fails too, or the repair call fails, what follows depends on the state the decision left: on the turn the model
ended the conversation its fixed close goes instead, `DECLINED_CLOSE` or `HANDOFF_ACK`; while a person is asked to take
over nothing is sent; after the goal nothing is sent and `needs_human` is set with `ERROR`; otherwise the `FALLBACK`
template goes. Nothing is ever truncated, because a cut SMS reads worse than a short fixed one. After an end the one
closing reply gets the same checks and no repair, so one that fails is suppressed. The deny-list is loaded at boot, so a
broken file stops the app, and the tests run every rule's examples and fail a pattern no example exercises.

Anna reads a calendar: a deterministic fake and the only one there is (A5), with no HTTP client behind it. Slots
come from the advisors' hours in Melbourne, Monday to Friday 09:00 to 20:00 and Saturday to 17:00, never a Sunday, a
national public holiday or a date in `anna.scheduling.state-holidays` (the advisors' state), on a 30-minute grid for a
15-minute call. The same weekday hours hold on the lead's clock, so a Perth lead's first call is at 9:00 AWST, never
6:00. The model asks for dates on the lead's own clock through the `get_availability` tool and gets back at most six
slots, each an opaque id and a label in the lead's zone with its abbreviation: "Mon 27 Jul 12:00 PM AEST", AWST for a
Perth lead, still AEST for a Brisbane lead once Melbourne moves to AEDT on 4 October. All date arithmetic is
`java.time` on wall time in a zone; nothing adds hours. Nothing is offered sooner than an hour from now or later than
14 days out, and a request past that comes back empty with `BEYOND_HORIZON`, so the model has a fact to state. The
latest result replaces the last one on the conversation, the next turn's context lists it with the slot ids, the API
response shows its labels as `last_offered_slots`, and the `time-mention` check reads it.

The OpenAI client makes one round trip per call and knows nothing of tools beyond offering them; the agent answers the
model's function calls round by round, at most `anna.agent.max-tool-rounds` rounds a turn, the decision call and its
repair together. A decision call that still calls a tool after that fails `TOOL_ROUNDS` and hands off; a repair that
does falls back like any failed repair. After an end, the repair included, the model is offered no tools. Each round is
a `MODEL_CALL` trace event, numbered across the turn, and each function call a `TOOL_CALL` event with its arguments,
result, slot count and latency, in the order they happened; the turn stores the calls in `tool_calls_json`, its tokens
are the sum of its rounds' and its cost the sum of their costs. Every call gets an answer, never a stack trace. One the
model got wrong is rejected with a compact code (`BAD_ARGS`, `BAD_DATE`, `BAD_RANGE`, `UNKNOWN_TOOL`, `UNKNOWN_SLOT`,
`NO_PHONE`) and counts toward nothing, since the round cap bounds it; a calendar that refuses or a tool that breaks
(`SLOT_TAKEN`, `NOT_BOOKED`, `TOOL_FAILED`) counts, and a second such failure in a row, across turns, with no answer
between, sets `needs_human` with `ERROR`. `anna.clock.mode: FIXED` with `anna.clock.fixed-at` replays a conversation on
a known day, and `anna.scheduling.calendar-preset: fully_booked` empties the calendar.

`book_call` takes one argument, a `slot_id`, and books only an id from the latest availability result on the
conversation; any other is `UNKNOWN_SLOT` and the model asks for availability again. The calendar checks the slot again
at booking, because an offer goes stale between turns, and answers `NOT_BOOKED` for one no longer free;
`calendar-preset: slot_taken` makes it answer the first booking `SLOT_TAKEN`. Both go back to the model. Name, phone and
email come from the stored lead, never from the model, and a lead with no phone is `NO_PHONE`. A booking is a row with
the calendar's event id, the UTC start and the lead-local label, one per conversation by a unique index, and a repeat
`book_call` returns the booking already made. The row is the truth: it commits before its turn does, so a turn that dies
in between leaves a booking the conversation does not show, and the next turn meets the goal before anything else runs.
When the calendar booked but the row did not save, nobody knows whether the call is booked: the model is told not to
retry and `needs_human` is set with `ERROR`. Every response carries the booking as `booking`, and its label is a time
the `time-mention` check accepts. After a booking, a request to move or cancel the call sends the fixed
`BOOKING_CHANGE_HANDOFF` once with `handoff_reason` `RESCHEDULE`, keeps the booking and never books again; a lone CANCEL
after that is stored, flagged `LOOP_SUPPRESSED` and answered with nothing.

The prompt is a versioned artifact in the database. A version is one row holding the prompt, the knowledge pack, the
decision schema, the tool definitions and the ten templates, with an id `v1`, `v2` and so on. On first boot
`src/main/resources/prompts/v1/` is copied in as `v1`, byte for byte, and made active; a later `prompts/v2/`
directory is copied in the same way, inactive. Exactly one version is active, and only a new conversation reads it: the
id is stored on the conversation at start, every response carries it as `prompt_version`, and every later turn loads
that version, so activating `v2` (one statement, `UPDATE prompt_version SET active = (id = 'v2')`) never changes a
conversation that began on `v1`, and each turn row records the version the model saw. Saving creates the next id and
never overwrites; the only update the table gets is the one that moves `active`. A save is checked first, by the rules
in `src/main/resources/prompts/CHANGELOG.md`, which also says what changed in each version and how a saved one gets
back into the repo. A version that does not load cannot be activated, and the active one is loaded at boot, so a
setting that breaks it, a longer company name pushing an opener past 320 characters, stops the app before any lead
sees it. When a conversation's own version is gone or no longer loads, an `ERROR` trace event names it and the turn
runs without the model: STOP, the safety reply and a booking change still go out, with the active version's
templates, and anything else hands off with `ERROR` and leaves the conversation open.

Once a turn carries health information, message bodies from that turn on come back as `null` with
`"redacted": "HEALTH_INFO"`, unless the GET asks with `?includeHealth=true`.

A message for a conversation that is mid-turn waits two seconds, then gets 409 `BUSY`. Conversations, turns and
suppressions are rows, so a restart loses nothing.

The schema is a set of Flyway migrations under `src/main/resources/db/migration/`, applied on boot, and an existing
`data/` keeps its rows: one from before the migrations gets the first of them, the old `schema.sql`, which adds the
tables and columns it lacks.

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

Flyway was not there from the start. Until the database held something a person typed, the designer's saved prompt
versions, every row could be replayed, and a `schema.sql` of `IF NOT EXISTS` statements was enough at the price of
deleting `data/` after a column changed. Saved versions cannot be replayed, so from then on the schema is a baseline
migration, the schema as it stood, plus one migration per change. Dependencies join the build with the code that first
uses them, so the OpenAI SDK arrived with the first model call and Thymeleaf arrives with the first page.

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
