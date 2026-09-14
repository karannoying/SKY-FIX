# Handoff: SKYFIX — balloon landing-footprint prediction with in-flight estimation

_Last updated: 2026-09-14_

## Goal

Ship SKYFIX: a Java 21 command-line workbench that predicts where a high-altitude balloon
payload will land as a **confidence ellipse**, and re-estimates the flight's real parameters
(free lift, ascent drag, burst scale, parachute drag) from replayed telemetry using a particle
filter, re-predicting the footprint after every update.

This is the graded submission for **CSE2006 Programming in Java**, VIT Bhopal University —
Kumar Karan Bohidar, Reg. No. 25BAS10049. It also serves two real missions: HabSat (~30 km)
and a 45 km ascent with ~400 km of drift.

**Done** means all of:

- `./mvnw test` green offline from a fresh clone, ≥80% JaCoCo line coverage on `core.*` and `estimation`
- `./scripts/run.sh validate` prints the model-vs-reference table and exits 0 (T-V1…T-V7)
- Parameter recovery within 5% (drag) and 500 m (burst altitude) on ≥18 of 20 synthetic flights (T-V5)
- Median landing-error reduction ≥30% vs the frozen pre-flight prediction (T-V6)
- `README.md`, `statement.md` and a 15-section report PDF committed; tag `v1.0-submission`

## Current Progress

**Design is complete; no code has been written yet.** Nothing has been committed to a repo —
this folder is the entire state of the project.

Files in this handoff package:

| File | Contents |
|---|---|
| `CLAUDE.md` | Project constitution — hard rules, stack, commands, coding conventions, definition of done. Copy to the repo root so Claude Code loads it automatically. |
| `docs/BLUEPRINT.md` | The full specification: objectives O1–O6, FR-1.1…FR-4.4, NFR-1…NFR-6, UC-1…UC-5, ADR-1…ADR-10, class inventory, datasets DS-1…DS-7, evaluation methodology, test plan with T- IDs, traceability matrix, 10-week timeline. Mermaid and PlantUML diagram sources are embedded. |
| `docs/schema/V1__init.sql` | The 12-table SQLite schema, ready to drop into `src/main/resources/schema/`. Physics is enforced as CHECK constraints. |

Decisions already locked (full rationale in `docs/BLUEPRINT.md` §14):

- US Standard Atmosphere 1976, hand-written, 0–86 km (ADR-1)
- SQLite + plain JDBC + hand-written DAOs, no ORM (ADR-2)
- Bootstrap particle filter for parameter estimation, not EKF/UKF (ADR-3)
- Replay-only telemetry ingest; no live serial or radio in `src/main` (ADR-4)
- Explicit `ExecutorService` fixed pool; one derived seed per ensemble member (ADR-5, ADR-6)
- Single-sounding wind field, with the error absorbed into the dispersion (ADR-7)
- CLI plus exported PNG charts; Swing is Stretch only (ADR-8)

## What Worked

- **Specifying acceptance criteria before code.** Every FR carries a numeric acceptance
  criterion and every NFR a verification test ID, so "is this done?" is never a judgement call.
- **Anchoring validation on published references.** The USSA-1976 tables, the analytic
  buoyancy–drag terminal velocity and the drag-free closed forms give real oracles, so the
  tests check correctness rather than self-consistency.
- **Building the evaluation set from a seeded generator (DS-6).** No real 30 km or 45 km flight
  has flown yet, so 20 synthetic flights with known truth carry O3 and O4 — and they regenerate
  deterministically from `data/truth/seeds.csv`.
- **An MVP cut-line that is submittable on its own** (ingest → atmosphere → one flight →
  persistence → CSV export, target end of week 4). If the estimator slips, there is still a
  complete project.

## What Didn't Work

Nothing has failed yet — no code exists. Recorded here so the next agent does not re-litigate
the options that were already considered and rejected during design:

- **Orekit or Apache Commons Math for the maths.** Rejected: the course rewards my own
  implementation, and a library core would gut the marks for implementation quality. Write the
  integrator, the interpolation, the LHS sampler and the eigen-decomposition by hand.
- **GFS/GRIB2 4-D wind fields.** Rejected: GRIB2 decoding is a term project by itself and would
  require a download, breaking the offline rule. A single sounding plus a `wind_scale`
  dispersion parameter covers the error honestly.
- **EKF or UKF instead of a particle filter.** Rejected: burst is a hard discontinuity in the
  dynamics and the pre-burst posterior can be bimodal; Jacobians across the phase switch are
  ugly and fragile.
- **Live serial ingest from the ESP32 flight computer.** Rejected for `src/main`: a grader with
  no hardware must still be able to exercise the primary use case.
- **Singleton for the database connection.** Rejected: hides lifetime and breaks per-test
  isolation. Construct `Database` once in `Main` and inject it.
- **A GUI from week 1.** Rejected: no GUI toolkit appears in the lab record, and the marks come
  from the computational core, not the widgets.

## Next Steps

Work in order. Do not start step 5 before step 4 is green.

1. **Scaffold the repo at `C:\Projects\SKYFIX`** (or `~/Projects/skyfix`). `git init`, then
   copy `CLAUDE.md` to the root, `docs/BLUEPRINT.md` to `docs/`, and
   `docs/schema/V1__init.sql` to `src/main/resources/schema/`. Create the Maven project:
   groupId `com.skyfix`, artifactId `skyfix`, Java 21, Maven Wrapper committed
   (`mvn -N wrapper:wrapper`). Dependencies: `sqlite-jdbc`, `jackson-databind`, `xchart`
   (verify current versions), test scope `junit-jupiter` and `assertj-core`, plugin `jacoco`.
   Add `.gitignore` (`target/`, `out/`, `*.db`, `*.db-journal`, `.idea/`, `*.iml`, `*.log`)
   and `.github/workflows/build.yml` running `./mvnw -B verify` on JDK 21.
   Commit: `chore: project scaffold, Maven wrapper, CI`.

2. **Create the 10 packages and the `domain` layer** — `GeoPoint`, `BalloonState`,
   `BalloonConfig` with its Builder and every cross-field rule from FR-1.3, `FlightParameters`,
   `SimSettings`, `DispersionSpec`, `StateHistory`, `Phase`, `Units` (overloaded converters),
   `Geodesy` (haversine + bearing), and the six-type `SkyfixException` hierarchy.
   Tests: `T-U-GEO`, `T-U-BUILDER` (one negative test per rule). Commit per logical group.

3. **`Ussa1976Atmosphere` + `T-V1`.** Transcribe the 7 layer bases, then write
   `data/reference/ussa1976.csv` with 25 rows of T, p, ρ from the published tables and assert
   ≤0.1% relative error. Add `ExponentialAtmosphere` for comparison. Then `Integrator`,
   `Rk4Integrator`, `IntegratorFactory` with `T-U-INTEG` against `y' = -ky`.
   **This is the first real gate — do not move on until T-V1 passes.**

4. **Flight dynamics** — `FlightPhase` (Template Method), `AscentPhase`, `DescentPhase`,
   `FlightSimulator`. Then `T-V2` (analytic terminal ascent rate at 5 altitudes, ≤2%) and
   `T-V4` (gas-law invariant drift <1e-6). Add `WyomingSoundingReader` and `SoundingWindField`
   with `ConstantWindField` as the analytic test oracle.

5. **Persistence** — `Database`, `SchemaInitializer` (applies `V1__init.sql` idempotently and
   records `schema_version`), `Repository<T,K>` and the 8 DAOs. Tests `T-D1`, `T-D3`, `T-D4`,
   plus `T-S1` scanning `src/main` for concatenated SQL. Then wire `IngestCommand`,
   `PredictCommand` and `ValidateCommand` through `SkyfixCli`.
   **Tag `v0.1-mvp` here** — at this point the project is already submittable.

6. Continue with the week 5–10 plan in `docs/BLUEPRINT.md` §15: ensemble and ellipse (W5),
   plots and exports (W6), synthetic flights and burst detection (W7), particle filter and
   replay (W8), scoring and coverage (W9), report and screenshots (W10).

**Standing constraints while doing any of the above:** no scientific libraries in `src/main`,
no network in any test, SI units internally, `PreparedStatement` only, requirement ID in every
commit body, and a green commit every week — a single bulk upload at the end forfeits 10% of
the grade.

**Unverified items to check before they reach the report** (currently marked `verify:` in the
blueprint): the University of Wyoming and NOAA IGRA access terms and file layouts; Totex/Kaymont
burst-diameter figures; the EGM96 geoid offset for the launch region; the sphere drag-crisis Re
range; the haversine-vs-Vincenty difference at 400 km; DGCA/AAI rules for unmanned free balloons
in India; and current versions of `sqlite-jdbc`, Jackson and XChart. Never write a citation,
DOI or URL from memory.
