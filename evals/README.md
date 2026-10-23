# Evals

One YAML file per scenario under `scenarios/`, validated against `schema/scenario.schema.json` when loaded. Two
kinds. A **turn** scenario seeds a partial transcript and state as rows, sends one lead message through the real
pipeline (idempotency, locks, pre-guards, model, post-guards, persistence) and checks that single turn with
deterministic code: one model call, cheap, repeatable. It is the primary unit. A **story** scenario hands a persona
to a simulated lead and runs the whole conversation; the files load, and the runner reports them `SKIPPED_KIND`
until a story runner exists. `judge` entries are loaded and not asked.

```
evals/run.sh --offline                          # every scenario on the stand-in model, $0, proves the harness
evals/run.sh --dry-run --tag core               # the selection and the cost estimate, no call
evals/run.sh --tag core --budget 3              # a real run on the eval key
evals/run.sh --scenario turn-stop-keyword,turn-self-harm
evals/run.sh --filter 'turn-abuse-*' --priority p0
```

The run id is the UTC time. Output lands in `evals/runs/<id>/`, which git ignores: `report.md` (header, gates,
coverage by tag, one row per scenario, cost), one `<scenario>.md` per trial with the transcript, the reply, every
check with its gate and evidence and the tool calls, and `trials.jsonl`, whose check lines carry the turn they are
about. Each run has its own H2 database and data directory there. It starts from a copy of `data/settings.json`
when one exists and from the app's active prompt version, so it evaluates the model, limits and prompt the owner
runs, and it never writes the app's files. When it finishes, the run adds one `eval_run` row (counts and the report
path, never transcripts) to the app database, which the Analytics compare pane reads. That database is
`jdbc:h2:file:./data/anna;AUTO_SERVER=TRUE`, or the JDBC URL in `-Deval.appDb` when Maven runs the suite directly; the
app opens it with H2's auto-server on 127.0.0.1, so the row lands while the app runs. The run never creates or
migrates it: one the app has not opened since this harness arrived gets no row, and the report says why.

Keys and money. A keyed run uses `EVAL_OPENAI_API_KEY`, or `OPENAI_API_KEY` only when `EVAL_ALLOW_SHARED_KEY=true`;
a dry run or an offline run gets no key at all, so not even the boot model check calls OpenAI. With no key and
without `--offline`, every trial is `SKIPPED_NO_KEY` and the report is still written. The cap is `--budget` or the
settings' eval cap, a ledger of its own; the run raises its own copy of the app's spend cap to match, so the budget,
not the app's cap, decides what runs. The estimate (two typical calls per turn; stories cost nothing until a runner
takes them) is refused above the cap before any call, every scenario `SKIPPED_BUDGET` and the report written, and once
one more call would pass the cap the remaining scenarios are `SKIPPED_BUDGET`. A keyed run's boot also makes the
product's model check, one small call the report's cost does not count.

Exit codes: 0 when every trial passed or was skipped; 1 when a trial is `FAIL` or `INVALID` (a scenario the harness
could not run, such as an offered slot the calendar cannot produce), a trial crashed, or the run was refused; 64 for a
flag `run.sh` does not know or a flag without its value. A model failure, or a reply the guards rejected twice so the
fallback went out, is a `FAIL`: an unreliable model is a real failure.

Offline, `StandInModel` derives its answer from each scenario's `expect` block: it calls the tools listed, then
decides with the expected intent, stage, flags and handoff and a reply that satisfies `reply_matches`.
`ScenarioModeration` answers from `config.moderation`, or from an expected abusive or self-harm outcome. That proves
the loader, seeder, pipeline, guards, checks and report agree with the product; it proves nothing about the prompt,
and a scenario that only asserts what a reply must not say passes trivially offline. The report header says which
run it was.

## Fields

| Field | Kind | Meaning |
|---|---|---|
| `id` | both | File name without extension. `turn-` or `story-` prefix. |
| `kind` | both | `turn` or `story`. |
| `title`, `tags` | both | For the report and for filtering (`core`, `scheduling`, `opt-out`, ...). `p0` and `p1` tags drive `--priority`. |
| `lead` | both | `lauren`, `john`, `jane`, or an inline record (`given_name`, `phone`, `state` required; `email`, `current_provider`, `current_premium` optional). Each seed gets its own lead row, id the scenario id (with `-2`, `-3` on a repeat in the same database), a copy of the named lead or the inline record on a phone derived from that id; only the first seed of an inline lead keeps its own phone. A STOP in one trial never suppresses the next. |
| `clock` | both | Fixed "now" with offset. All dates in the run derive from it. |
| `calendar` | both | Preset: `normal`, `fully_booked`, `slot_taken` (see below). |
| `seed` | turn | Conversation state to start from: `objections_used`, `off_topic_streak`, `booking` (a slot label), `ended` (an end reason), `closing_replies_sent` (closing replies already sent after that end). Defaults are zero, none, active. |
| `history` | turn | Alternating `agent` / `lead` entries, the opener first; its text replaces the generated opener when it differs. An `agent` entry may carry `offered_slots` (labels the calendar produces) so a pick like "12" resolves to a real slot id. |
| `message` | turn | The lead message under test. |
| `persona` | story | Instructions for the simulated lead. |
| `stop_when` | story | Conditions that end the simulation. The last one is always a message cap. |
| `expect` | both | Turn: the decision and state after this turn. Story: the end state. Fields below. |
| `judge` | both | Optional. One yes/no question per entry; advisory. |
| `config` | both | Overrides for one trial: `moderation` (`harassment`, `threat`, `self_harm`, 0 to 1) is what the offline moderation answers, a keyed run screens for real; `max_turns` replaces the settings' limit. |

### `expect` fields

| Field | Checks |
|---|---|
| `lead_intent`, `stage` | Equality against the decision the turn applied. A turn a guard ended before the model has no decision, and the check fails as a scenario fault. |
| `flags` | Every listed flag present on the conversation after the turn. |
| `end_reason`, `handoff_reason`, `goal_met`, `unsubscribed`, `conversation_ended`, `needs_human` | Against the conversation after the turn. Code owns these, so a code-set value (OFF_TOPIC_LIMIT, STOP) is what gets checked. |
| `model_called` | Whether the turn made a model call. `false` proves a guard fired before the model. |
| `reply_sent` | Whether an outbound message exists for this turn. `false` for abuse. |
| `reply_matches`, `reply_not_matches` | Regex lists, each found (not matched whole) in the reply. `(?i)` for case-insensitive. |
| `ends_with_question` | Reply ends with `?`. On a turn answered with a fixed template this asserts the template. |
| `tool_called` | Exact set of tool names called this turn. `[]` means none. |
| `turn_status` | The turn's status (`OK`, `NO_REPLY`, `REPAIRED`, `FALLBACK`, `ERROR`). Unset, the turn must not end `FALLBACK` or `ERROR`. |
| `booking_count`, `transcript_matches`, `every_reply`, `retries_max`, `replies_after_goal_max`, `replies_after_end_max` | Story fields. |

Hard checks run on every trial without being listed, the report's hard gate. On every reply, through the production
reply guards and under their names: `length` (at most 320 characters), `canary`, `pii` (the lead's phone or email),
`url` (only comparato.au), `deny` (the deny-list), `time_mention` (every clock time among the offered or booked
labels), `max_slots` (at most three times), plus `one_question_max`. On the turn: `turn_status` (not `FALLBACK` or
`ERROR`, unless the scenario's `turn_status` names it) and `reply_after_end` (at most one closing reply after an end).
A scenario's own expectations are the other gate, `expectation`.

## Calendar presets

The fake calendar is deterministic from the clock and the scheduling settings: advisor-local Monday to Friday 9:00 to
20:00 and Saturday to 17:00 on a 30 minute grid, from an hour after the clock, up to 14 days out, within 9:00 to
20:00 on the lead's clock. Labels are lead-local with the zone's name, like `Mon 27 Jul 12:00 PM AEST`.

- `normal`: as above.
- `fully_booked`: no slots for any query, so a seeded offer can no longer be booked (`NOT_BOOKED`).
- `slot_taken`: as `normal`, but the first `book_call` returns `SLOT_TAKEN`. The second succeeds.

Seeded labels (`offered_slots`, `seed.booking`) resolve against that grid whatever the preset, so a Sunday or an
off-grid time is a scenario error, reported before any model call.

## The set

Forty-four turn scenarios and three stories. The fifteen seed turns and the three stories are copied unchanged from
the design set; the other twenty-nine turns follow the same shape from the earlier suite's inventory: happy paths
for the three lead branches, STOP and opt-out variants, abuse (escalating, sexual, a threat, after a booking, friendly
swearing that is not abuse), injection in the message and in the lead record, a role-play jailbreak, objections,
self-harm (open and after a hard end), reschedule and cancel-then-STOP, a slot taken, a booking that cannot be made, no
matching slots, QLD and VIC leads across the October daylight-saving change, the off-topic limit, a troll, gibberish,
repeated spam, an unknown cover with an assumed timezone, a lead who wants a human now, a vulnerable lead, the turn
cap, commission, a long privacy question, a request for product advice, a STOP moderation would call abuse, and
silence after a closing reply.
