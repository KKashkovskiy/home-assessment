# Eval run 2026-10-22T10-41-03Z

| | |
|---|---|
| Prompt version | v1 |
| Model | gpt-5.1 (effort medium) |
| Key | eval (EVAL_OPENAI_API_KEY) |
| Offline stand-in | no |
| Clock | per scenario |
| Scenarios | 3 selected (tag core) |
| Trials per scenario | 2 |
| Judges | none (--judges none) |
| Trials | 6 run, 0 skipped, 0 crashed |
| Pass^k | 67% (2 of 3 scenarios clean) |
| Per-trial pass rate | 80% (38% to 96%, n=5) |
| Cost | $0.2310 of $10.00 cap |
| Verdict | FAIL |

## Gates
hard: FAIL   (failed: 1)
- turn-non-english#1: url, only comparato.au links are allowed: "Hola Lauren"
global: FAIL   (failed: 1)
- turn-non-english#1: reply_matches (?i)english, url
validity: PASS   (1 of 6 trials INVALID; the gate allows 20%)
exit code: 3

## Coverage by tag
| Tag | Scenarios | Clean |
|---|---|---|
| core | 3 | 2 |
| language | 1 | 0 |
| objection | 1 | 1 |
| scheduling | 1 | 1 |

## Scenarios
| Scenario | Trials | Result | Failed checks | Cost | Model calls |
|---|---|---|---|---|---|
| [turn-happy-pick-a-time](turn-happy-pick-a-time.md) [#2](turn-happy-pick-a-time.2.md) | 2/2 | PASS |  | $0.0800 | 2 |
| [turn-non-english](turn-non-english.md) [#2](turn-non-english.2.md) | 1/2 | FAIL | #1: reply_matches (?i)english, url | $0.0800 | 2 |
| [story-john-objects-twice](story-john-objects-twice.md) [#2](story-john-objects-twice.2.md) | 1/1 INVALID 1 | PASS | #2: persona | $0.0710 | 4 |

## Cost
Total $0.2310. Per scenario: see table; a story's includes its simulated lead, never the judges. The model check at boot, one small call, is not counted.
