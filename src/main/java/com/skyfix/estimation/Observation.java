package com.skyfix.estimation;

import com.skyfix.domain.TelemetrySample;

import java.time.Instant;

/**
 * A telemetry sample prepared for weighting: position, altitude and a derived vertical rate
 * (FR-3.1).
 *
 * <p>A raw {@link TelemetrySample} carries no vertical rate — a flight computer reports where it
 * is, not how fast it is climbing — so the rate is differenced from neighbouring samples before
 * the measurement model sees it. Doing that once, here, rather than inside the likelihood means
 * five hundred particles do not each recompute the same number.
 *
 * @param epochUtc           when the sample was taken
 * @param latitudeDeg        observed latitude
 * @param longitudeDeg       observed longitude
 * @param altitudeM          observed altitude above MSL, metres
 * @param verticalRateMs     vertical rate derived from neighbouring samples, m/s, or NaN if it
 *                           could not be derived
 * @param altitudeFromPressure whether the altitude came from the barometer rather than GPS, which
 *                           the model weights differently
 */
public record Observation(Instant epochUtc, double latitudeDeg, double longitudeDeg,
                          double altitudeM, double verticalRateMs, boolean altitudeFromPressure) {

    /** @return whether this observation carries a usable vertical rate */
    public boolean hasVerticalRate() {
        return !Double.isNaN(verticalRateMs);
    }

    /** @return whether this observation carries a usable altitude */
    public boolean hasAltitude() {
        return !Double.isNaN(altitudeM);
    }
}
