# Eval run 2026-10-22T10-41-03Z

| | |
|---|---|
| Prompt version | v1 |
| Model | gpt-5.1 (effort medium) |
| Key | eval (EVAL_OPENAI_API_KEY) |
| Offline stand-in | no |
| Clock | per scenario |
| Scenarios | 2 selected (tag core), 2 run, 0 skipped, 0 crashed |
| Cost | $0.0770 of $10.00 cap |
| Verdict | FAIL |

## Gates
hard: FAIL   (failed: 1)
- turn-non-english: url, only comparato.au links are allowed: "Hola Lauren"

## Coverage by tag
| Tag | Scenarios | Pass |
|---|---|---|
| core | 2 | 1 |
| language | 1 | 0 |
| scheduling | 1 | 1 |

## Scenarios
| Scenario | Result | Failed checks | Cost | Model calls |
|---|---|---|---|---|
| [turn-happy-pick-a-time](turn-happy-pick-a-time.md) | PASS |  | $0.0410 | 2 |
| [turn-non-english](turn-non-english.md) | FAIL | reply_matches (?i)english, url | $0.0360 | 1 |

## Cost
Total $0.0770. Per scenario: see table. Judges: none. The model check at boot, one small call, is not counted.
