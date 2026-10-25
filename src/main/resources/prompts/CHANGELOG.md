# Prompt changelog

Each prompt version is a directory here: `v1/`, then `vN/` for each one after. A directory holds the five artifacts that
travel together: `vN.md` (the system prompt), `knowledge.md` (the pack inlined at `{{knowledge}}`),
`agent-decision.schema.json`, `tools.json`, and `templates/NAME.txt`, one file per fixed template.

At boot every `vN/` without a row is seeded into the `prompt_version` table, byte for byte and inactive. When no
version is active, as on an empty table, the newest version the repo seeded is made active; a saved version never is.
The directory in the repo is the record of what shipped, and the table is what runs. Every conversation pins the
version it started on, so changing the active version never changes a conversation in flight.

## v1

The first version. It keeps the rules of the source project's system prompt, rewritten so a product designer can read
and edit it and so Anna sounds like the sample transcript.

What changed against the source project's `system.md`:

- Voice: short, plain, Australian, with contractions and no exclamation marks at all (the old prompt only banned
  "spam"). Every example reply is rewritten in that register. A "How you sound" section replaces the old "Tone and
  examples".
- Structure: sections are named for the moment they cover (Who you are, How you sound, Hard rules, The script,
  Off-script moments, Scheduling, After the conversation has ended, Knowledge pack, What you return). Each lead
  intent is described once, in Off-script moments, with its state changes beside it. The old prompt described intents
  twice (Principles and Output contract).
- Example replies for each scripted moment: preference branches for all three seeded leads, call ask, offering times,
  booked, objection retry, decline, are-you-a-bot, off-topic, injection, distress, health info volunteered,
  non-English, SLOT_TAKEN.
- One explicit line added: reply in English whatever language the lead writes in. The source project tested this
  (NONENGLISH scenario) but never stated it in the prompt.
- The hard rule on length, questions and formatting moved into "How you sound", because that is where a designer
  looks for it. The constraint is unchanged.
- The opener templates follow the brief's sample: greeting, who is texting, one provider-aware line, a question
  about the lead's situation, and STOP. The `{provider}` placeholder is new.
- Nothing of substance was dropped. The old rule 10 list of banned formats is kept, and the repeated explanations of
  opt-outs, retries and tool errors are cut to one statement each.

The placeholders are unchanged: `{{persona}}`, `{{target_chars}}`, `{{max_chars}}`, `{{canary}}`, `{{knowledge}}`.
Code still appends the per-turn context (today, tomorrow, counters, offered slots, booking, ended state, lead record),
and the prompt refers to it by its labels ("objections used: n of 1", "post-terminal replies", ENDED).

## v2

Fixes for what keyed eval runs found. Nothing else changed, and v1 stays as it shipped.

- Distress flags health information. v1's distress rule asked for VULNERABLE only, while rule 6 asks for HEALTH_INFO
  when a lead volunteers health information; in one of two keyed trials of `story-vulnerable` the model set only the
  first, so later bodies were not hidden. A diagnosis, condition or treatment mentioned, named or not, now gets both.
- Distress offers a person, not a time. v1's own distress example ended "Want me to find a time that suits?", and the
  story's judge failed both keyed trials for it. The reply is now under 140 characters: a few words of acknowledgement,
  then an offer of someone from the team whenever the lead is ready. That offer is the reply's one question (code wants
  exactly one in every open reply), and it is asked again instead of the stage question until the lead answers it. The
  answer, whether yes, thanks or not now, is PLEASANTRY, never OBJECTION or HANDOFF_REQUEST, so it gets no slot. Anna
  closes with no question, the conversation ends HANDOFF, and the handoff reason stays DISTRESS.
- A bot question keeps the stage. v1's example answered and jumped to the call ask ("Would tomorrow morning suit?"),
  as the model did in one of four keyed trials of `turn-are-you-a-bot`. Anna now answers and asks her last question
  again, and that scenario checks `stage: ENGAGE` and that no tool is called, so the core gate catches a skip.
- An off-topic decline is one fixed sentence. v1's example declined a joke with "I'll leave the jokes to the
  comedians", and on keyed runs the model declined a poem with "I'll leave poems to the poets" and "Poems aren't my
  thing", which `story-troll` forbids. Anna now says "That's outside what I can help with here.", names nothing they
  asked for, and asks the stage question.
- An empty calendar gets one more search and a plain answer. v1 said only "BEYOND_HORIZON or no slots: offer the
  earliest window that has slots". On keyed runs the model answered "Advisors are pretty booked tomorrow morning, but I
  can grab the next morning spot that opens up", and once searched until the tool-round cap. Now an empty query gets
  one more search across the whole horizon. If that is empty too, Anna stops calling tools, says no times are open,
  promises no spot or follow-up, and asks the lead to text again later.

Evidence on the text as committed, 24 October 2026, gpt-5.1 at low effort:

- The core set, one trial each: exit 0, verdict PASS, pass^k 100% (26 of 26), the hard, global and validity gates
  PASS; $0.27, judges $0.64.
- `turn-calendar-fully-booked` and `turn-booking-fails`, one trial each: both passed, and both judge questions.
- `story-vulnerable`, two trials: both passed with both flags and handoff reason DISTRESS. The story's judge question
  passed one; the other reply ran to 157 characters with two sentences of acknowledgement.
- `story-troll`, three trials: all passed; its judge question passed two of three.
- `turn-are-you-a-bot`, two trials: both passed.

Before this text, four keyed core runs of drafts scored 25 or 26 of 26 with the hard gate always passing; their failures
led to the off-topic decline and the empty-calendar rule above.

A fresh `data/` activates v2 at first boot. An existing `data/` seeds it inactive and keeps its active version, so
activate v2 in Configuration; conversations pinned to v1 keep v1. If a save in that `data/` already took the id v2, the
repo's v2 is skipped with a warning and never activated. In that case, save `prompts/v2`'s files as a new version in
Configuration and activate it, or start a fresh `data/`.

## Making the next version

Saving a version gives it the next id in the table, and nothing is overwritten. A save is refused when a turn on it
could not be answered:

- the prompt lacks one of the five placeholders above, or has one they do not include;
- the knowledge pack is empty;
- the decision schema or the tools are not valid JSON, the schema is not named `agent_decision`, not strict or has no
  `schema` object, or a tool is not strict or lacks a description or a parameters object;
- `get_availability` or `book_call` is missing;
- a template is missing, uses a placeholder other than `{name_part}`, `{persona}`, `{disclosure}` and `{provider}`, or
  goes over its cap (320 characters for the openers, 160 for the rest) for the longest name the lead table holds.

A template with characters outside GSM-7 is saved with a warning, because the handset still gets it, in more
segments. Activate a version for new conversations when it is ready. A version that does not load cannot be
activated.

To make a saved version part of the repo, create `prompts/vN/` with the same id and copy the five artifacts into it:
the prompt as `vN.md`, the pack as `knowledge.md`, the schema and tools JSON under their file names, and each template
as `templates/NAME.txt`. Add an entry here saying what changed and why. Do this before anyone adds a directory for an
id the table already has, because the seed skips that id and logs that the repo and the table disagree. A directory in
the repo must pass the save rules with no warning at all, or the app does not boot.
