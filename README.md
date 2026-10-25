# Anna

An SMS appointment-setting agent with a web console. Java 25, Spring Boot 4.1.2, H2 on disk and server-rendered
Thymeleaf. The only external service is OpenAI, and the app runs without it.

Design notes, assumptions and trade-offs are in [`docs/design.md`](docs/design.md). Code rules are in
[`AGENTS.md`](AGENTS.md), and the eval suite is described in [`evals/README.md`](evals/README.md).

## Requirements

Java 25 on the path. Any Temurin 25 build works, for example one from SDKMAN (`sdk list java`). Maven comes with the
repo as `./mvnw`.

An OpenAI key is optional. Without one the build, the tests, the offline evals and the console all work.

## Build and run

```
git clone <repo-url> anna && cd anna
./mvnw verify            # format check, Error Prone, tests; no key needed
./mvnw spring-boot:run   # the console on http://localhost:8080
```

To use another port, run `./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.port=18080`.

On Windows use `mvnw.cmd`. `evals/run.sh` and the Evals page call the POSIX `mvnw`, so run those under WSL.

The app listens on 127.0.0.1 only and has no login. It refuses requests that arrive under another host name or are
posted from another site, `/api/` included.

## Add an OpenAI key

Without a key the console shows "Agent unavailable". STOP and the fixed templates still work; every other lead message
is stored and handed to a person.

To talk to the model, export `OPENAI_API_KEY` before starting the app. You can also copy `.env.example` to `.env`, fill
it in and load it with `set -a; . ./.env; set +a`. Spring does not read `.env` by itself.

| Variable | Used for |
|---|---|
| `OPENAI_API_KEY` | the app's model calls |
| `EVAL_OPENAI_API_KEY` | keyed eval runs: the simulated lead and the judges |
| `EVAL_ALLOW_SHARED_KEY` | `true` lets evals spend `OPENAI_API_KEY` when the eval key is blank |

With a key set, the boot log shows `model check ok: gpt-5.1`.

## Try it

In the console, press Start a conversation, pick Lauren and type as the lead. The seeded leads are `lauren`, `john` and
`jane`.

The SMS platform uses the API instead:

```
curl -s -X POST localhost:8080/api/conversations -H 'content-type: application/json' -d '{"lead_id":"lauren"}'
curl -s -X POST localhost:8080/api/conversations/<conversation_id>/messages -H 'content-type: application/json' \
  -d '{"text":"yeah, save money","provider_message_id":"a1"}'
```

Each response has the reply to send (or `no_reply` with the reason), `goal_met`, `unsubscribed`, the booking and the
transcript.

## Data

The database is an H2 file under `data/`, next to the settings the console saves. Delete `data/` to start over.

A fresh `data/` runs the newest prompt version, v2. An existing one keeps its active version until you activate v2 in
Configuration. If a save in that database already took the id v2, see
[`prompts/CHANGELOG.md`](src/main/resources/prompts/CHANGELOG.md).

## Tests and evals

| Command | Needs | Cost |
|---|---|---|
| `./mvnw verify` | nothing | $0 |
| `evals/run.sh --offline` | nothing; code plays the model and the lead | $0 |
| `evals/run.sh --scenario turn-are-you-a-bot` | `EVAL_OPENAI_API_KEY` | about $0.02 |
| `evals/run.sh --tag core` | `EVAL_OPENAI_API_KEY` | about $1 |
| `evals/run.sh --kind story` | `EVAL_OPENAI_API_KEY` | about $0.45 |

A run is green when it exits 0 and its report header says `| Verdict | PASS |`. A run that judged nothing, as with no
eval key, exits 8 as SKIPPED. `evals/run.sh --dry-run --tag core` prints the selection and the cost ceiling without
calling the model. Reports land in `evals/runs/`.
