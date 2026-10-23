# Working in this repo

Every commit is one unit of value that passes the same gates.

## Code rules

- SOLID is mandatory.
- Javadocs adequate and useful: not verbose, not terse.
- Commits are atomic units of work.
- Linted and formatted: Spotless (google-java-format) and Error Prone pass.
- Latest LTS tooling; modern Java 25 features where they make sense: records, sealed interfaces, pattern matching,
  text blocks.
- Code review before every commit until polished: SOLID, clear patterns, proper decomposition, no boilerplate, no
  typical coding-agent mistakes (dead abstractions, duplicated helpers, swallowed exceptions, tests that assert
  nothing).
- TDD per commit, but assess tests critically. No useless tests.

## Per-commit ritual

1. Read the commit's scope and do not widen it. A gap left by an earlier commit gets the smallest fix, named in
   the commit body.
2. Write failing tests first where they earn their place: pure rules and the API contract.
3. Implement in flat feature packages. Interface only where a second implementation exists or a test needs a fake.
4. Run the gates. Review for SOLID and the coding-agent mistakes above, fix, run the gates again.
5. Commit with a subject that names the value and a body that says why. Never amend, reset hard or force anything;
   everything is on `main`.

## Package layout

Root `com.enrola.anna`. Feature packages are flat: `lead`, `conversation`, `agent`, `guard`, `scheduling`, `prompt`,
`config`, `eval`, `web`; a new feature gets its own. Tests mirror main; the eval harness goes in
`src/test/java/com/enrola/anna/eval`, and only what the Evals page validates with lives in main's `eval`: the scenario
model, its loader and stop grammar, and the rubric loader. No `common`, `util`, `core` or `shared` package, ever; a
helper belongs to the feature that first needed it. Dependencies point one way: `conversation` to `agent`, `guard` and
`scheduling`; `guard` to `agent`; `agent` to `scheduling`; `eval` to `agent` and `conversation`; plus `prompt`, `config`
and `lead` from any package above them, `prompt` itself to `config` and `lead`, `scheduling` to `config` and `lead`.
`lead` and `config` import no feature package, and nothing imports `web`. `agent`, `guard` and `scheduling` never import
`conversation`, so a guard or a tool takes plain values, never the conversation entity. The time comes from `AppClock`
in the root package, which any package may use; nothing else reads the system clock, but for the eval launcher's run
ids, which name a launch in wall time as `run.sh`'s `date -u` does.

## The turn pipeline

`InboundTurns` claims the turn number, then `TurnPipeline` runs: moderation and `PreGuards` pick one outcome; the model
call with its tool loop (`AgentTurn`, `AgentCall`, `AgentService`); the decision applied (`DecisionApplier`); the reply
checks with one repair (`ReplyRepair`, `ReplyGuards`); and the finish writes reply, conversation, turn row and state
diff in one transaction. Only `StateRules` writes terminal state; only `StateRules.booked` writes `goal_met`. A new rule
goes where its kind already lives, never into the prompt alone.

## Console pages

- Templates live in `src/main/resources/templates/`, one file per page and shared pieces in `fragments/`;
  `app.css` and `app.js` in `static/`. `web` is a leaf: nothing imports it.
- A page controller carries `@Console`, which gives it the status strip and the console's error page; it binds,
  calls a service, and puts one view record on the model.
- Templates hold no rules: words, icons, CSS classes and option lists come from `@fmt` (`ViewFormats`), decisions
  from methods on the view records. No `T(...)`, no `name()` comparisons.
- `app.js` never renders domain HTML: the server renders every bubble and fragment and the script swaps them in.
  Every action also works as a plain form post.
- With the PII mask on, view records hold masked text only; masking happens where the records are built.

## Running the gates

```
./mvnw spotless:apply && ./mvnw verify   # format, Error Prone, tests
evals/run.sh --offline                   # the eval suite on the stand-ins, $0
evals/run.sh --tag core                  # needs EVAL_OPENAI_API_KEY; green before a prompt or model change
```

Green means exit code 0 and `| Verdict | PASS |` in the report header: with no eval key the run exits 0 as SKIPPED.

Java 25 on the path. The build is red if Spotless, Error Prone or a test fails; no commit on red. Error Prone runs in
compile and every warning fails the build, so a suppression needs a reason. It reads javac internals, so
`.mvn/jvm.config` carries the module exports it needs; do not move them into the pom.

## Where things go

- Prompt bundles (prompt, knowledge pack, templates, schema, tools) go in `src/main/resources/prompts/vN/`, seeded
  into the `prompt_version` table at boot; `prompts/CHANGELOG.md` says what changed in each and how a saved version
  gets back into the repo.
- Schema changes go in a new Flyway migration under `src/main/resources/db/migration/`; an applied migration is never
  edited.
- Eval scenarios, rubrics and the runner go in `evals/`; run output goes in `evals/runs/` (ignored).
- Runtime data and settings overrides go in `data/` (ignored).
- Secrets come from the environment only; `.env.example` names them with blank values, other `.env*` files are
  ignored.

Common extensions, and the file each starts in:

- A pre-guard: a case in `guard/PreGuards.Outcome` and a branch in its order; a row in `PreGuardsTest`.
- A reply check: `guard/ReplyGuards.validate` and a `guard/ReplyCheck` constant; a row in `ReplyGuardsTest`.
- An Analytics figure: one more `Kpi` in `web/AnalyticsKpis` and its tile in `templates/fragments/kpis.html`.
- A scenario: the Evals page, or `evals/scenarios/<id>.yaml` by hand; `ScenarioLoaderTest` loads every file.
- A judge question: `evals/rubrics/J-<NAME>.yaml` with `applies_to` and `examples`.
- A fixed template: a `prompt/Template` constant and `templates/<NAME>.txt` in every `prompts/vN/`, because a version
  loads only with every template and a fresh `data/` seeds them all. An existing `data/` never sees repo edits to a
  version it holds: before the constant ships, save the next version in Configuration with the template added,
  activate it and copy it into the repo as `prompts/CHANGELOG.md` says; conversations pinned to an older version then
  hand off.

## Tests

Spring tests run against a real H2 file in a temp directory, set through `@DynamicPropertySource` as in
`AnnaApplicationTest`. No test touches `./data`, and nothing mocks H2 or a repository. Spring tests set
`OPENAI_API_KEY=` blank, so no test calls OpenAI or costs money; a test that needs the model scripts it with
`ScriptedModelClient`.

## What not to do

- No `catch (Exception e)`, no empty catch block.
- No Lombok, Spring AI, MapStruct, Guava or Commons Lang: records and the standard library cover the project, and
  Lombok would hide fields from Error Prone and the reader. No dependency before the commit that uses it.
- No real key anywhere: repo, test or log line.
