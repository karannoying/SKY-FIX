# Handoff: SKYFIX — balloon landing-footprint prediction with in-flight estimation

_Last updated: 2026-09-15_

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

**Weeks 1–10 are built and green; the project is at `v1.0-submission`.** `predict --members N`
produces a landing footprint — 50% and 95% confidence ellipses — rather than a single point, which
is objective O2. `replay` estimates the four flight parameters from a log with a pooled bank of
particle filters and re-predicts the footprint as the flight unfolds (O3, O4). DS-6, the
twenty-flight synthetic evaluation set O3 and O4 are scored on, regenerates byte-identically from a
committed seed file. The 15-section report, the screenshots and the diagrams are committed.

What is left is not code: two acceptance criteria need a decision from the course owner (T-V5's
ascent-Cd criterion), the five milestone tags are created locally but the relay's push policy
refuses `refs/tags/*`, and the verification ledger's entries need a machine with network access.

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
4. ~~`ParticleFilter`, `GaussianMeasurementModel`, `SystematicResampler`, `ReplayService`, T-V5.~~
   **Built and green.** `run.sh replay` estimates parameters from a log and re-predicts the
   footprint as the flight unfolds. ADR-3 has the design and every measurement behind it; ADR-17
   settles the FR-3.3 cadence question that was open here.

   **T-V5, all twenty DS-6 flights at N = 500** (`ParameterRecoveryTest`, `-Pperf`, 6.5 min):

   | criterion | result | status |
   |---|---|---|
   | burst altitude within 500 m | **20 / 20** (17 inside 130 m, worst 461 m) | **gated, passing** |
   | ascent Cd within 5% | 5 / 20 | reported, not gated — see below |
   | parachute Cd within 5% | 15 / 20 | reported — bimodal, unexplained |

   **Three findings, in order of how much they matter.**

   - **The 5-95% bands are not calibrated.** Coverage of the value each flight was generated from
     is **0/20** for free lift, ascent Cd and burst scale, and 5/20 for parachute Cd; a calibrated
     band would cover about 18/20. The medians are good — burst altitude to tens of metres — but
     the intervals around them are not intervals anyone should rely on, and that goes straight at
     the project's central claim. Cause understood (particle impoverishment); the fix is not simply
     a wider roughening floor, since holding a dimension open at its prior width was measured to
     take the ascent-Cd median from 1.6% to 32% error. **This is the first job of week 9** and is
     what T-V6 is for. Until then, do not quote a band as a credible interval anywhere.
   - **Ascent Cd is not identifiable to 5% from this observation set**, so T-V5's criterion tests
     the prior rather than the filter. Measured: a 5% ascent-Cd error absorbed by a 6% free-lift
     change reproduces the whole flight's altitude profile to 10.5 m RMS — the GPS noise itself —
     and over the ascent alone a 20% error matches to 0.44 m. The two columns slide together on
     every flight. **Amending a graded acceptance criterion is not the implementer's call**: the
     measurement and a proposed replacement are in ADR-3's Consequences, and BLUEPRINT §10 stands
     until someone decides. `ParameterRecoveryTest` prints the number and gates on burst altitude.
   - **Parachute drag is bimodal** — sixteen flights under 5%, most under 1%, and four at 15-26%.
     Not correlated with the flight's wind-scale error, its burst-altitude error, or anything else
     checked. Worth an hour before the report; it is reported, not gated.

   Four defects were found by measurement during this work and are written up in ADR-3 with their
   numbers: the vertical-rate sigma must be derived from the differencing baseline rather than
   assumed; burst diameter must be redrawn from a *censored* prior, not a plain one; burst must be
   signalled by `BurstDetector` rather than by the first particle to burst; and the Liu-West jitter
   kernel needs the full weighted covariance, not per-dimension variances.

   **Still to do before tagging `v0.3-estimator`:** the tag itself, and a decision on the T-V5
   criterion above.

5. ~~Band calibration, `ReportService`, T-V6, T-V7, PL-3…PL-5, T-E3, coverage to 80%.~~
   **Built and green.** Tag `v0.4-validated`.

   **The calibration defect is fixed, and the diagnosis is the interesting part.** Eight filters
   identical but for their seed disagreed about ascent Cd by 0.169 while each reported a band of
   width 0.0017 — every filter understating its own uncertainty by a factor of about a hundred. So
   the dominant error was Monte Carlo, not statistical, and no single filter could ever see it.
   `FilterBank` pools independent filters over the *same* particle budget; ADR-18 has the numbers.

   | 5-95% band covers truth | free lift | ascent Cd | burst scale | chute Cd | burst ≤ 500 m |
   |---|---|---|---|---|---|
   | 1 filter × 500 | 0/20 | 0/20 | 0/20 | 5/20 | 20/20 |
   | 8 × 62 (same 500 budget) | 13/20 | 16/20 | 14/20 | 16/20 | 19/20 |
   | 16 × 125 (default) | 17/20 | 17/20 | 15/20 | 20/20 | 20/20 |

   **T-V6 passes: 74.0% median landing-error reduction at burst**, against 30% required, across all
   twenty flights, from a median frozen error of 15.4 km. Scored at burst rather than at landing,
   because burst is when a recovery team commits to a drive and the whole descent is still ahead.
   Two flights go negative — both had a wind scale near 1.0, so the pre-flight guess was already
   within 1.4 km. The benefit is large in the median and not guaranteed per flight.

   **T-V7 passes: the 95% ellipse contains 94.6% of the cloud it was fitted to and 94.0% of an
   independent one** (window 0.90–0.98). The 50% ellipse over-covers at ~56%, and the measurement
   says why: the fitted footprint is 76.4 × 0.5 km, an aspect ratio near 150, because the sounding's
   wind direction barely turns with altitude. **The landing footprint is effectively
   one-dimensional** — the uncertainty is about how long the flight lasts, not where it goes — so a
   chi-square scaling for two degrees of freedom is generous at low confidence and converges
   towards nominal as confidence rises. Good report material for §11.

   Two defects found by measurement, both in ADR-3/the commit bodies with their numbers: the
   re-prediction was being seeded with the telemetry's own vertical rate (a difference of noisy
   altitudes, ±7 m/s) which put drag damping past RK4's stability limit and discarded 128 of 200
   members on a normal ascent; and `EnsembleRunner`'s 1% failure threshold failed any ensemble
   under a hundred members on its first bad draw.

   Coverage, re-measured from a clean `target/` on 2026-09-15: `estimation` **91.7%**,
   `core.atmos` 100%, `core.flight` 93.1%, `domain` 93.7% — the 80% gate passes. Outside the strict
   gate by design (BLUEPRINT §11): `io` 74.9%, `app` 73.8%, `persistence` 73.2%, `cli` 69.9%.
   81.6% overall.

   **Measure it from a clean `target/`.** JaCoCo's agent appends to an existing `jacoco.exec`, so
   generating the report after a `-Dtest=SomeClass` run shows that one class's coverage under the
   whole project's name. An earlier capture of SC-8 was taken that way and understated `cli` and
   `io` by more than twenty points each. `rm -f target/jacoco.exec` before `./mvnw verify`.

   **Still open, and worth an hour each before the report:**
   - Parachute drag is bimodal — sixteen flights under 5%, most under 1%, four at 15-26%. Not
     correlated with wind-scale error, burst-altitude error, or anything else checked.
   - Burst scale is the weakest band at 15/20 coverage.
   - T-V5's ascent-Cd criterion still needs a decision (see step 4) — it is measured and printed,
     not gated, and BLUEPRINT §10 stands until someone rules on it.

6. ~~Report, screenshots SC-1…SC-10, compliance checklist.~~ **Done.** Tag `v1.0-submission`.

   `docs/report/report.pdf` is 38 pages across the 15 required sections plus a compliance checklist
   and a reproduce-every-number appendix, built by `python3 scripts/build-report.py`. The build
   **inlines** the console transcripts from `docs/screenshots/` rather than quoting them, so the
   report cannot drift from the runs that produced it — which is the structural version of
   CLAUDE.md's fifth rule.

   `docs/screenshots/` holds SC-1…SC-8, SC-10…SC-12 as verbatim stdout plus the exported charts at
   1460 px or wider. `docs/diagrams/` now exists — it was empty, which rule 6 would not have
   allowed at submission — with the architecture diagram as SVG and the replay sequence as Mermaid,
   the latter carrying ADR-17's correction.

   **What is genuinely not done, and is listed in the report's own §15 rather than hidden:**
   - **SC-9** needs a capture of a GitHub Actions run list, which only exists once the branch has
     been pushed and the workflow has run. Everything else is generated.
   - **T-V5's ascent-Cd criterion** still needs a decision from the course owner. Measured and
     printed, not gated; BLUEPRINT §10 stands.
   - Two references need their full citations checked.
   - The `verify:` markers on DS-1, DS-3, DS-4 and the IUPAC molar masses are unchanged; the build
     environment could not reach the sources. `grep -rn "verify:" src data docs` lists them.
   - Parachute-drag recovery is bimodal and unexplained; burst-scale band coverage is 15/20.

**Standing constraints:** no scientific libraries in `src/main`, no network in any test, SI units
internally, `PreparedStatement` only, requirement ID in every commit body, and a green commit
every week — a single bulk upload at the end forfeits 10% of the grade.

## Unverified items — must be cleared before the report

**`docs/VERIFICATION.md` is the authority.** It carries one entry per marker with the exact source,
the exact check, and what changes if the answer differs, and
`SourceRulesTest.everyMarkerHasARouteToClearingIt` fails the build on any marker in `src/main` or
`data/` that has no entry — so the ledger cannot drift from the tree. The table below is the
summary; read the ledger before acting on any row.

Every remaining item is blocked on a document this build environment cannot reach: `ntrs.nasa.gov`,
`weather.uwyo.edu`, `ciaaw.org` and `doi.org` all resolve to nothing here, re-checked on
2026-09-15. None is blocked on more work in the repository.

| Item | Where | What is needed |
|---|---|---|
| **DS-3 reference table** | `data/reference/ussa1976.csv` header | Hand-transcribe from NOAA-S/T 76-1562 Table I. The current rows come from two independent implementations agreeing to 0.00987%, which corroborates but is not the primary source. The archive was unreachable from the build environment. |
| **DS-1 real soundings** | `data/soundings/` | The committed profile is **synthetic and labelled as such**. Fetch three real cached soundings and confirm the University of Wyoming column layout and terms of use. |
| **Lifting-gas molar masses** | `LiftGas.java` | Cite IUPAC standard atomic weights. |
| **Burst-diameter figures (DS-4)** | `data/missions/balloon.json` | Check against the Totex/Kaymont datasheet. |
| **EGM96 geoid offset** | `data/missions/mission.json` | Confirm the undulation for the launch region. |
| **Sphere drag-crisis Re range** | BLUEPRINT §10 | Confirm before quoting "Cd is roughly flat". Nothing depends on it — Cd is estimated from telemetry, so this justifies the model choice rather than feeding it. |
| **DGCA/AAI rules** | BLUEPRINT §5 | Confirm requirements for unmanned free balloons in India. No regulatory number is quoted anywhere, so this is scope rather than correctness. |
| **Pre-flight dispersion spreads** | `DispersionSpec.java` | Needs a manufacturer tolerance or a population of real flights. **Cannot** be fitted to DS-6: DS-6's truths are drawn from this very spec, so the earlier proposal to do so was circular and is withdrawn. |
| **Barometric sigma factor** | `GaussianMeasurementModel.java` | Measured at **1.71** on DS-6 and held at 4.0. DS-6 generates pressure from the same atmosphere model the reader inverts, so 1.71 is a floor containing no model bias. Needs a real dual-sensor log. |
| **Two paper citations** | report §17 | Liu & West; Gordon, Salmond & Smith. Known but not openable offline, and rule 5 forbids writing a DOI unseen. |

**Cleared since this table was written.** Kept visible so "unverifiable here" stays distinguishable
from "nobody measured it":

- **LHS convergence claim** — BLUEPRINT §10's "~400 members vs ~1,500 for plain MC" is measured
  and **retired at about 2.2x rather than 3.75x**. Across seven independent seeds per point, LHS's
  seed-to-seed scatter in the fitted 95% semi-major axis is lower than plain Monte Carlo's at every
  count from 100 to 3,200, by about 1.4x; scatter falls as N^-0.53 and N^-0.46 respectively, so a
  1.4x scatter advantage is about a 2x member advantage. `SamplerConvergenceTest` (`-Pperf`, ~3 min)
  re-derives it deterministically; ADR-14 carries the table.
  **Read the ADR before quoting this.** A first attempt ran one seed per point and compared each
  ensemble with its own 3,200-member value, which is one sample of the spread being measured, and
  it reported LHS as *worse*. The statistic, not the sampler, was the problem.
- **Screenshots SC-1…SC-12** — all captured, SC-9 included; the CI history could only be read once
  the branch had been pushed and the workflow had run fifteen times.

Never write a citation, DOI or URL from memory.
