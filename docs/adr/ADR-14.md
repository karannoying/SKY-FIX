# ADR-14 — Latin hypercube over plain Monte Carlo, and a closed-form ellipse

**Status:** accepted · **Date:** 2026-09-14 · **Affects:** FR-2.4, O2, T-U-LHS, T-U-ELLIPSE, T-V7

## Context

FR-2.4 turns a nominal configuration into a footprint by dispersing five parameters and
aggregating where the members land. Two choices sit inside that: how to draw the design, and how
to turn a scatter of landing points into an ellipse.

## Decision 1 — Latin hypercube sampling

Each dimension is cut into *N* equal-probability strata, exactly one sample is taken from each,
and the strata are permuted independently per dimension.

**Why not plain Monte Carlo.** With *N* independent uniform draws, roughly a third of the strata
go unvisited while others are hit repeatedly — `DispersionSamplerTest` measures more than a fifth
empty at *N* = 1,000 — so the footprint wobbles from seed to seed at a given member count. LHS
guarantees marginal coverage by construction, so a smaller ensemble gives a stable ellipse.

**The property is checkable exactly, not statistically.** T-U-LHS asserts that every stratum in
every dimension holds exactly one sample. A companion test shows plain Monte Carlo fails the same
assertion, so the test genuinely distinguishes the two rather than being one any sampler passes.

verify: BLUEPRINT §10 claims LHS "reaches a stable 95% ellipse in ~400 members where plain MC
needs ~1,500". That number is still unmeasured. Now that both paths exist, it should be measured
by running each at increasing member counts and finding where the semi-axes settle — and the
report should quote the measurement, not the estimate.

**Truncation by clamping, not rejection.** Physical parameters have hard bounds — the schema
confines drag coefficients to [0.1, 2.0] — but rejecting an out-of-range draw and redrawing would
break the one-sample-per-stratum property. The quantile is clamped to the truncation bounds
instead.

## Decision 2 — A closed-form 2×2 eigen-decomposition

Landing points are projected onto a local east-north tangent plane about their mean, and the 2×2
covariance is decomposed in closed form: eigenvalues from the trace and determinant, the major
axis from the eigenvector of the larger one.

**Why not an iterative solver.** A symmetric 2×2 eigenproblem has an exact algebraic solution, so
there is no convergence criterion to tune and nothing to iterate. That also settles the "is this a
job for a library?" question raised by CLAUDE.md rule 1: the whole decomposition is a dozen lines
of arithmetic.

**Why a tangent plane.** A covariance computed in degrees is anisotropic anywhere but the equator —
a degree of longitude at 23°N is 92% of a degree of latitude — so the ellipse would be skewed by
the coordinate system rather than by the physics.

**The confidence scaling is derived, not pasted.** For a bivariate normal the squared Mahalanobis
distance is chi-square with two degrees of freedom, whose CDF is `1 − exp(−s²/2)`; inverting gives
`s = sqrt(−2 ln(1 − p))`. So the 50% and 95% factors come out of the closed form rather than from
a table, and `GaussianTest` checks the inversion round-trips exactly.

## Measured

`T-U-ELLIPSE` builds a cloud with known axes and orientation and requires the fitter to recover
them: from a cloud built at 19,582 × 6,119 m on a 35° bearing, the fit returned
**19,468 × 6,088 m at 35.27°** — 0.58%, 0.51% and 0.27° of error, against a 2% tolerance.

Axes being right does not prove the scaling is, so containment is measured separately: the 50%,
90% and 95% ellipses contained **49.9%, 90.0% and 95.1%** of the cloud. That is the check T-V7
will make against real ensembles.

## Consequences

- A degenerate scatter — every member landing on one point, or exactly on a line — would give a
  zero axis, which the schema's `semi_major_m > 0` CHECK forbids. The axes are floored at one
  centimetre, far below any real dispersion, so the record stays storable.
- The fitted ellipse assumes the landing scatter is approximately Gaussian. Under a strongly
  sheared wind it may not be, and a very high aspect ratio is the signal: a constant-wind ensemble
  produces an aspect ratio in the thousands, because with unidirectional wind every bit of the
  dispersion lands along one axis. T-V7's containment check is what will show whether the Gaussian
  assumption holds on real profiles; if it does not, the honest fix is a convex hull or a kernel
  density contour, reported as such.
