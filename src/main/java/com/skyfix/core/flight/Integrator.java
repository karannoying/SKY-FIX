package com.skyfix.core.flight;

import com.skyfix.domain.error.SkyfixException;

/**
 * Strategy for advancing an ODE system by one step (BLUEPRINT §8).
 *
 * <p>Two implementations ship. {@link Rk4Integrator} is the default; {@link Rkf45Integrator} is a
 * genuinely different scheme, and running the same flight through both is the T-V3 cross-check
 * that gives NFR-2 a leg it would otherwise lack.
 *
 * <p>Implementations are stateless with respect to the trajectory and therefore safe to share
 * across ensemble threads.
 */
public interface Integrator {

    /**
     * Advances the state by one step of size {@code h}.
     *
     * @param t          current independent variable, seconds
     * @param y          current state vector; left unmodified
     * @param h          step size, seconds; must be positive
     * @param derivative the system's right-hand side
     * @return a new array holding the state at {@code t + h}
     * @throws SkyfixException if the derivative cannot be evaluated anywhere in the step
     */
    double[] step(double t, double[] y, double h, Derivatives derivative) throws SkyfixException;

    /** @return the integrator's name, as it appears in run records and the config hash */
    String name();

    /** @return the order of the method, used only for reporting */
    int order();

    /**
     * Magnitude of the local truncation error estimated on the most recent step, or
     * {@link Double#NaN} for a method that does not produce one.
     *
     * <p>Not thread-safe by design: an integrator instance belongs to one member's integration.
     *
     * @return the estimate, in the units of the state vector's largest component
     */
    default double lastErrorEstimate() {
        return Double.NaN;
    }
}
