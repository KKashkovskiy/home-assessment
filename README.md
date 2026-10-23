# Anna, Comparato's SMS appointment-setting agent

Enrola builds SMS agents for its customers. Comparato is one of them: an Australian health-insurance comparison service
with roughly 750 leads a week. Anna texts each lead, holds a short conversation in a fixed register, and books a free
15-minute call with a human advisor in a calendar slot. Java 25, Spring Boot 4.1.2, H2 on disk, server-rendered
Thymeleaf; the only external service is OpenAI.

## Quick start

You need Java 25 on the path and nothing else; any Temurin 25 works, for example from SDKMAN's `sdk list java`.

```
git clone <repo-url> anna && cd anna
./mvnw verify            # format check, Error Prone, tests; no key needed
./mvnw spring-boot:run   # the console on http://localhost:8080
```

For another port, `./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=18080`. On Windows use `mvnw.cmd`;
`evals/run.sh` and the Evals page call the POSIX `mvnw`, so run those under WSL. The database is an H2 file under
`data/`; delete it to start over. Without a key the console shows "Agent unavailable": STOP and the fixed templates
work, and every other lead message is stored and handed to a person. To talk to the model, export `OPENAI_API_KEY`, or
fill in a copy of `.env.example` and load it with `set -a; . ./.env; set +a` (Spring does not read `.env`). Then, in the
console, press Start a conversation, pick Lauren and type as the lead, or call the API the SMS platform calls:

```
curl -s -X POST localhost:8080/api/conversations -H 'content-type: application/json' -d '{"lead_id":"lauren"}'
curl -s -X POST localhost:8080/api/conversations/<conversation_id>/messages -H 'content-type: application/json' \
  -d '{"text":"yeah, save money","provider_message_id":"a1"}'
```

Every response carries the reply to send (or `no_reply` with the reason there is none), `goal_met`, `unsubscribed`, the
booking and the transcript. The seeded leads are `lauren`, `john` and `jane`.

## Proof it works, and what to run after adding a key

Cheapest first. Steps 1 and 2 need no key; steps 5 to 7 need `EVAL_OPENAI_API_KEY`, the eval suite's own key.

| # | Run | Expect | Cost | Total |
|---|---|---|---|---|
| 1 | `./mvnw verify` | `BUILD SUCCESS`, 1,319 tests | $0 | $0 |
| 2 | `evals/run.sh --offline` | every scenario answered by stand-ins, code that plays the model: report verdict PASS, 52 of 52 | $0 | $0 |
| 3 | Export `OPENAI_API_KEY`, `./mvnw spring-boot:run` | log `model check ok: gpt-5.1`, no banner | one small call | $0 |
| 4 | Start Lauren, reply "all of the above" | an `LLM` reply under 320 characters; the inspector shows the guards, the model round and the decision | about $0.01 | $0.01 |
| 5 | `evals/run.sh --scenario turn-are-you-a-bot` | exit code 0, report verdict PASS, a Judges section (LLM judges, advisory) | $0.01, judges $0.01 | $0.03 |
| 6 | `evals/run.sh --tag core` | exit code 0 and report verdict PASS over 20 turn scenarios (one turn each) and 6 stories (whole conversations against a simulated lead) | about $0.45, judges $0.40 | $0.90 |
| 7 | `evals/run.sh --kind story` | all eight stories once | about $0.30, judges $0.15 | $1.35 |
| 8 | Play a rude, a confused and a one-word lead | replies under 320 characters; the guards that fired, in the inspector | about $0.05 each | $1.50 |

Costs are gpt-5.1 at low effort, measured on keyed runs during the build (23 and 24 October 2026): 18 turn rows cost
$0.0029 to $0.0135, median $0.0085, and took 2.3 to 24.1 seconds, median 5.3; a judge question costs about $0.002. Steps
6 to 8 multiply those out, because no full keyed core or story run was made. `evals/run.sh --dry-run --tag core` prints
the harness's ceiling ($1.43, judges at most $0.99), above which a run is refused; each report header has the exact
cost.

## Assumptions and defaults

Each is a decision with a default the code is built to, and the person whose word changes it.

| # | Assumption and default | Sign-off |
|---|---|---|
| A1 | Consent: Comparato warrants SMS consent for every lead it sends; consent fields are stored verbatim, never interpreted | Comparato |
| A2 | Persona: "Anna", disclosed as an AI assistant in the opener (`anna.persona.disclosure-in-opener`, on). The sample transcript does not disclose; this is the choice I most expect to be overruled | Comparato, with Enrola's view |
| A3 | Opener claims: no member counts and no savings promise; "review what they've got", never "find better value" | Comparato and the product designer |
| A4 | Sender: the opener names Comparato and ends "Reply STOP to opt out", never varied, because STOP handling relies on it; later replies omit it | Comparato |
| A5 | External systems: the Lead API and Calendly are deterministic fakes with no HTTP client; OpenAI is the only live service | Enrola's CTO, who asked for stubs |
| A6 | Scale: one instance, an H2 file, in-process locks; one lead in five replies | Enrola's CTO |
| A7 | Keys: from the environment only. Evals spend `EVAL_OPENAI_API_KEY` under a cap of their own, the app's key only with `EVAL_ALLOW_SHARED_KEY=true` | Enrola's CTO |
| A8 | Lead timezone: from the lead's state; an unknown state takes the advisors' zone and flags `TZ_ASSUMED` | Comparato |
| A9 | Delivery: the platform posts each inbound to the API and sends the reply; Anna never sends SMS | Enrola's CTO |
| A10 | Calling hours: Melbourne, Monday to Friday 09:00 to 20:00 and Saturday to 17:00, never Sunday or a national holiday, on the lead's clock too; 30-minute slots from an hour ahead to 14 days out; no state holidays set (`anna.scheduling.state-holidays`) | Comparato |
| A11 | Spend caps: $10 in total for the app's model calls, $0.50 a conversation, $10 an eval run, sized for a reviewer's $50 key and editable in Configuration | Enrola's CTO |
| A12 | Moderation: a harassment or threat score of 0.5 ends a conversation as abusive; a self-harm score of 0.3 sends the safety template, lower because a needless one costs less than a missed one | Comparato, with Enrola's view |

## Open questions for sign-off

1. (A1) Did the enquiry form consent to SMS from Comparato only, or from a partner texting on its behalf?
2. (A2) Is proactive AI disclosure in the opener acceptable to Comparato?
3. (A3) Can "helping a lot of Bupa members" in the opener be substantiated, or should the opener drop it?
4. (A7) May the brief's key fund the simulated lead and the judges, or does Enrola provide an eval key?
5. (A10) Which state's public holidays apply, and do advisors work Saturdays?
6. (A11) What monthly model budget replaces the reviewer caps before real leads?

## What Anna does

Anna opens with a fixed template, never a model call: who is texting, why, one question about the lead's situation, and
how to opt out. From the lead's first reply a model drives a fixed script to one outcome, a call booked in a slot the
lead picked from real availability on their own clock. Each turn the model reads the whole transcript, replayed from the
database, and the turn's context (counters, the latest offer, the booking, the lead record without phone or email); it
may ask the calendar for slots and book one; and it returns one decision under a strict JSON schema: what the lead
meant, the script stage, facts for the advisor, and the reply.

Everything that must hold is code around that call. Before it, `guard/PreGuards` picks at most one outcome, in the order
its Javadoc gives: a lone CANCEL after a booking hands the call to a person; a whole-message STOP opts the phone out
with no model call; a self-harm signal from OpenAI's moderation sends the Lifeline (Australian crisis line) message
once; a hard end (opted out, abusive, safety, off-topic limit) or a console reset stays silent; abuse ends the
conversation in silence; no key, a spent cap or a prompt version that does not load hands the turn to a person; a soft
end (booked, declined, turn cap, handed off) allows one short closing reply; the turn cap sends a fixed close. After it,
`guard/ReplyGuards` checks every reply as the phone will show it, normalised to GSM-7 (the SMS character set): at most
320 characters, exactly one question before the goal, no exclamation mark, markdown, leaked prompt token, link off
comparato.au, lead phone or email, deny-listed claim, or clock time that was not offered. A failed reply gets one
rewrite, then a fixed fallback; nothing is truncated. `conversation/StateRules` is the only code that ends a
conversation or meets the goal, and only a booking row meets it. The prompt is a versioned row, and each conversation
pins the version it started on, so activating a new one never changes a conversation in flight.

## The console

- **Conversations**: the list, the SMS thread with what wrote each reply and why a turn sent nothing, and the state;
  Start, Reset, and a status strip with the clock and calendar test controls, spend, and the PII mask, on by default,
  which hides phone numbers, emails and health details.
- **Inspector**, under the state: every turn from its stored rows, guards, model rounds, tool calls, decision, state
  diff, and the exact prompt sent.
- **Configuration**: the prompt editor (Monaco, the VS Code editor, from a CDN) with versions, activation and diff; the
  runtime settings below it.
- **Analytics**: figures computed from the rows, each with its numerator, its base and a low-n badge under thirty, and
  each but the latency percentiles linking to the conversations it counted; two prompt versions side by side.
- **Evals**: runs the suite, edits scenarios, rubrics and prompts through their loaders' checks, renders reports.

The app has no login: it listens on 127.0.0.1 and refuses any request, `/api/` included, under another host name or
posted from another site; its pages refuse framing.

## How state survives a two-day pause

Nothing a conversation needs lives in memory between turns. A turn claims its number in its own transaction, replays the
transcript from the message rows into a stateless model call (`store` false, no previous response id), and commits the
reply, the conversation, the turn row and its state diff together. Counters, the latest offer and the booking are rows,
and an offer gone stale is re-checked when the lead picks it. The only in-process state is the per-conversation lock,
held for one turn. `ConversationApiTest.Restart` boots the app twice on one database and finds the conversation and its
opt-out intact.

## Code a reviewer can own

Read in this order: `conversation/InboundTurns` (one SMS to one turn), `conversation/TurnPipeline` (the steps),
`guard/PreGuards`, `conversation/AgentTurn` and `agent/AgentService` (the model call and its tool loop),
`conversation/ReplyRepair` with `guard/ReplyGuards`, and `conversation/StateRules`. [`AGENTS.md`](AGENTS.md) sketches
how they connect and holds the code rules. `PreGuardsTest`, `ReplyGuardsTest`, `StateRulesTest` and `InboundTurnsTest`
pin the rules. The commit history tells the build in order, each body saying why.

## Built first, and why

Vertical slices, each usable on its own. The gates first, because nothing can be reviewed until format, lint and tests
exist. Persistence, the opener and STOP before the model, because the hardest requirement is a reply two days later,
STOP must work with the model down, and both are provable without a key. Then the model call, then the guards, so no
page ever showed an unguarded Anna. Then scheduling, then prompt versioning, so the editor is a view over tested
behaviour. Then the console in order of value: Conversations, the inspector, Configuration, Analytics. The evals came
late because they drive the finished pipeline, judges last.

## Left out, and why

A real SMS gateway, real Calendly and Lead API clients, authentication, more than one tenant or instance, operator
takeover, replay, judge calibration, telemetry export, nudges and a kill switch. None is needed to put a reviewer in
front of a working agent, and each would add code before the code that exists is judged. No Docker, CI or second Maven
module: one reviewer, one laptop, one `./mvnw`. Next, each with the class where it starts:

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

- A deterministic shell around one model call, over an agent framework: more code I own, and every rule that matters
  is code with a test rather than a sentence in a prompt.
- H2 in file mode over Postgres: zero setup and a database that survives a restart; a migration if a second instance
  is ever needed.
- Thymeleaf over a JavaScript front end: one language, one build, no bundler, for less interactivity.
- Fakes with no HTTP path over clients behind interfaces: the first real integration is new code, and nothing claims
  to be tested against a service it has never seen.
- Error Prone over Checkstyle or PMD, because Spotless owns style and Error Prone finds bugs; Maven, Enrola's stack;
  Spring Data JDBC over JPA, because rows written once per turn gain nothing from a persistence context.

## Measuring quality at scale, and which metric to distrust first

What gates stays deterministic: the eval suite's hard checks and core scenarios, and in production the Analytics
figures, guardrail violations against zero, booking rate of replied, opt-outs split STOP keyword versus model, handoffs
by reason and cost per booking, each a numerator over its base.

Distrust first the judge pass rate: the judges are uncalibrated, and a judge that reads "fine" on everything looks like
a healthy product. Second, any rate on a base under thirty: Analytics badges it low n but draws no interval, and only
eval runs report one, for their own trials. Third, anything measured on simulated leads, which are politer and more
consistent than people: a story's pass^k (the share of scenarios whose every trial passed) bounds breakage and estimates
no booking rate. The offline run's 100% proves the harness, not the prompt. Before a judge gates anything, label a
hundred real transcripts per rubric and measure its agreement.

A new prompt version proves itself twice. Evals run on the active version, so on a development database save it,
activate it, and run the core set and the stories with `--trials 3`; each run records its version, and Analytics shows
the latest keyed run per version. In production conversations pin their version, so after activation the two are clean
cohorts on the same figures; call it once both bases pass thirty. Judges comment; they do not decide.

## 750 leads a week

The openers are templates, so a lead costs nothing until it replies. At one in five (A6), about 150 conversations a week
reach the model. A booking took four model turns in a keyed story; at an assumed six an engaged conversation, that is
about 900 turns a week, about $8 at the measured median, plus a free moderation call per inbound. Even at the 15-reply
cap for every lead it is 2,250 turns, about $30. That is about 130 turns a day at five seconds each: one instance is
idle almost all the time, so compute is not the constraint. What gives first:

1. **The app spend cap.** A lifetime total (A11), spent in about nine days at this rate, after which every turn hands
   off to a person. Raise it in Configuration; the fix is a monthly window.
2. **The calendar.** The fake never fills, so two leads can book one slot. One advisor's hours hold 126 starts a week
   (22 a weekday, 16 on Saturday), and 150 engaged leads could want more. With Calendly the ceiling is the advisor
   seats behind the event type.
3. **Storage.** About 50 KB of request and response JSON per model turn: 2.3 GB a year in one H2 file, no retention.
4. **A second instance.** The H2 file and the per-conversation lock are single-process; nothing else holds state.

## A second customer

Everything customer-specific is data. A second customer is a prompt bundle under `src/main/resources/prompts/vN/`
(prompt, knowledge pack, schema, tools, templates), the persona, company, hours and zone in the runtime settings, the
state-to-zone table in `scheduling/LeadZones` if the leads are not Australian, and a scenario set. The deny-list and the
rubrics name Comparato's claims and are rewritten with the pack. The pipeline, guards, scheduling, persistence, API and
console stay as they are.

## Privacy

Lead messages and the lead record go to OpenAI, which processes them outside Australia; Comparato's privacy notice
should say so under APP 8 (Australian Privacy Principle 8, cross-border disclosure). Every model request sets `store`
false, and phone and email never reach the model. The prompt forbids asking about health; once the model flags a turn as
health information, the API returns later message bodies as null unless asked with `?includeHealth=true`, and the PII
mask hides them in the console (see Known issues for when it does not flag). Eval leads are synthetic. Logs carry ids
and outcomes, never bodies or phone numbers; keys travel only as a request header and show only as set or not set.
Nothing is deleted: messages and each turn's raw request and response stay in `data/` until a retention period is set.

## Known issues

- **A distressed lead's health information can go unflagged.** In one of two keyed trials of the vulnerable-lead story
  the model flagged the lead vulnerable but not as health information, so the bodies were not hidden: prompt v1's
  distress rule asks only for the first, while its rule 6 asks for the second. One line in a v2, which needs a green
  core run.
- **Anna offers a call time to a distressed lead.** The judge failed that story's own question on both keyed trials;
  v1's own distress example ends "Want me to find a time that suits?". Worth the same v2.
- **Anna can skip to the call ask** after "are you a bot" (one of four keyed trials). The scenario does not check the
  stage, so only a judge caught it.
- **Unproven on the real model:** a slot taken between offer and booking, and a booking the calendar refuses. Both have
  scenarios, never run with a key; no full keyed core run exists.
- **Self-harm after a hard end while moderation is down gets silence**, because the model is not asked there.

## Where AI was used

A coding agent (Claude Code; every commit carries its `Co-Authored-By` trailer) wrote the code and these docs, one
commit at a time, from per-commit guides I wrote outside the repo, and each commit was reviewed against its guide and
`AGENTS.md` before it was made; what the reviews changed is in the commit bodies. About $0.35 of OpenAI usage was
recorded across the build's validation runs (per-commit ledger; boot model checks are not recorded). Coding-agent usage
is billed separately and not recorded in the repo.

With more time I would delete the repair call in `conversation/ReplyRepair` if Analytics shows replies almost never fail
a check (the checks and the fallback stay); the keyword story stand-in once every developer has an eval key;
scenario-level judge questions once the rubrics cover them; and the scenario editor's form view if the designer edits
the YAML.

## Repo layout

```
src/main/java/com/enrola/anna/
  lead/           leads, phones, opt-out suppressions, the seeded three
  conversation/   the turn: InboundTurns, TurnPipeline, StateRules, the rows and trace events
  agent/          the model: decision vocabulary, the tool loop, the OpenAI client, prices
  guard/          pre-guards, moderation, reply checks, the deny-list, STOP keywords
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
