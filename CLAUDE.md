# CLAUDE.md — SKYFIX

Project instructions for any agent working in this repository. Read `docs/BLUEPRINT.md`
for the full specification (all FR/NFR/ADR/T- IDs) and `HANDOFF.md` for current state.

## What this is

SKYFIX predicts where a high-altitude balloon payload will land, as a confidence ellipse
rather than a point, and re-estimates the flight's real parameters from replayed telemetry
using a particle filter. It is the submission for **CSE2006 Programming in Java**,
VIT Bhopal University — Kumar Karan Bohidar, Reg. No. 25BAS10049.

It serves two real missions: HabSat (~30 km) and a 45 km ascent with ~400 km drift.

## Hard rules — never violate without asking

1. **Own the computational core.** Every aerospace algorithm is hand-written Java in this
   repo: atmosphere model, integrators, flight dynamics, interpolation, Latin-hypercube
   sampling, covariance eigen-decomposition, particle filter, resampler. No Orekit, no
   Apache Commons Math, no scientific library in `src/main`. Libraries are allowed only for
   plumbing: `sqlite-jdbc`, Jackson (JSON), XChart (PNG export), JUnit 5, AssertJ, JaCoCo.
   If a task seems to need a maths library, write the maths instead and add a unit test.
2. **Offline always.** A fresh clone plus a JDK must run every command and every test with
   no network. All data ships in `data/`. A test that needs the network is a bug.
3. **SI internally.** Metres, kilograms, seconds, kelvin, pascals. Convert only at the
   edges, in `domain/Units.java`. Altitudes are geometric above MSL; times are UTC;
   winds are local east-north-up. State every integrator step size and tolerance.
4. **Real persistence.** Embedded SQLite through plain JDBC and the DAO pattern.
   `PreparedStatement` everywhere — never build SQL by string concatenation. Test `T-S1`
   scans `src/main` for violations and must stay green.
5. **No invented facts.** Never write a dataset, paper, DOI or URL that has not been
   verified. Unverified claims carry a literal `verify:` marker in the text. Anything that
   only exists after the code runs (results, timings, challenges) stays as an explicit
   `[PLACEHOLDER — ...]` until it is measured.
6. **Documentation ships with the code.** The ADR for a decision lands in the same commit
   as the code implementing it. Diagrams in `docs/diagrams/` are regenerated whenever the
   design they describe changes — a stale diagram is worse than no diagram.

## Stack

JDK 21 LTS · Maven with the wrapper (`mvnw`, `mvnw.cmd`) · SQLite via `sqlite-jdbc` ·
JUnit 5 + AssertJ + JaCoCo · XChart (PNG only) · Jackson (config JSON only) ·
`java.util.logging` · GitHub Actions.

No Spring, no Hibernate, no JPA. The grader must see my own OOP, JDBC and concurrency code.

## Commands

```bash
./mvnw -B package            # build
./mvnw test                  # unit + model validation + DB integration (~2 min target)
./mvnw verify -Pperf         # adds timing (T-P*) and reproducibility (T-R1) tests
./mvnw jacoco:report         # coverage -> target/site/jacoco/index.html
./scripts/run.sh validate    # reference-case table; exits non-zero on any breach
```

## Definition of done for any task

- Code compiles and `./mvnw test` is green.
- The requirement ID it implements is named in the commit body
  (`Implements FR-2.1, verified by T-V1`).
- Every new public type has Javadoc saying what it does and in which units.
- Any physical model added has a validation test against a published reference value with
  a stated numeric tolerance — not a self-consistency check.
- User-facing errors name the offending field or `file:line`. No raw stack traces.
- If the change alters a design decision, the matching `docs/adr/ADR-*.md` is updated in
  the same commit.

## Coding conventions

- Package root `com.skyfix`. Dependency direction is strictly
  `cli -> app -> {core.*, estimation, persistence, io} -> domain`, and `domain` depends on
  nothing. Do not introduce a cycle.
- Domain value types are immutable (records or final classes with builders). Configuration
  objects validate in `build()` and throw `ValidationException` naming the failing field.
- Exceptions extend `SkyfixException`. Use `IllegalArgumentException` only for programmer
  errors in private methods, never for user input.
- Concurrency: explicit `ExecutorService` with a fixed pool, `Future`/`CompletionService`,
  explicit `shutdown()` and `awaitTermination()`. Never a shared mutable `Random` — derive
  one seed per ensemble member from the run seed via `SplittableRandom.split()` so member
  *k* is reproducible in isolation.
- Every run persists seed, git SHA, config hash, integrator, step, member count, host cores
  and wall-clock. Same seed must reproduce identical ellipse parameters to 1e-9.

## Build order

Follow the tiers in `docs/BLUEPRINT.md` §12. Do not start the particle filter before the
ensemble runner is green, and do not start plotting before the database layer exists.
The MVP cut-line (ingest → atmosphere → single flight → persistence → CSV export) must be
a complete, submittable project on its own.

## Commit convention

Conventional Commits, requirement ID in the body:

```
feat(atmos): USSA-1976 layer model with table validation

Implements FR-2.1. Verified by T-V1 (0.1% relative at 25 altitudes).
```

Milestone tags: `v0.1-mvp`, `v0.2-ensemble`, `v0.3-estimator`, `v0.4-validated`,
`v1.0-submission`. Commit something green every week — a single bulk upload at the end
forfeits 10% of the course grade outright.
