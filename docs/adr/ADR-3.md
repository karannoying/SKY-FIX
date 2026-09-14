# ADR-3 — Bootstrap particle filter over four parameters, and what it can and cannot recover

**Status:** accepted · **Date:** 2026-09-14 · **Affects:** FR-3.1, FR-3.2, FR-3.4, O3, T-V5
**Amends:** BLUEPRINT §10 (T-V5 row) — see *Consequences*

## Context

FR-3.2 asks for a recursive estimator over θ = (free lift, ascent Cd, burst-diameter scale,
parachute Cd) from replayed telemetry. The blueprint's ADR-3 row chose a bootstrap particle
filter over EKF, UKF and batch least squares. This record is the detail behind that row, written
against a working implementation and the measurements it produced.

Burst is a hard discontinuity in the dynamics rather than a smooth nonlinearity, and before it
happens the burst-diameter posterior is genuinely uninformative — no Kalman variant represents
either honestly, and batch least squares cannot answer mid-flight at all. A bootstrap filter needs
no Jacobians and carries whatever shape the posterior actually has, at O(N x forward-step) per
update. N is capped at 500 to hold that cost inside FR-3.3's budget.

## Decisions

### 1. Particles are advanced incrementally, not re-simulated

Each particle carries its own `(θ, BalloonState)` and is advanced by one observation interval with
`FlightSimulator.advanceTo`. Re-simulating every particle from launch at every sample would be
quadratic in log length: 500 particles over an 8,400-sample log is about four billion integration
steps. Incrementally it is one flight per particle. The step is truncated at the observation epoch
so the predicted state sits exactly on it — a smaller final step, and a smaller step is never less
stable (ADR-13).

A `BalloonState` carries phase, diameter and rate, which is everything the next leg needs, so
there is no separate integrator state to keep in step with the particle.

### 2. The wind scale is not estimated

Four parameters, exactly the ones FR-3.2 names. The wind scale is a property of the sounding, not
of the balloon; a single flight's horizontal track is a weak and heavily aliased observation of it;
and letting the filter absorb wind error into a balloon parameter is precisely the failure this
project exists to avoid. Wind uncertainty stays where ADR-7 put it — dispersed across the forward
ensemble — so the re-predicted footprint still carries it.

Instead its magnitude enters the likelihood: `GaussianMeasurementModel.withWindDrift` inflates the
horizontal sigma as `sqrt(sigma_gps^2 + (sigma_windscale * drift)^2)`. Scoring a horizontal
residual against an 8 m GPS sigma when the *prediction* rests on a single sounding would hand that
channel a likelihood hundreds of thousands of times sharper than the vertical ones.

### 3. The vertical-rate sigma is derived, not assumed

A flight computer reports position, not rate, so the rate in an `Observation` is a difference of
two noisy altitudes over a baseline T and is uncertain by `sigma_alt * sqrt(2) / T`. At 1 Hz with
a 10 m GPS that is about 7 m/s — comparable to the balloon's entire ascent rate.

The first implementation used a flat 2 m/s. **Measured consequence on `ds6-flight-01`:** at
26.8 km a single GPS error produced a rate that flattered one just-burst particle by enough nats
to take the entire weight; all particles were resampled onto it; and the filter spent the rest of
the flight in free fall while the telemetry climbed another four kilometres. A likelihood that
claims more precision than the measurement has does not merely add noise — it lets one sample
overrule the whole flight.

### 4. Burst diameter is redrawn from a *censored* prior until burst is observed

Burst diameter has no effect on the trajectory until the envelope reaches it. While the telemetry
has not shown a burst, no particle is ever selected *for* it: burst-diameter values are passengers
on particles chosen for their lift and drag. Ordinary resampling therefore arrives at burst — the
one instant the parameter is measurable — holding a single arbitrary value. **Measured:** 8% error
in burst scale, 1,721 m in burst altitude, against T-V5's 500 m.

Redrawing from the *plain* prior at each resample is worse, and instructively so. Burst is a
threshold crossing, so a threshold redrawn 130 times over an ascent is crossed as soon as any one
draw falls below the diameter already reached: burst time is then governed by the minimum of many
draws. **Measured:** every particle burst by 26 km against a true 30.8 km, and the predicted
altitude was fourteen kilometres below the telemetry by the real burst.

The error was in calling the pre-burst state uninformative. It is *censored*: an envelope that has
grown to diameter D without bursting is direct evidence that its burst diameter exceeds D. So the
correct conditional prior is the prior truncated below at D, sampled through the prior's own CDF.
The band then narrows from below as the balloon climbs, which is the actual information a rising
balloon carries about an envelope that has not yet failed.

Burst is signalled from the telemetry by `BurstDetector` (FR-3.4), not inferred from the particles.
The *first* particle to burst is by construction the one holding the smallest burst diameter in the
set, so "some particle has burst" fires far too early. This is the `switchPhase` arrow in the
BLUEPRINT §8 replay sequence, with the semantics the physics wants: it does not force any particle
into descent — a particle that disagrees about when to burst is a hypothesis the data may refute —
it only says the question has now been asked.

### 5. Jitter uses the full weighted covariance

Liu–West shrinkage jitter, with `a = (3d-1)/2d` at `d = 0.98` and kernel covariance `h^2 V`,
`h^2 = 1 - a^2`, realised through a hand-written Cholesky factor.

The covariance must be the full matrix, not per-dimension variances. Free lift and ascent drag are
very nearly degenerate — more lift climbs faster, more drag climbs slower, and over an ascent the
two cancel almost exactly — so the posterior is a long thin ridge lying at an angle to both axes. A
diagonal kernel is spherical, and a spherical proposal on a ridge lands almost entirely *across*
it, where the likelihood kills it; resampling then grinds the set onto a point wherever the random
walk happened to be.

**Measured, six DS-6 flights at N = 200, ascent-Cd error:**

| kernel | f01 | f02 | f03 |
|---|---|---|---|
| diagonal | 19.61% | 0.95% | 15.57% |
| full covariance | 4.76% | 1.32% | 6.10% |

## The identifiability limit, measured

Ascent Cd is only weakly identifiable, and this is a property of the observation set rather than of
the estimator. Scanning ascent Cd away from truth on `ds6-flight-01` and re-optimising free lift
and burst diameter at each step, the best achievable RMS difference against the true altitude
profile is:

| ascent Cd error | compensating free lift | compensating burst diameter | best whole-flight RMS |
|---|---|---|---|
| −10% | −12.05% | −1.07% | 10.98 m |
| −5% | −6.02% | −0.47% | 10.45 m |
| +5% | +6.14% | +0.48% | 10.94 m |
| +10% | +12.30% | +0.85% | 29.68 m |
| +15% | +18.53% | +1.20% | 49.95 m |

A 5% error in ascent Cd, absorbed by a 6% change in free lift, reproduces the entire flight to
**10.5 m RMS — the GPS noise itself**. Over the ascent alone the degeneracy is far tighter still:
a 20% Cd error compensated by free lift matches the ascent profile to **0.44 m**. And because
`DescentPhase` depends only on dry mass and parachute drag, neither free lift nor ascent Cd is
observable at all after burst: the information about them arrives entirely before the moment they
stop mattering.

**Measured recovery, six DS-6 flights at N = 500:**

| flight | ascent Cd error | burst altitude error |
|---|---|---|
| 01 | 15.07% | 1 m |
| 02 | 2.71% | 98 m |
| 03 | 0.63% | 0 m |
| 04 | 19.66% | 52 m |
| 05 | 15.62% | 2 m |
| 06 | 0.97% | 7 m |

Burst altitude — the physically decisive quantity, and the one a recovery team cares about — is
recovered essentially exactly, far inside T-V5's 500 m. Ascent Cd scatters across the width of the
ridge and does not improve with particle count, which is what an information limit looks like
rather than a tuning failure.

## Consequences

- T-V5's burst-altitude criterion (500 m) is met with two orders of magnitude to spare.
- **T-V5's "ascent Cd within 5%" criterion is not supported by the observation set.** It is met on
  roughly half the flights, and which half is decided by where the random walk along the ridge
  stopped. [PLACEHOLDER — the amended criterion has not been agreed. The candidate, to be settled
  before `v0.3-estimator` is tagged, is to score the identifiable quantities instead: burst
  altitude within 500 m, parachute Cd within 5%, and the posterior median reproducing the observed
  altitude profile within 3 sigma of the GPS noise — reporting the ascent-Cd error alongside, with
  the table above as the stated reason it is reported rather than gated.]
- The reported bands are narrower than the filter can honestly resolve in the ridge directions.
  `DEFAULT_ROUGHENING` floors them at 2% of the prior spread, which is a floor on impoverishment,
  not a calibrated credible interval. T-V6 will say how far off the calibration is.
- One filter instance belongs to one replay and is single-threaded by design; the parallelism in
  this project is in `EnsembleRunner`, where the re-prediction runs.
