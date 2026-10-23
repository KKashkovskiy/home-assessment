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
own, and git ignores it.

The server has no pages or endpoints yet, so the root URL answers with a 404.

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
