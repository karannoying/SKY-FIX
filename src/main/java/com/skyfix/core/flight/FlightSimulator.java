package com.skyfix.core.flight;

import com.skyfix.core.atmos.AtmosphereModel;
import com.skyfix.core.atmos.AtmosphericState;
import com.skyfix.core.atmos.WindField;
import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.BalloonState;
import com.skyfix.domain.FlightParameters;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.Phase;
import com.skyfix.domain.SimSettings;
import com.skyfix.domain.StateHistory;
import com.skyfix.domain.error.ConvergenceException;
import com.skyfix.domain.error.SkyfixException;
import com.skyfix.domain.error.ValidationException;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Integrates one flight from launch to landing (FR-2.3).
 *
 * <p>The loop is deliberately small: ask the current {@link FlightPhase} for its derivative, take
 * one integrator step, ask the phase whether it is finished, and swap phases if so. All the
 * physics that differs between ascent and descent lives in the phase objects, so burst is a phase
 * swap here rather than a branch.
 *
 * <p>Stateless between calls and safe to share across ensemble threads: every call allocates its
 * own integrator and phase objects (ADR-5).
 */
public final class FlightSimulator {

    private static final Logger LOG = Logger.getLogger(FlightSimulator.class.getName());

    /** Below this speed at ground contact the landing is treated as reached, m/s. */
    private static final double GROUND_EPSILON_M = 1e-6;

    private final AtmosphereModel atmosphere;
    private final WindField windField;

    /**
     * @param atmosphere the atmosphere model every phase queries
     * @param windField  the wind field every phase is advected by
     */
    public FlightSimulator(AtmosphereModel atmosphere, WindField windField) {
        this.atmosphere = atmosphere;
        this.windField = windField;
    }

    /**
     * Runs a complete flight from the launch point, at rest, under the nominal parameters.
     *
     * @param config   the balloon configuration
     * @param settings integration settings
     * @param launch   launch position; its altitude is the release altitude
     * @return the flight history, including the landing point
     * @throws SkyfixException if the configuration is unflyable, a model is queried out of range,
     *                         or the flight does not land inside the time ceiling
     */
    public StateHistory run(BalloonConfig config, SimSettings settings, GeoPoint launch)
            throws SkyfixException {
        return run(config, FlightParameters.nominal(config), settings, launch);
    }

    /**
     * Runs a complete flight from the launch point, at rest, under given parameters.
     *
     * <p>This overload is what the ensemble calls: each member supplies its own dispersed
     * {@link FlightParameters} against one shared, immutable configuration.
     *
     * @param config     the balloon configuration
     * @param parameters the dispersed or estimated parameters for this member
     * @param settings   integration settings
     * @param launch     launch position; its altitude is the release altitude
     * @return the flight history, including the landing point
     * @throws SkyfixException if the configuration is unflyable, a model is queried out of range,
     *                         or the flight does not land inside the time ceiling
     */
    public StateHistory run(BalloonConfig config, FlightParameters parameters,
                            SimSettings settings, GeoPoint launch) throws SkyfixException {
        AtmosphericState launchAir = atmosphere.stateAt(launch.altitudeM());
        double gasMass = gasMassFor(config, parameters, launchAir);

        double[] y = {launch.latitudeDeg(), launch.longitudeDeg(), launch.altitudeM(), 0.0};
        FlightPhase phase = new AscentPhase(atmosphere, windField, config, parameters, gasMass);
        return integrate(config, parameters, settings, phase, 0.0, y, gasMass);
    }

    /**
     * Re-predicts the rest of a flight from a measured state (FR-3.3).
     *
     * <p>Same integration, different starting point: the state comes from telemetry rather than
     * from a launch assumption, and the phase is taken from the state rather than assumed to be
     * ascent. This is the overload the in-flight re-prediction calls once the filter has a
     * posterior.
     *
     * @param config     the balloon configuration
     * @param parameters the estimated parameters
     * @param settings   integration settings
     * @param from       the measured state to continue from
     * @return the flight history from {@code from} to landing
     * @throws SkyfixException if a model is queried out of range, or the flight does not land
     *                         inside the time ceiling
     */
    public StateHistory runFrom(BalloonConfig config, FlightParameters parameters,
                                SimSettings settings, BalloonState from) throws SkyfixException {
        AtmosphericState air = atmosphere.stateAt(from.altitudeM());
        double[] y = {from.latitudeDeg(), from.longitudeDeg(), from.altitudeM(),
                from.verticalRateMs()};

        FlightPhase phase;
        double gasMass;
        if (from.phase() == Phase.DESCENT || from.phase() == Phase.BURST) {
            phase = new DescentPhase(atmosphere, windField, config, parameters);
            gasMass = 0.0;
        } else {
            // Recover the gas mass implied by the measured diameter, so the re-prediction
            // continues the flight that was actually observed rather than a fresh nominal one.
            double volume = BalloonConfig.volumeOfDiameter(from.diameterM());
            gasMass = volume * config.gas().densityAt(air.pressurePa(), air.temperatureK());
            phase = new AscentPhase(atmosphere, windField, config, parameters, gasMass);
        }
        return integrate(config, parameters, settings, phase, from.timeSeconds(), y, gasMass);
    }

    /**
     * The lifting gas mass implied by a member's free lift at the launch atmosphere.
     *
     * @param config     the balloon configuration
     * @param parameters the member's parameters; its free lift overrides the configuration's
     * @param launchAir  the atmospheric state at the launch altitude
     * @return the gas mass in kg
     * @throws ValidationException if the gas is not buoyant at the launch site
     */
    public static double gasMassFor(BalloonConfig config, FlightParameters parameters,
                                    AtmosphericState launchAir) throws ValidationException {
        BalloonConfig effective = config.toBuilder()
                .freeLiftKg(parameters.freeLiftKg())
                .build();
        return effective.gasMassKg(launchAir.densityKgM3(), launchAir.pressurePa(),
                launchAir.temperatureK());
    }

    private StateHistory integrate(BalloonConfig config, FlightParameters parameters,
                                   SimSettings settings, FlightPhase initialPhase,
                                   double startTime, double[] initialState, double gasMass)
            throws SkyfixException {

        Integrator integrator = IntegratorFactory.create(settings.integrator());
        StateHistory.Builder history = StateHistory.builder();

        FlightPhase phase = initialPhase;
        double[] y = initialState.clone();
        double t = startTime;
        double h = settings.stepSeconds();
        int step = 0;

        double stabilityLimit = integrator.realAxisStabilityLimit();
        AtmosphericState air = atmosphere.stateAt(y[FlightPhase.ALT]);
        history.add(state(t, y, phase.diameterM(air), phase.phase(), false));

        while (t < settings.endSeconds()) {
            requireStableStep(phase, air, y[FlightPhase.VZ], h, stabilityLimit,
                    integrator.name(), y[FlightPhase.ALT]);
            phase.clearWindExtrapolated();
            double[] next = integrator.step(t, y, h, phase.derivative());
            boolean extrapolated = phase.windExtrapolated();
            t += h;
            step++;
            history.countStep(extrapolated);

            AtmosphericState nextAir = atmosphere.stateAt(next[FlightPhase.ALT]);

            // Ground contact: interpolate to the crossing rather than overshooting by up to one
            // step, which at 1 s and 5 m/s would be a 5 m error in the reported landing altitude.
            if (phase.phase() == Phase.DESCENT
                    && next[FlightPhase.ALT] <= settings.groundElevationM()) {
                BalloonState landed = interpolateToGround(y, next, t - h, h,
                        settings.groundElevationM(), phase.diameterM(nextAir), extrapolated);
                history.add(landed).landing(landed);
                LOG.log(Level.INFO, () -> String.format(
                        "landed after %.0f s at %s", landed.timeSeconds(), landed.position()));
                return history.build();
            }

            if (phase.isComplete(nextAir, next[FlightPhase.ALT], settings.groundElevationM())) {
                if (phase.phase() == Phase.ASCENT) {
                    BalloonState burst = state(t, next, phase.diameterM(nextAir), Phase.BURST,
                            extrapolated);
                    history.add(burst).burst(burst);
                    LOG.log(Level.INFO, () -> String.format(
                            "burst at %.0f m after %.0f s", burst.altitudeM(), burst.timeSeconds()));
                    phase = new DescentPhase(atmosphere, windField, config, parameters);
                    y = next;
                    air = nextAir;
                    continue;
                }
            }

            y = next;
            air = nextAir;
            if (step % settings.stateSampleStride() == 0) {
                history.add(state(t, y, phase.diameterM(nextAir), phase.phase(), extrapolated));
            }
        }

        throw new ConvergenceException(
                "flight did not reach the ground within t_end_s (" + settings.endSeconds()
                        + " s); last altitude " + y[FlightPhase.ALT] + " m")
                .with("t_end_s", settings.endSeconds())
                .with("last_altitude_m", y[FlightPhase.ALT]);
    }

    /**
     * Refuses a step the integrator cannot take stably.
     *
     * <p>Quadratic drag linearises to a real negative eigenvalue whose magnitude is the phase's
     * damping rate, and that rate rises as the vehicle descends into denser air. Past the
     * integrator's stability boundary an explicit method oscillates instead of converging, and the
     * landing point it produces is wrong without looking wrong — measured at 39 s of extra flight
     * time, and around 470 m of landing error in a 12 m/s wind, for RK4 at a 1 s step (ADR-13).
     *
     * <p>So the condition is checked rather than assumed, and the message names the largest step
     * that would have worked.
     */
    private static void requireStableStep(FlightPhase phase, AtmosphericState air,
                                          double verticalRateMs, double h, double stabilityLimit,
                                          String integratorName, double altitudeM)
            throws SkyfixException {
        double dampingRate = phase.dampingRatePerSecond(air, verticalRateMs);
        if (dampingRate * h <= stabilityLimit) {
            return;
        }
        double maxStableStep = stabilityLimit / dampingRate;
        throw new ConvergenceException(String.format(
                "step_s (%.4g s) exceeds the stability limit of %s during %s at %.0f m; "
                        + "drag damping is %.3g /s there, so the step must be at most %.3g s",
                h, integratorName, phase.phase(), altitudeM, dampingRate, maxStableStep))
                .with("step_s", h)
                .with("max_stable_step_s", maxStableStep)
                .with("integrator", integratorName)
                .with("altitude_m", altitudeM);
    }

    private static BalloonState interpolateToGround(double[] before, double[] after,
                                                    double timeBefore, double h,
                                                    double groundElevationM, double diameterM,
                                                    boolean windExtrapolated) {
        double altBefore = before[FlightPhase.ALT];
        double altAfter = after[FlightPhase.ALT];
        double span = altBefore - altAfter;
        double fraction = span > GROUND_EPSILON_M ? (altBefore - groundElevationM) / span : 1.0;
        fraction = Math.max(0.0, Math.min(1.0, fraction));

        double lat = before[FlightPhase.LAT] + fraction * (after[FlightPhase.LAT] - before[FlightPhase.LAT]);
        double lon = before[FlightPhase.LON] + fraction * (after[FlightPhase.LON] - before[FlightPhase.LON]);
        double vz = before[FlightPhase.VZ] + fraction * (after[FlightPhase.VZ] - before[FlightPhase.VZ]);

        return new BalloonState(timeBefore + fraction * h,
                new GeoPoint(lat, com.skyfix.domain.Geodesy.normaliseLongitude(lon),
                        groundElevationM),
                vz, diameterM, Phase.LANDED, windExtrapolated);
    }

    private static BalloonState state(double t, double[] y, double diameterM, Phase phase,
                                      boolean windExtrapolated) {
        return new BalloonState(t,
                new GeoPoint(y[FlightPhase.LAT],
                        com.skyfix.domain.Geodesy.normaliseLongitude(y[FlightPhase.LON]),
                        y[FlightPhase.ALT]),
                y[FlightPhase.VZ], diameterM, phase, windExtrapolated);
    }
}
