# Prompt changelog

Each prompt version is a directory here: `v1/`, then `vN/` for each one after. A directory holds the five artifacts
that travel together: `vN.md` (the system prompt), `knowledge.md` (the pack inlined at `{{knowledge}}`),
`agent-decision.schema.json`, `tools.json`, and `templates/NAME.txt`, one file per fixed template. At boot every
`vN/` without a row is seeded into the `prompt_version` table, byte for byte and inactive; the first one seeded into an
empty table is made active. The directory in the repo is the record of what shipped, the table is what runs. Every
conversation pins the version it started on, so changing the active version never changes a conversation in flight.

## v1

The first version. Same rules as the system prompt in the source project, rewritten so a product designer can read
and edit it, and so Anna sounds like the sample transcript.

What changed against the source project's `system.md`:

- Voice. Short, plain, Australian, contractions, no exclamation marks at all (the old prompt only banned "spam").
  Every example reply rewritten in that register. A "How you sound" section replaces the old "Tone and examples".
- Structure. Sections named for the moment they cover (Who you are, How you sound, Hard rules, The script,
  Off-script moments, Scheduling, After the conversation has ended, Knowledge pack, What you return). Each lead
  intent lives once, in Off-script moments, with its state changes beside it. The old prompt described intents
  twice (Principles and Output contract).
- Example replies for every key moment: preference branches for all three seeded leads, call ask, offering times,
  booked, objection retry, decline, are-you-a-bot, off-topic, injection, distress, health info volunteered,
  non-English, SLOT_TAKEN.
- One explicit line added: reply in English whatever language the lead writes in. The source project tested this
  (NONENGLISH scenario) but never stated it in the prompt.
- Hard rule on length, questions and formatting moved into "How you sound" because that is where a designer looks
  for it. The constraint is unchanged.
- Opener templates reshaped to the brief's sample (greeting, who, one provider-aware line, situation question,
  STOP). A `{provider}` placeholder is new.
- Dropped: nothing in substance. The old rule 10 list of banned formats is kept; the explanatory repetition around
  opt-outs, retries and tool errors is cut to one statement each.

Placeholders are unchanged: `{{persona}}`, `{{target_chars}}`, `{{max_chars}}`, `{{canary}}`, `{{knowledge}}`.
The per-turn context (today, tomorrow, counters, offered slots, booking, ended state, lead record) is appended by
code as before and is referred to by its labels ("objections used: n of 1", "post-terminal replies", ENDED).

## Making the next version

Saving a version gives it the next id in the table; nothing is overwritten. A save is refused when a turn on it could
not be answered:

- the prompt lacks one of the five placeholders above, or has one they do not include;
- the knowledge pack is empty;
- the decision schema or the tools are not valid JSON, the schema is not named `agent_decision`, not strict or has no
  `schema` object, or a tool is not strict or lacks a description or a parameters object;
- `get_availability` or `book_call` is missing;
- a template is missing, uses a placeholder other than `{name_part}`, `{persona}`, `{disclosure}` and `{provider}`, or
  goes over its cap (320 characters for the openers, 160 for the rest) for the longest name the lead table holds.

A template with characters outside GSM-7 is saved with a warning: the handset still gets it, in more segments.
Activate a version for new conversations when it is ready; one that does not load cannot be activated.

To make a saved version part of the repo, create `prompts/vN/` with the same id and copy the five artifacts into it:
the prompt as `vN.md`, the pack as `knowledge.md`, the schema and tools JSON under their file names, and each template
as `templates/NAME.txt`. Add an entry here saying what changed and why. Do this before adding a directory for an id
the table already has: the seed skips that id and logs that the repo and the table disagree. A directory in the repo
must pass the save rules with no warning at all, or the app does not boot.
