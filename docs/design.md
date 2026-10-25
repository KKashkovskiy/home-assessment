# Design notes

Enrola builds SMS agents for its customers. One of them is Comparato, an Australian health-insurance comparison service
with roughly 750 leads a week. Anna texts each lead, holds a short conversation in a fixed register, and books a free
15-minute call with a human advisor in a calendar slot.

Setup and launch are in the [README](../README.md).

## Assumptions and defaults

Each row is a decision the code is built to, with its default and the person who can change it.

| # | Assumption and default | Sign-off |
|---|---|---|
| A1 | Consent: Comparato warrants SMS consent for every lead it sends. Consent fields are stored verbatim and never interpreted | Comparato |
| A2 | Persona: "Anna", disclosed as an AI assistant in the opener (`anna.persona.disclosure-in-opener`, on). The sample transcript does not disclose, and this is the choice I most expect to be overruled | Comparato, with Enrola's view |
| A3 | Opener claims: no member counts and no savings promise. The opener says "review what they've got", never "find better value" | Comparato and the product designer |
| A4 | Sender: the opener names Comparato and always ends "Reply STOP to opt out", because STOP handling relies on it. Later replies omit it | Comparato |
| A5 | External systems: the Lead API and Calendly are deterministic fakes with no HTTP client. OpenAI is the only live service | Enrola's CTO, who asked for stubs |
| A6 | Scale: one instance, an H2 file, in-process locks; one lead in five replies | Enrola's CTO |
| A7 | Keys: from the environment only. Evals spend `EVAL_OPENAI_API_KEY` under a cap of their own, and spend the app's key only with `EVAL_ALLOW_SHARED_KEY=true` | Enrola's CTO |
| A8 | Lead timezone: from the lead's state. An unknown state takes the advisors' zone and flags `TZ_ASSUMED` | Comparato |
| A9 | Delivery: the platform posts each inbound to the API and sends the reply. Anna never sends SMS | Enrola's CTO |
| A10 | Calling hours: Melbourne, Monday to Friday 09:00 to 20:00 and Saturday to 17:00, never Sunday or a national holiday, and within those hours on the lead's clock too. Slots are 30 minutes, from an hour ahead to 14 days out. No state holidays are set (`anna.scheduling.state-holidays`) | Comparato |
| A11 | Spend caps: $10 in total for the app's model calls, $0.50 a conversation and $10 an eval run, sized for a reviewer's $50 key and editable in Configuration | Enrola's CTO |
| A12 | Moderation: a harassment or threat score of 0.5 ends a conversation as abusive. A self-harm score of 0.3 sends the safety template; the bar is lower because a needless safety message costs less than a missed one | Comparato, with Enrola's view |

## Open questions for sign-off

1. (A1) Did the enquiry form consent to SMS from Comparato only, or from a partner texting on its behalf?
2. (A2) Is proactive AI disclosure in the opener acceptable to Comparato?
3. (A3) Can "helping a lot of Bupa members" in the opener be substantiated, or should the opener drop it?
4. (A7) May the brief's key fund the simulated lead and the judges, or does Enrola provide an eval key?
5. (A10) Which state's public holidays apply, and do advisors work Saturdays?
6. (A11) What monthly model budget replaces the reviewer caps before real leads?

## How a conversation runs

Anna's opener is a fixed template with no model call. It says who is texting and why, asks one question about the
lead's situation, and says how to opt out. From the lead's first reply, a model drives a fixed script toward one
outcome: a call booked in a slot the lead picked from real availability, shown on the lead's own clock.

On each turn the model reads the whole transcript, replayed from the database, and the turn's context: counters, the
latest offer, the booking, and the lead record without phone or email. It may ask the calendar for slots and book one.
It returns one decision under a strict JSON schema: what the lead meant, the script stage, facts for the advisor, and
the reply.

Everything that must hold is code around that call. Before it, `guard/PreGuards` picks at most one outcome, in this
order (its Javadoc gives the same one):

1. A lone CANCEL after a booking hands the call to a person.
2. A whole-message STOP opts the phone out, with no model call.
3. A self-harm signal sends the Lifeline (Australian crisis line) message once. The signal comes from OpenAI's
   moderation or, when moderation does not answer, from a short list of clear self-harm phrases
   (`guards/self-harm-phrases.yaml`).
4. After a hard end (opted out, abusive, safety, off-topic limit) or a console reset, the conversation stays silent.
   The one exception: with moderation down and no phrase matched, the model is asked whether the message is self-harm,
   with no tools and no reply of its own.
5. Abuse ends the conversation in silence.
6. No key, a spent cap, or a prompt version that does not load hands the turn to a person.
7. After a soft end (booked, declined, turn cap, handed off), one short closing reply is allowed.
8. The turn cap sends a fixed close.

After the call, `guard/ReplyGuards` checks every reply as the phone will show it, normalised to GSM-7 (the SMS
character set). A reply is at most 320 characters, or 140 to a lead the model read as in distress, and asks exactly
one question until the goal is met. It has no exclamation mark, markdown, leaked prompt token, link outside
comparato.au, lead phone or email, deny-listed claim, or clock time that was not offered. A reply that fails gets one
rewrite, then a fixed fallback; nothing is truncated. A lead in distress whose reply fails twice gets no reply and is
handed to a person.

`conversation/StateRules` is the only code that ends a conversation or meets the goal, and only a booking row meets
it. The prompt is a versioned row. Each conversation pins the version it started on, so activating a new version never
changes a conversation in flight.

## The console

- Conversations: the list; the SMS thread, showing what wrote each reply and why a turn sent nothing; and the state.
  It has Start and Reset, and a status strip with the clock and calendar test controls, the spend, and the PII mask.
  The mask is on by default and hides phone numbers, emails and health details.
- Inspector, under the state: every turn from its stored rows, with guards, model rounds, tool calls, the decision,
  the state diff and the exact prompt sent.
- Configuration: the prompt editor (Monaco, the VS Code editor, loaded from a CDN) with versions, activation and diff,
  and the runtime settings below it.
- Analytics: figures computed from the rows. Each shows its numerator and base, gets a low-n badge under thirty, and,
  apart from the latency percentiles, links to the conversations it counted. Two prompt versions can be compared side
  by side.
- Evals: runs the suite, edits scenarios, rubrics and prompts through their loaders' checks, and renders reports.

The app has no login. It listens on 127.0.0.1 and refuses any request, `/api/` included, that arrives under another
host name or is posted from another site. Its pages refuse framing.

## How state survives a two-day pause

Nothing a conversation needs is held in memory between turns. A turn claims its number in its own transaction and
replays the transcript from the message rows into a stateless model call (`store` false, no previous response id).
It then commits the reply, the conversation, the turn row and its state diff together. Counters, the latest offer and
the booking are rows, and an offer that has gone stale is re-checked when the lead picks it. The only in-process state
is the per-conversation lock, held for one turn. `ConversationApiTest.Restart` boots the app twice on one database and
finds the conversation and its opt-out intact.

## Reading the code

Start with `conversation/InboundTurns` (one SMS to one turn), then `conversation/TurnPipeline` (the steps),
`guard/PreGuards`, `conversation/AgentTurn` and `agent/AgentService` (the model call and its tool loop),
`conversation/ReplyRepair` with `guard/ReplyGuards`, and `conversation/StateRules`. [`AGENTS.md`](../AGENTS.md)
sketches how they connect and holds the code rules. `PreGuardsTest`, `ReplyGuardsTest`, `StateRulesTest` and
`InboundTurnsTest` pin the rules. The commit history tells the build in order, and each commit body says why.

## Build order

The build went in vertical slices, each usable on its own. The gates came first, because nothing can be reviewed
until format, lint and tests exist. Persistence, the opener and STOP came before the model: the hardest requirement is
a reply two days later, STOP must work with the model down, and both can be proved without a key. The model call came
next, then the guards, so no page ever showed Anna unguarded. Scheduling followed, then prompt versioning, so the
editor is a view over tested behaviour. The console came in order of value: Conversations, the inspector,
Configuration, Analytics. The evals came late because they drive the finished pipeline, and the judges came last.

## Left out

A real SMS gateway, real Calendly and Lead API clients, authentication, more than one tenant or instance, operator
takeover, replay, judge calibration, telemetry export, nudges and a kill switch. None of them is needed to put a
reviewer in front of a working agent, and each would add code before the existing code is judged. There is no Docker,
CI or second Maven module, because one reviewer runs it on one laptop with `./mvnw`.

Where each would start:

| Item | Starts at |
|---|---|
| Real Calendly | `scheduling/FakeCalendar` becomes one implementation of a calendar interface; `FakeCalendarTest` is the contract |
| A monthly app cap, one spend ledger for the app and evals | `conversation/SpendCap` and the harness's `EvalBudget` |
| Evaluate a saved prompt version before activating it | the harness's `AppState`, which reads the active version |
| More than one instance | `conversation/TurnLocks` becomes a database row lock |
| More than one tenant | a tenant column, once a second customer is signed |
| OpenTelemetry export | `conversation/TracePublisher`, which publishes every trace event after commit |
| Kill switch | a settings flag read where `guard/PreGuards` decides whether the model may be called |
| Re-engage a declined lead, operator takeover | `conversation/StateRules`, which owns what may follow an end |
| Replay a conversation, add a turn to the eval set | the harness's `Seeder` seeds any transcript; `web/ScenarioEditor` writes scenarios |
| Judge calibration | each rubric's `examples` in `evals/rubrics/`, scored against designer labels |
| Nudges and reminders | a scheduled job beside `conversation/ConversationService` |

## Trade-offs

- A deterministic shell around one model call instead of an agent framework. I own more code, and every rule that
  matters is code with a test instead of a sentence in a prompt.
- H2 in file mode instead of Postgres: zero setup and a database that survives a restart. A second instance would need
  a migration.
- Thymeleaf instead of a JavaScript front end: one language, one build and no bundler, at the cost of some
  interactivity.
- Fakes with no HTTP path instead of clients behind interfaces. The first real integration is new code, and nothing
  claims to be tested against a service it has never seen.
- Error Prone over Checkstyle or PMD, because Spotless owns style and Error Prone finds bugs. Maven, because it is
  Enrola's stack. Spring Data JDBC over JPA, because rows written once per turn gain nothing from a persistence
  context.

## Measuring quality at scale, and which metric to distrust first

The gates stay deterministic. In the eval suite they are the hard checks and the core scenarios. In production they
are the Analytics figures, each a numerator over its base: guardrail violations against zero, booking rate of replied
leads, opt-outs split by STOP keyword and by model, handoffs by reason, and cost per booking.

The first metric to distrust is the judge pass rate. The judges are uncalibrated, and a judge that reads "fine" on
everything looks like a healthy product. Second is any rate on a base under thirty: Analytics badges it as low n but
draws no interval, and only eval runs report one, for their own trials. Third is anything measured on simulated leads,
who are politer and more consistent than people. A story's pass^k (the share of scenarios whose every trial passed)
bounds breakage and estimates no booking rate. A 100% offline run proves the harness works and says nothing about the
prompt. Before a judge gates anything, label a hundred real transcripts per rubric and measure the judge's agreement
with the labels.

A new prompt version has to prove itself twice. Evals run on the active version, so on a development database save
it, activate it, and run the core set and the stories with `--trials 3`. Each run records its version, and Analytics
shows the latest keyed run per version. In production, conversations pin their version, so after activation the old
and new versions are clean cohorts on the same figures. Decide once both bases pass thirty.

## 750 leads a week

The openers are templates, so a lead costs nothing until it replies. At one in five (A6), about 150 conversations a
week reach the model. A booking took four model turns in a keyed story. At an assumed six turns per engaged
conversation that is about 900 turns a week, or about $8 at the measured median, plus a free moderation call per
inbound. Even with every lead at the 15-reply cap it is 2,250 turns, about $30. That is about 130 turns a day at five
seconds each, so one instance is idle almost all the time and compute is not the constraint. What gives first:

1. The app spend cap. It is a lifetime total (A11) and runs out in about nine days at this rate, after which every
   turn hands off to a person. Raise it in Configuration; the fix is a monthly window.
2. The calendar. The fake never fills, so two leads can book one slot. One advisor's hours hold 126 starts a week (22
   a weekday, 16 on Saturday), and 150 engaged leads could want more. With Calendly the ceiling is the advisor seats
   behind the event type.
3. Storage. Each model turn stores about 50 KB of request and response JSON: 2.3 GB a year in one H2 file, with no
   retention.
4. A second instance. The H2 file and the per-conversation lock are single-process; nothing else holds state.

## A second customer

Everything customer-specific is data. A second customer needs a prompt bundle under `src/main/resources/prompts/vN/`
(prompt, knowledge pack, schema, tools, templates); the persona, company, hours and zone in the runtime settings; the
state-to-zone table in `scheduling/LeadZones` if the leads are not Australian; and a scenario set. The deny-list and
the rubrics name Comparato's claims, so they are rewritten with the knowledge pack. The pipeline, guards, scheduling,
persistence, API and console stay as they are.

## Privacy

Lead messages and the lead record go to OpenAI, which processes them outside Australia. Comparato's privacy notice
should say so under APP 8 (Australian Privacy Principle 8, cross-border disclosure). Every model request sets `store`
false, and phone and email never reach the model. The prompt forbids asking about health. Once the model flags a turn
as health information, the API returns later message bodies as null unless asked with `?includeHealth=true`, and the
PII mask hides them in the console. Eval leads are synthetic. Logs carry ids and outcomes, never message bodies or
phone numbers. Keys travel only as a request header and show only as set or not set. Nothing is deleted: messages and
each turn's raw request and response stay in `data/` until a retention period is set.

## Known issues

- If moderation and the model are both unavailable after a hard end, only the phrase list is checked, and any other
  wording gets silence. The same holds when a spend cap, a missing key or a pinned prompt that does not load keeps the
  model out; the trace says which. While moderation is down, each new message after a hard end that does not repeat
  the one before costs one classify call, bounded by the conversation and app spend caps.

## Validation runs and costs

Cheapest first. Steps 1 and 2 need no key. Steps 5 to 7 need `EVAL_OPENAI_API_KEY`, the eval suite's own key.

| # | Run | Expect | Cost | Total |
|---|---|---|---|---|
| 1 | `./mvnw verify` | `BUILD SUCCESS`, 1,427 tests | $0 | $0 |
| 2 | `evals/run.sh --offline` | every scenario answered by stand-ins (code that plays the model): report verdict PASS, 52 of 52 | $0 | $0 |
| 3 | Export `OPENAI_API_KEY`, `./mvnw spring-boot:run` | log `model check ok: gpt-5.1`, no banner | one small call | $0 |
| 4 | Start Lauren, reply "all of the above" | an `LLM` reply under 320 characters; the inspector shows the guards, the model round and the decision | about $0.01 | $0.01 |
| 5 | `evals/run.sh --scenario turn-are-you-a-bot` | exit code 0, report verdict PASS, a Judges section (LLM judges, advisory) | $0.01, judges $0.01 | $0.03 |
| 6 | `evals/run.sh --tag core` | exit code 0 and report verdict PASS over 20 turn scenarios (one turn each) and 6 stories (whole conversations against a simulated lead) | $0.27, judges $0.64 | $0.94 |
| 7 | `evals/run.sh --kind story` | all eight stories once | about $0.30, judges $0.15 | $1.39 |
| 8 | Play a rude, a confused and a one-word lead | replies under 320 characters; the guards that fired, in the inspector | about $0.05 each | $1.54 |

Costs are for gpt-5.1 at low effort, measured on keyed runs during the build on 23 and 24 October 2026. Eighteen turn
rows cost $0.0029 to $0.0135 (median $0.0085) and took 2.3 to 24.1 seconds (median 5.3). A judge question costs about
$0.002.

Step 6 was measured directly. The keyed core run on prompt v2 on 24 October 2026 exited 0 with verdict PASS, pass^k
100% (26 of 26) and every gate passing, for $0.27 plus $0.64 of judges, one trial each. A rerun on the final code
matched it for $0.24 plus $0.57. The booking failures passed on v2 the same day: a slot taken between offer and
booking in every core run, and a booking the calendar refuses (`turn-booking-fails`, outside the core set) in one
keyed trial. Steps 7 and 8 are estimated from the turn figures, because no full keyed story run was made.
`evals/run.sh --dry-run --tag core` prints the harness's ceiling ($1.43, judges at most $0.99), above which a run is
refused. Each report header has the exact cost.

## Where AI was used

A coding agent (Claude Code; every commit carries its `Co-Authored-By` trailer) wrote the code and these docs one
commit at a time, from per-commit guides I wrote outside the repo. Each commit was reviewed against its guide and
`AGENTS.md` before it was made, and the commit bodies say what the reviews changed. About $0.35 of OpenAI usage was
recorded across the build's validation runs (per-commit ledger; boot model checks are not recorded). Coding-agent
usage is billed separately and not recorded in the repo.

With more time I would delete:

- the repair call in `conversation/ReplyRepair`, if Analytics shows replies almost never fail a check (the checks and
  the fallback stay);
- the keyword story stand-in, once every developer has an eval key;
- scenario-level judge questions, once the rubrics cover them;
- the scenario editor's form view, if the designer edits the YAML.

## Repo layout

```
src/main/java/com/enrola/anna/
  lead/           leads, phones, opt-out suppressions, the seeded three
  conversation/   the turn: InboundTurns, TurnPipeline, StateRules, the rows and trace events
  agent/          the model: decision vocabulary, the tool loop, the OpenAI client, prices
  guard/          pre-guards, moderation, self-harm phrases, reply checks, the deny-list, STOP keywords
  scheduling/     lead zones, the fake calendar, the two tools, bookings
  prompt/         prompt versions, templates, GSM-7
  config/         the runtime settings record and its store
  eval/           the scenario and rubric models the Evals page shares with the harness
  web/            the API and the console pages
src/main/resources/   application.yml, prompts/vN/, db/migration/, guards/deny-list.yaml, templates/, static/
src/test/java/com/enrola/anna/eval/   the eval harness: runner, checks, stand-ins, simulator, judges, report
evals/                scenarios, rubrics, simulator and judge prompts, run.sh; runs/ is ignored
data/                 the H2 file and the console's saved settings; ignored
```
