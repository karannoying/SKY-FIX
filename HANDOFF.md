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

**The MVP cut-line is built, green and tagged `v0.1-mvp`.** Handoff steps 1–5 are complete:
scaffold → domain → atmosphere and integrators → flight dynamics and wind → persistence → CLI.

A fresh clone plus a JDK runs the whole path offline in three commands. `./mvnw verify` passes
with the JaCoCo gate. `./scripts/run.sh validate` reports **84 checks, 84 passed**.

### What exists

| Layer | Classes | Verified by |
|---|---|---|
| `domain` (+ `domain.error`) | `GeoPoint`, `BalloonState`, `StateHistory`, `Phase`, `LiftGas`, `BalloonConfig` + Builder, `FlightParameters`, `SimSettings`, `Units`, `Geodesy`, six-type exception hierarchy | T-U-GEO, T-U-BUILDER, T-E1 |
| `core.atmos` | `AtmosphereModel`, `Ussa1976Atmosphere`, `ExponentialAtmosphere`, `WindField`, `ConstantWindField`, `SoundingWindField`, `WindFieldFactory`, `SoundingLevel`, `WindSample` | **T-V1**, T-U-ATMOS, T-U-WIND |
| `core.flight` | `Integrator`, `Rk4Integrator`, `Rkf45Integrator`, `IntegratorFactory`, `FlightPhase`, `AscentPhase`, `DescentPhase`, `FlightSimulator` | T-U-INTEG, **T-V2**, **T-V3**, **T-V4** |
| `persistence` | `Database`, `SchemaInitializer`, `Repository<T,K>`, `MissionDao`, `BalloonConfigDao`, `SoundingDao`, `RunDao`, `RunStateDao`, `ValidationDao` | T-D1, T-D3, T-D4, **T-S1** |
| `io` | `SoundingReader`, `WyomingSoundingReader`, `ConfigLoader`, `CsvWriter`, `GeoJsonWriter` | T-E3, FR-1.1 suite |
| `app` | `RunContext`, `IngestService`, `PredictionService`, `ValidationService` | T-U1 |
| `cli` | `SkyfixCli`, `ConsoleReporter`, `Main` | T-U1, T-E1, T-E3 |

### Measured results

| Case | Tolerance | Measured |
|---|---|---|
| T-V1 USSA-1976 at 25 altitudes | 0.1% relative | **T 0.0008%, p 0.0096%, ρ 0.0107%** |
| T-V2 ascent rate vs analytic terminal velocity | 2% | **worst 0.017%** |
| T-V3 RK4 vs RKF45 landing, dt 0.5/0.25/0.125 s | 50 m | **0.0025 / 0.000085 / 0.0000048 m** |
| T-V4 gas-law mass invariant | 1e-6 | **7.9e-16** |
| Coverage, `core.atmos` / `core.flight` | 80% | **100% / 96.4%** |

Two blueprint `verify:` items are now measured rather than estimated:
**ADR-9** haversine vs Vincenty at ~400 km = **0.327%**; **ADR-1** a best-fit exponential
atmosphere is **20.4%** off in density at the tropopause and **48.3%** off by 35 km.

## What Worked

- **Specifying acceptance criteria before code.** Every FR carries a numeric acceptance
  criterion and every NFR a verification test ID, so "is this done?" is never a judgement call.
- **Anchoring validation on published references.** The USSA-1976 tables, the analytic
  buoyancy–drag terminal velocity and the drag-free closed forms give real oracles, so the
  tests check correctness rather than self-consistency.
- **Independent oracles wherever the obvious check would be circular.** T-U-GEO asserts five
  separations that are exact multiples of the earth radius and cross-checks against the
  spherical law of cosines; ADR-9's error term is measured against a Vincenty implementation
  written in the test file; the T-V1 reference table came from two independent third-party
  implementations of the standard that agree to 0.00987%. Each of these caught or quantified
  something a self-consistency check would have passed.
- **Enforcing the hard rules mechanically.** `SourceRulesTest` scans `src/main` for concatenated
  SQL, scientific-library imports, network imports, package cycles, missing Javadoc and missing
  `verify:` markers. It made the codebase comply rather than the reverse — two DAOs were
  rewritten to use whole SQL literals because `"SELECT " + COLUMNS + " FROM run"` is still SQL
  assembled from parts.
- **An MVP cut-line that is submittable on its own.** Reached on schedule; if the estimator
  slips there is still a complete project.

## What Didn't Work

Design-stage rejections (do not re-litigate): Orekit/Commons Math for the maths; GFS/GRIB2 4-D
wind fields; EKF or UKF instead of a particle filter; live serial ingest in `src/main`; a
singleton database connection; a GUI from week 1. Rationale is in `docs/BLUEPRINT.md` §14.

Things the implementation itself disproved:

- **T-V3 at dt = 1 s does not hold, and the tolerance was never the problem.** Quadratic drag
  linearises to a real eigenvalue whose magnitude grows as the payload falls into denser air,
  reaching ~3.6 /s near the ground; RK4's real-axis stability limit of 2.785 caps the step at
  ~0.77 s there. At 1 s the landing came out 39 s late, ~470 m off in a 12 m/s wind, with the
  descent rate visibly oscillating. The default step is now 0.25 s, the simulator **refuses**
  any unstable step naming the largest that would work, and each integrator derives its own
  limit from itself. See **ADR-13**. This is the binding constraint on NFR-1 — see below.
- **Free lift and launch diameter are redundant inputs.** Free lift wins, because it is what the
  filter estimates and what a crew measures. See **ADR-11**.
- **A silent drop in the sounding parser.** A line opening with `-` was classified as a header
  and skipped, so a negative pressure was never reported — exactly the silent drop FR-1.1
  forbids. Found by writing the negative test, not by reading the code.
- **`run` does not cascade from `mission`, deliberately.** The first T-D3 asserted a cascade
  that would have destroyed the provenance record NFR-5 exists to keep.

## Next Steps

Work in order. The MVP is tagged, so everything below is additive.

1. **`DispersionSampler` (Latin hypercube over 5 parameters), `EnsembleRunner`, `EllipseFitter`
   (covariance eigen-decomposition by hand), T-P1, T-U-LHS, T-U-ELLIPSE.** Week 5, tag
   `v0.2-ensemble`.
   **Read ADR-13 first.** A single flight is ~50 ms at dt = 0.25 s, so 1,000 members is ~50 s
   single-threaded. NFR-1's 30 s budget on 4 cores is reachable but no longer comfortable, and
   the 0.25 s step is not negotiable downward. Measure T-P1 early; if it misses, the honest
   options are a coarser `stateSampleStride`, a larger pool, or restating NFR-1 with the
   stability constraint as the reason — not a bigger step.
2. **`PlotExporter` (PL-1, PL-2), `ConsoleReporter` tables, T-U1 extension.** Week 6, tag
   `v0.2-ensemble`.
3. **`SyntheticFlightWriter` + 20 truth flights from `data/truth/seeds.csv`, `CsvTelemetryReader`,
   `BurstDetector` + T-E2.** Week 7.
4. **`ParticleFilter`, `GaussianMeasurementModel`, `SystematicResampler`, `ReplayService`, T-V5.**
   Week 8, tag `v0.3-estimator`. Do not start before the ensemble runner is green.
5. **`ReportService`, T-V6, T-V7, PL-3…PL-6, coverage to 80% on `estimation`.** Week 9, tag
   `v0.4-validated`.
6. **Report, screenshots SC-1…SC-10, compliance checklist.** Week 10, tag `v1.0-submission`.

**Standing constraints:** no scientific libraries in `src/main`, no network in any test, SI units
internally, `PreparedStatement` only, requirement ID in every commit body, and a green commit
every week — a single bulk upload at the end forfeits 10% of the grade.

## Unverified items — must be cleared before the report

These carry literal `verify:` or `[PLACEHOLDER — …]` markers in the tree; `grep -rn "verify:" src data docs`
finds them all.

| Item | Where | What is needed |
|---|---|---|
| **DS-3 reference table** | `data/reference/ussa1976.csv` header | Hand-transcribe from NOAA-S/T 76-1562 Table I. The current rows come from two independent implementations agreeing to 0.00987%, which corroborates but is not the primary source. The archive was unreachable from the build environment. |
| **DS-1 real soundings** | `data/soundings/` | The committed profile is **synthetic and labelled as such**. Fetch three real cached soundings and confirm the University of Wyoming column layout and terms of use. |
| **Lifting-gas molar masses** | `LiftGas.java` | Cite IUPAC standard atomic weights. |
| **Burst-diameter figures (DS-4)** | `data/missions/balloon.json` | Check against the Totex/Kaymont datasheet. |
| **EGM96 geoid offset** | `data/missions/mission.json` | Confirm the undulation for the launch region. |
| **Sphere drag-crisis Re range** | BLUEPRINT §10 | Confirm before quoting "Cd is roughly flat". |
| **DGCA/AAI rules** | BLUEPRINT §5 | Confirm requirements for unmanned free balloons in India. |
| **LHS convergence claim** | BLUEPRINT §10 | "~400 members vs ~1,500 for plain MC" must be measured, not repeated. |
| **Screenshots SC-1…SC-10** | `README.md`, `docs/screenshots/` | Capture after `v0.4-validated`. |

Never write a citation, DOI or URL from memory.
