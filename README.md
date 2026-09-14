# SKYFIX

Predicts where a high-altitude balloon payload will land, and — once the estimator lands — re-estimates
the flight's real parameters from replayed telemetry and re-predicts the footprint as a confidence
ellipse.

Submission for **CSE2006 Programming in Java**, VIT Bhopal University — Kumar Karan Bohidar,
Reg. No. 25BAS10049.

## What it does today

This is **`v0.2-ensemble`**: ingest → atmosphere → flight model → Monte Carlo footprint →
persistence → CSV/GeoJSON export, with the reference-case validation suite wired to a CLI command.
The particle-filter estimator is specified in `docs/BLUEPRINT.md` and is not implemented yet;
`validate` lists the cases that depend on it by name rather than passing silently.

| Capability | Status |
|---|---|
| USSA-1976 atmosphere, 0–86 km, hand-written (FR-2.1) | done, validated by T-V1 |
| RK4 and RKF45 integrators (FR-2.3) | done, validated by T-U-INTEG, T-V3 |
| Ascent / burst / parachute descent flight model (FR-2.3) | done, validated by T-V2, T-V4 |
| Radiosonde sounding ingest and wind interpolation (FR-1.1, FR-2.2) | done, validated by T-U-WIND, T-E3 |
| SQLite persistence, 13 tables, 6 DAOs (ADR-2) | done, validated by T-D1, T-D3, T-D4, T-S1 |
| CLI: `ingest`, `predict`, `validate`, `version` | done, validated by T-U1, T-E1 |
| Monte Carlo ensemble + 50/95% ellipse (FR-2.4) | done, validated by T-U-LHS, T-U-ELLIPSE, T-P1, T-R1 |
| Telemetry ingest and replay (FR-1.2) | done, validated by T-E5, T-P4 |
| Synthetic flight generator, DS-6 (FR-1.4) | done, validated by `SynthServiceTest` |
| Burst detection (FR-3.4) | done, validated by T-E2 |
| Particle-filter parameter estimation (FR-3.1–3.3) | **not yet** — week 8 |
| PNG plots PL-1, PL-2, PL-6 (FR-4.2) | done, validated by `PlotExporterTest` |

## Requirements

JDK 21. Nothing else — Maven comes from the committed wrapper, and **no command or test touches the
network**. All data ships in `data/`.

## Install and run

```bash
git clone <repository-url> && cd SKY-FIX

# Unix / macOS
./scripts/run.sh validate
./scripts/run.sh ingest  --sounding data/soundings/SYNTHETIC_2026-09-14_00Z.txt \
                         --epoch 2026-09-14T00:00:00Z
./scripts/run.sh predict --mission data/missions/mission.json \
                         --balloon data/missions/balloon.json --sounding-id 1

# a dispersed footprint rather than a single point: 50% and 95% confidence ellipses
./scripts/run.sh predict --mission data/missions/mission.json \
                         --balloon data/missions/balloon.json --sounding-id 1 --members 1000

# regenerate the DS-6 synthetic evaluation set (20 flights with known truth)
./scripts/run.sh synth   --mission data/missions/mission.json \
                         --balloon data/missions/balloon.json
```

```cmd
REM Windows
scripts\run.cmd validate
scripts\run.cmd predict --mission data\missions\mission.json --balloon data\missions\balloon.json
```

`predict` writes `out/run-<id>/` containing `trajectory.csv`, `summary.csv` and `flight.geojson`
(drop the GeoJSON onto any map to see the track, burst point and landing point). With `--members`
it also writes `landing-scatter.csv`, `ellipses.csv` and `footprint.geojson`, the last holding each
confidence ellipse as a polygon alongside every member's landing point, plus two charts:
`pl1-altitude.png` (altitude against time, ensemble behind the nominal flight) and
`pl2-footprint.png` (the landing scatter with both ellipses). `validate --plots` adds
`pl6-atmosphere-residual.png`, which shows how the atmosphere model's error behaves with altitude
rather than as the single worst-case number T-V1 reports.

**Reading PL-2.** Its axes share a scale, so the ellipse has its true shape. Under the synthetic
sounding shipped in `data/soundings/`, whose wind veers only about 40° over the whole profile, the
footprint comes out extremely elongated — roughly 180:1 — because almost all the uncertainty is in
how long the balloon stays airborne, and that lands along one axis. A real sounding veers more and
gives a rounder footprint. The sliver is the data's shape, not a plotting fault; the chart could
only hide it by distorting the axes. Per-run detail goes
to `out/skyfix.0.log`; the console carries warnings and above.

### Exit codes

`validate` exits non-zero if any tolerance is breached, so it works as a CI gate.

| Code | Meaning | Code | Meaning |
|---|---|---|---|
| 0 | success | 4 | model queried outside its valid range |
| 1 | usage error | 5 | solver did not converge |
| 2 | input violates a stated rule | 6 | persistence failure |
| 3 | file unparseable at `file:line` | 70 | unexpected — full trace in `out/error.log` |

## Testing

```bash
./mvnw test          # unit, model validation and DB integration
./mvnw verify        # adds the JaCoCo coverage gate
./mvnw verify -Pperf # adds timing and reproducibility tests
```

Measured line coverage: `core.atmos` **100%**, `core.flight` **96.4%** (gate: 80% on `core.*` and
`estimation`), `domain` 96.4%, `io` 90.3%, `app` 86.4%.

## Validation results

Every case compares the model against something computed **outside** it — a published reference
table, a closed-form solution, or a second integration scheme. `./scripts/run.sh validate` prints
this table; the figures below are its current output.

| Case | What it checks | Tolerance | Measured |
|---|---|---|---|
| T-V1 | USSA-1976 T, p, ρ at 25 altitudes, 0–47 km, against DS-3 | 0.1% relative | **T 0.0008%, p 0.0096%, ρ 0.0107%** |
| T-V2 | Ascent rate against analytic buoyancy–drag terminal velocity, 5 altitudes | 2% | **worst 0.017%** |
| T-V3 | RK4 vs RKF45 landing separation, dt = 0.5 / 0.25 / 0.125 s | 50 m | **0.0025 / 0.000085 / 0.0000048 m** |
| T-V4 | Gas mass recovered from each stored diameter | 1e-6 relative | **7.9e-16** |
| T-U-ELLIPSE | Recover known semi-axes and orientation from a synthetic cloud | 2% | **0.58% / 0.51% / 0.27°** |
| T-P1 | 1,000-member ensemble, 4 cores, warm JVM, sounding wind | 30 s | **5.84 s** (3-run median) |
| T-P2 | 200-member re-prediction | 5 s | **1.05 s** |
| T-P4 | 10,000 telemetry samples parsed | 3 s | **0.11 s** |
| T-E2 | Burst detection, 10 m GPS noise, dropouts | 5 s, 150 m, 0 false positives | **0.0–2.3 s, 1–36 m, none** |
| T-R1 | Same seed, 1 thread vs 4 threads, identical ellipse | 1e-9 | **met** |
| T-V5–T-V7 | Parameter recovery, error reduction, ellipse containment | — | not yet implemented (weeks 7–9) |

Two figures the blueprint left open are now measured rather than estimated:

- **ADR-9** — haversine against a Vincenty inverse solution on the WGS-84 ellipsoid, at ~400 km
  mission scale: **0.327%**, far below the wind-field error that dominates the prediction.
- **ADR-1** — a best-fit single-scale-height exponential atmosphere is **20.4%** off in density at
  the tropopause and **48.3%** off by 35 km, which is why USSA-1976 was implemented instead.

## A finding worth knowing about

The blueprint specified T-V3 "at dt ≤ 1 s". It does not hold there, and the reason is not a tight
tolerance. Quadratic drag linearises to a real eigenvalue `λ = −ρ·Cd·A·|v|/m` whose magnitude **grows
as the payload descends into denser air**, reaching ~3.6 /s near the ground. RK4's real-axis stability
limit is 2.785, which caps the step at ~0.77 s there — so a 1 s step is outside the region for the
last few kilometres of every descent, and the landing came out 39 s late and ~470 m off, with the
descent rate visibly oscillating.

The default step is therefore 0.25 s, and `FlightSimulator` refuses any step outside the integrator's
stability region rather than returning a plausible-looking wrong answer. Each integrator derives its
own limit by applying itself to `y' = λy` — nothing is transcribed. See `docs/adr/ADR-13.md`.

## Known limitations

- **The footprint assumes the landing scatter is roughly Gaussian.** The ellipse comes from a
  covariance fit, so a strongly sheared wind could produce a scatter it describes poorly. A very
  high aspect ratio is the signal — a constant-wind ensemble gives one in the thousands, because
  every bit of the dispersion then lands along a single axis. T-V7 will measure containment against
  real profiles; if the assumption fails there, the honest fix is a convex hull or a density
  contour, reported as such (ADR-14).
- **A single sounding stands in for a 4-D wind field** (ADR-7). Over a long drift the profile is
  assumed to hold along the whole track. This is the model's largest named error source; the
  `wind_scale` dispersion exists to carry it into the footprint once the ensemble lands.
- **Constant ground elevation.** No terrain lookup (SRTM is Stretch).
- **Replay only.** No live serial or radio ingest in `src/main`, by design (ADR-4).
- **`data/soundings/` ships a synthetic profile**, clearly labelled, because the archive was not
  reachable from the build environment. See the file header and `[PLACEHOLDER — DS-1]`.
- **DS-6 is synthetic by necessity.** No real 30 km or 45 km flight log exists yet, so O3 and O4
  are scored on twenty generated flights with known truth. `data/truth/seeds.csv` is the dataset
  definition and the telemetry regenerates from it byte-identically; the generated logs are not
  committed. Every file says in its first line that it is synthetic. When HabSat flies, the same
  commands score a recorded log with no code change.
- **`data/reference/ussa1976.csv` is corroborated, not yet transcribed from the primary document.**
  Its 25 rows come from two independent third-party implementations of the standard that agree to
  0.00987%. The header carries a `verify:` marker requiring hand transcription from NOAA-S/T 76-1562
  Table I before the report cites it.

## Screenshots

[PLACEHOLDER — SC-1…SC-10, due after `v0.4-validated` per BLUEPRINT §16.]

## Documentation

- `docs/BLUEPRINT.md` — the specification: FR/NFR/UC/ADR/T- IDs, datasets, evaluation methodology,
  traceability matrix, timeline.
- `docs/adr/` — one file per design decision, including the three this implementation added
  (ADR-11 free lift drives inflation, ADR-12 RKF45 is a fixed-step cross-check, ADR-13 the step is
  bounded by descent stability).
- `HANDOFF.md` — current state and the next steps in order.
- `docs/schema/V1__init.sql` — the 13-table schema, with physics enforced as CHECK constraints.

## Licence and attribution

Coursework submission. The USSA-1976 tables are a US Government publication; the sounding layout
follows the University of Wyoming upper-air archive (`verify:` its terms before redistributing any
real file).
