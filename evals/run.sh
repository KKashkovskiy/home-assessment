#!/usr/bin/env sh
# Runs the eval suite through JUnit on a fresh database under evals/runs/<id>/ and prints the report path.
set -eu

usage() {
  cat <<'EOF'
Usage: evals/run.sh [--scenario id,id] [--filter GLOB] [--tag TAG] [--kind turn|story] [--priority p0|p1]
                    [--offline] [--budget USD] [--dry-run]

  --scenario  comma-separated ids; wins over the other filters; an id with no file fails the run
  --filter    glob on the id, e.g. 'turn-stop-*'
  --tag       only scenarios carrying the tag (core, scheduling, abuse, ...)
  --kind      turn or story
  --priority  p0 or p1 (a tag)
  --offline   the stand-in model answers from each scenario's expect block: $0, proves the harness
  --budget    this run's cap in USD (default: the settings' eval cap); an estimate above it is refused
  --dry-run   print the selection and the estimate; no model call, no eval_run row

Keys: EVAL_OPENAI_API_KEY, or OPENAI_API_KEY when EVAL_ALLOW_SHARED_KEY=true. With neither and without
--offline every trial is SKIPPED_NO_KEY and the report is still written.
Exit codes: 0 every trial passed or was skipped; 1 a trial failed, was invalid or crashed, or the run was
refused; 64 a flag this script does not know or a flag without its value.
Each flag becomes one -Deval.* property; the Evals page builds the same properties, so this list is the contract.
EOF
}

# The -Deval.* property a valued flag sets.
property() {
  case "$1" in
    --scenario) echo eval.scenarios;;
    --filter)   echo eval.filter;;
    --tag)      echo eval.tag;;
    --kind)     echo eval.kind;;
    --priority) echo eval.priority;;
    --budget)   echo eval.budgetUsd;;
  esac
}

cd "$(dirname "$0")/.."
# The flags are read off the front of "$@" and their properties appended to its end, so each value stays one word.
COUNT=$#
while [ "$COUNT" -gt 0 ]; do
  FLAG=$1
  shift
  COUNT=$((COUNT - 1))
  case "$FLAG" in
    --scenario|--filter|--tag|--kind|--priority|--budget)
      if [ "$COUNT" -eq 0 ] || [ -z "$1" ]; then echo "$FLAG needs a value" >&2; exit 64; fi
      case "$1" in --*) echo "$FLAG needs a value" >&2; exit 64;; esac
      VALUE=$1
      shift
      COUNT=$((COUNT - 1))
      set -- "$@" "-D$(property "$FLAG")=$VALUE";;
    --offline) set -- "$@" -Deval.offline=true;;
    --dry-run) set -- "$@" -Deval.dryRun=true;;
    -h|--help) usage; exit 0;;
    *) echo "unknown flag $FLAG" >&2; usage >&2; exit 64;;
  esac
done
RUN_ID=$(date -u +%Y-%m-%dT%H-%M-%SZ)
N=1
while [ -d "evals/runs/$RUN_ID" ]; do N=$((N + 1)); RUN_ID="$(date -u +%Y-%m-%dT%H-%M-%SZ)-$N"; done
mkdir -p "evals/runs/$RUN_ID"
STATUS=0
./mvnw -q -Dtest=EvalSuiteTest -Dsurefire.failIfNoSpecifiedTests=false -Deval.runId="$RUN_ID" "$@" test \
  || STATUS=$?
echo "report: evals/runs/$RUN_ID/report.md"
exit $STATUS
