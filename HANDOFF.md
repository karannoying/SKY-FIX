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

**Weeks 1–7 are built and green.** `predict --members N` produces a landing footprint — 50% and
95% confidence ellipses — rather than a single point, which is objective O2; the charts are
exported; and DS-6, the twenty-flight synthetic evaluation set that O3 and O4 are scored on,
regenerates byte-identically from a committed seed file. What remains is the estimator itself.

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
| T-U-ELLIPSE semi-axes and orientation | 2% | **0.58% / 0.51% / 0.27°** |
| T-U-ELLIPSE containment at 50/90/95% | 0.90–0.98 at 95% | **49.9 / 90.0 / 95.1%** |
| T-P1 1,000 members, 4 cores, sounding wind | 30 s | **5.84 s** (3-run median) |
| T-P2 200-member re-prediction | 5 s | **1.05 s** |
| T-R1 same seed, 1 vs 4 threads | 1e-9 | **met** |
| FR-4.2 chart export | 5 s | **100 ms** |
| T-P4 10,000 telemetry samples | 3 s | **112 ms** |
| T-E2 burst detection (6 seeds) | 5 s, 150 m, 0 false pos | **0.0–2.3 s, 1–36 m, none** |
| FR-1.4 DS-6 byte-identical regeneration | exact | **met** |
| Coverage, `core.atmos` / `core.flight` | 80% | **100% / 96.4%** |

**Measure timing with `-Pperf` only, and with nothing else on the machine.** T-P1's first reading
was 91,670 ms — void, because it ran under the JaCoCo agent and shared four cores with a concurrent
build. ADR-15 records the conditions the real figure was taken under.

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
- **A Maclaurin series for `erf` is wrong past |x| ≈ 3.** Its largest term grows like `exp(x²)`
  before cancelling back to a result of order one, so `cdf(8)` came out 7e-5 wrong — invisible,
  because the quadrature check that would have caught it only swept ±4. Now a continued fraction
  handles the tail, and the check sweeps ±8.
- **Every ensemble member was building a trajectory that was thrown away.** About three million
  wasted objects per 1,000-member run, at 16.6 s against 9.7 s once members outside the retention
  sample stopped sampling states at all.
- **A performance figure measured under instrumentation is not a performance figure.** See ADR-15.
- **Out-of-order counting has two defensible definitions, and they differ.** Counting stream
  *inversions* (a packet whose timestamp precedes the one before it) is what a replay notices as a
  late packet; counting packets displaced from their sorted position reports a larger number that
  says more about the sort than about the radio link. SKYFIX counts inversions, and
  `CsvTelemetryReaderTest` spells out why — the first version of that test asserted the other
  definition's answer and failed.
- **Charts need looking at, not just testing.** Three PL-1/PL-2 defects — every line silently
  dashed by XChart's default style cycling, a footprint framed on a launch site 143 km away, and a
  title clipped at both ends — all passed the automated checks and were only caught by rendering
  the images and reading them. `PlotExporterTest` now at least catches a blank panel and a series
  drawn in the wrong colour; the rest still needs eyes.

## Next Steps

Work in order. The MVP is tagged, so everything below is additive.

1. ~~`DispersionSampler`, `EnsembleRunner`, `EllipseFitter`, T-P1, T-U-LHS, T-U-ELLIPSE.~~
   **Done.** NFR-1 is met with room to spare: **5,836 ms median** for 1,000 members on 4 cores,
   against a 30 s budget, at the 0.25 s step ADR-13 requires and against a sounding-interpolated
   wind field. T-P2 is 1,054 ms against 5 s.
   An earlier note here warned that the budget was tight, on the basis of a ~50 ms-per-flight
   figure taken from a cold JVM. Warm, a flight costs about 18 ms. Disregard the warning.
2. ~~`PlotExporter` (PL-1, PL-2), T-U1 extension.~~ **Done**, plus PL-6. Charts render in 100 ms
   against the 5 s budget. See ADR-16 for the design decisions, and read it before adding PL-3
   to PL-5 so the new charts match: validated colour, one axis, solid lines, muted chrome for
   thresholds.
3. ~~`SyntheticFlightWriter` + 20 truth flights, `CsvTelemetryReader`, `BurstDetector` + T-E2.~~
   **Done.** DS-6 exists and regenerates byte-identically via `run.sh synth`; T-E2 passes on six
   seeds with 0.0–2.3 s and 1–36 m error and no false positives across dropouts.
4. **`ParticleFilter`, `GaussianMeasurementModel`, `SystematicResampler`, `ReplayService`, T-V5.**
   Week 8 — **next**, tag `v0.3-estimator`. The ensemble runner is green, so this is unblocked.

   What is already in place: `DispersionSpec` is the prior the particle set is drawn from and
   `DispersionSampler` can draw it; `BurstDetector` tells the filter when to switch to the descent
   model; `FlightSimulator.runFrom` propagates a particle from a measured state; `EnsembleRunner`
   does the FR-3.3 re-prediction, measured at 1,054 ms for 200 members.

   Three things to decide early:
   - **Cost.** ADR-3 caps the filter at 500 particles. Propagating 500 particles per update over a
     ~8,000-sample log is the expensive part, so measure it before tuning anything else — the
     answer probably means propagating on a coarser cadence than every sample.
   - **FR-3.3's stated rationale does not hold arithmetically.** It justifies a 5 s re-prediction
     budget as letting "a 1 Hz log replayed at 10x drop no updates", but 10x replay allows 100 ms
     per sample, so a 5 s re-prediction cannot run per-sample under any implementation. The
     tolerance is met (1,054 ms); the rationale implies re-prediction is periodic rather than
     per-sample. Settle which it is before building on it.
   - **The measurement model needs a pressure channel.** `CsvTelemetryReader` already derives
     pressure altitude and flags it, so the filter can weight a GPS altitude and a barometric one
     differently — worth doing, since a dropout leaves only the barometer.
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
