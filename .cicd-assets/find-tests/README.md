# find-tests

Determines which Maven modules and JUnit / Failsafe tests are affected by a diff, by walking the reverse-dependency graph emitted by `structure-maven-plugin`.

Used by CI (`.github/workflows/main.yml` → `decide-scope` → `make unit-tests` / `make integration-tests`) to scope shards to changed modules. Also runnable locally.

## Local usage

Generate the Maven project structure graph (this is the slow ~2-minute step):

```
./mvnw org.opennms.maven.plugins:structure-maven-plugin:1.0:structure
```

Then run the tool against the repo root:

```
python3 .cicd-assets/find-tests/find-tests.py generate-test-lists .
```

With no flags, the tool reads `parent_branch:` from `.nightly` and diffs `origin/<parent_branch>...HEAD` to determine the scope.

## CLI flags

- `--changes-only=true|false` — when `false`, consider every module regardless of diff. Default `true`.
- `--baseline-ref=<ref>` — explicit baseline ref; skips the `.nightly` lookup entirely. Falls back to the `BASELINE_REF` environment variable if the flag is unset. Use this in CI.
- `--output-unit-test-classes=<path>` — write unit test class names.
- `--output-integration-test-classes=<path>` — write integration test class names.

## CI usage

The workflow's `decide-scope` job computes a baseline ref per event (PR vs target branch; push vs `${before}` SHA) and exports it as `BASELINE_REF`. `make unit-tests` / `make integration-tests` invoke `find-tests.py` with `--changes-only=true` and rely on the env var for the baseline.

Tag pushes (`v*`) skip the structure-graph step entirely via `FULL_BUILD=true`, which routes `make test-lists` through a `find`-based fallback in the Makefile.

## Trip-wires

Trip-wires (cross-cutting paths that force a full build) live in the workflow, not in `find-tests.py` — see design D3 in `openspec/changes/scope-tests-by-changed-modules/design.md`.

## Development

Run the test suite:

```
cd .cicd-assets/find-tests
python3 -m unittest
```
