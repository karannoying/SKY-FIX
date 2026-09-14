package com.skyfix.estimation;

import com.skyfix.domain.BalloonState;
import com.skyfix.domain.Geodesy;

/**
 * Independent Gaussian errors on altitude, vertical rate and horizontal position (FR-3.1).
 *
 * <p>The three channels are treated as independent, which is an approximation — a GPS receiver's
 * horizontal and vertical errors are correlated through the same satellite geometry — but a
 * defensible one at this level of modelling, and one the report should state rather than leave
 * implicit.
 *
 * <p><strong>A barometric altitude is weighted differently from a GPS one.</strong> When the fix
 * drops, {@code CsvTelemetryReader} substitutes a pressure altitude and flags it. That number is
 * smooth but biased: it is what the <em>standard</em> atmosphere puts at that pressure, and the
 * day's profile is not the standard one. Trusting it as heavily as a GPS fix would let the filter
 * chase an offset that is a property of the atmosphere rather than of the balloon, so its sigma is
 * inflated by {@link #PRESSURE_ALTITUDE_SIGMA_FACTOR}.
 */
public final class GaussianMeasurementModel implements MeasurementModel {

    /**
     * How much wider the altitude sigma is for a barometric altitude than for a GPS one.
     *
     * <p>verify: this is an engineering judgement, not a measurement. It should be replaced with a
     * figure fitted from the DS-6 flights, where both the true altitude and the pressure-derived
     * one are known for every sample, so the actual spread of the difference can be computed.
     */
    public static final double PRESSURE_ALTITUDE_SIGMA_FACTOR = 4.0;

    private static final double LOG_SQRT_TWO_PI = 0.5 * Math.log(2.0 * Math.PI);

    private final double altitudeSigmaM;
    private final double verticalRateSigmaMs;
    private final double horizontalSigmaM;

    /**
     * Creates a model with per-channel standard deviations.
     *
     * @param altitudeSigmaM      GPS altitude error, metres
     * @param verticalRateSigmaMs vertical-rate error, m/s — larger than the altitude error implies,
     *                            because the rate is differenced from noisy altitudes
     * @param horizontalSigmaM    horizontal position error, metres
     */
    public GaussianMeasurementModel(double altitudeSigmaM, double verticalRateSigmaMs,
                                    double horizontalSigmaM) {
        if (altitudeSigmaM <= 0 || verticalRateSigmaMs <= 0 || horizontalSigmaM <= 0) {
            throw new IllegalArgumentException("every sigma must be positive");
        }
        this.altitudeSigmaM = altitudeSigmaM;
        this.verticalRateSigmaMs = verticalRateSigmaMs;
        this.horizontalSigmaM = horizontalSigmaM;
    }

    /**
     * The model matched to the noise {@code NoiseSpec.standard()} applies, which is what the DS-6
     * flights carry.
     *
     * @return the standard model
     */
    public static GaussianMeasurementModel standard() {
        return new GaussianMeasurementModel(10.0, 2.0, 8.0);
    }

    @Override
    public double logLikelihood(BalloonState predicted, Observation observed) {
        double total = 0.0;

        if (observed.hasAltitude()) {
            double sigma = observed.altitudeFromPressure()
                    ? altitudeSigmaM * PRESSURE_ALTITUDE_SIGMA_FACTOR
                    : altitudeSigmaM;
            total += logGaussian(predicted.altitudeM() - observed.altitudeM(), sigma);
        }
        if (observed.hasVerticalRate()) {
            total += logGaussian(predicted.verticalRateMs() - observed.verticalRateMs(),
                    verticalRateSigmaMs);
        }

        // Horizontal error as one great-circle distance rather than two angular residuals: a
        // degree of longitude is not a degree of latitude, and treating them as interchangeable
        // would make the model latitude-dependent for no reason.
        double horizontalErrorM = Geodesy.haversineMetres(
                predicted.latitudeDeg(), predicted.longitudeDeg(),
                observed.latitudeDeg(), observed.longitudeDeg());
        total += logGaussian(horizontalErrorM, horizontalSigmaM);

        return total;
    }

    /** Log of a zero-mean Gaussian density at {@code error}. */
    private static double logGaussian(double error, double sigma) {
        double z = error / sigma;
        return -0.5 * z * z - Math.log(sigma) - LOG_SQRT_TWO_PI;
    }

    @Override
    public String name() {
        return "GAUSSIAN";
    }

    /** @return the GPS altitude standard deviation, metres */
    public double altitudeSigmaM() {
        return altitudeSigmaM;
    }

    /** @return the vertical-rate standard deviation, m/s */
    public double verticalRateSigmaMs() {
        return verticalRateSigmaMs;
    }

    /** @return the horizontal standard deviation, metres */
    public double horizontalSigmaM() {
        return horizontalSigmaM;
    }
}
