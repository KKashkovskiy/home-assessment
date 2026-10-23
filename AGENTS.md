# Working in this repo

Every commit is one unit of value that passes the same gates, keeps the README current and never reorders its
headings.

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

Root `com.enrola.anna`. Feature packages arrive with their features and stay flat: `lead`, `conversation`, `agent`,
`guard`, `scheduling`, `prompt`, `config`, `web`. Tests mirror main; the eval harness goes in
`src/test/java/com/enrola/anna/eval`. No `common`, `util`, `core` or `shared` package, ever; a helper belongs to the
feature that first needed it.
Dependencies point one way: `conversation` to `agent` and `guard`, and `guard` to `agent` and `lead`; `agent` and
`guard` never import `conversation`, so a guard takes plain values, never the conversation entity.

## Running the gates

```
./mvnw spotless:apply && ./mvnw verify
```

Java 25 on the path. The build is red if Spotless, Error Prone or a test fails; no commit on red. Error Prone runs in
compile and every warning fails the build, so a suppression needs a reason. It reads javac internals, so
`.mvn/jvm.config` carries the module exports it needs; do not move them into the pom.

## Running the app

`./mvnw spring-boot:run`; see the README Quick start.

## Where things go

- Prompt bundles (prompt, knowledge pack, templates, schema, tools) go in `src/main/resources/prompts/vN/`.
- Eval scenarios, rubrics and the runner go in `evals/`; run output goes in `evals/runs/` (ignored).
- Runtime data and settings overrides go in `data/` (ignored).
- Secrets come from the environment only; `.env.example` names them with blank values, other `.env*` files are
  ignored.

## Tests

Spring tests run against a real H2 file in a temp directory, set through `@DynamicPropertySource` as in
`AnnaApplicationTest`. No test touches `./data`, and nothing mocks H2 or a repository. Spring tests set
`OPENAI_API_KEY=` blank, so no test calls OpenAI or costs money; a test that needs the model scripts it with
`ScriptedModelClient`.

## What not to do

- No `catch (Exception e)`, no empty catch block.
- No Lombok, Spring AI, MapStruct, Guava or Commons Lang. No dependency before the commit that uses it.
- No real key anywhere: repo, test or log line.
