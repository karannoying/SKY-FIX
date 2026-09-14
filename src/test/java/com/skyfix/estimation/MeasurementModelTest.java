package com.skyfix.estimation;

import com.skyfix.domain.BalloonState;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.Geodesy;
import com.skyfix.domain.Phase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * T-U-LIKELIHOOD — FR-3.1's two acceptance criteria, stated as they are written: the likelihood is
 * maximised at the injected truth, and decreases monotonically as error is induced in each channel.
 *
 * <p>Both are checkable exactly. A measurement model that got either wrong would still produce a
 * filter that runs and reports confident-looking posteriors pointing at the wrong parameters, which
 * is the worst kind of failure this project could have.
 */
class MeasurementModelTest {

    private static final Instant EPOCH = Instant.parse("2026-09-14T05:00:00Z");
    private static final double LAT = 23.2599;
    private static final double LON = 77.4126;
    private static final double ALT = 12_000.0;
    private static final double RATE = 5.0;

    private final GaussianMeasurementModel model = GaussianMeasurementModel.standard();

    private static BalloonState state(double lat, double lon, double alt, double rate) {
        return new BalloonState(600.0, new GeoPoint(lat, lon, alt), rate, 3.0, Phase.ASCENT, false);
    }

    private static Observation truth() {
        return new Observation(EPOCH, LAT, LON, ALT, RATE, false);
    }

    @Test
    @DisplayName("T-U-LIKELIHOOD: the likelihood is maximised at the injected truth")
    void maximisedAtTruth() {
        double atTruth = model.logLikelihood(state(LAT, LON, ALT, RATE), truth());

        // Perturb in every direction, in every channel, and nothing may beat the truth.
        for (double dAlt : new double[]{-50, -5, -0.5, 0.5, 5, 50}) {
            assertThat(model.logLikelihood(state(LAT, LON, ALT + dAlt, RATE), truth()))
                    .as("altitude offset %.1f m", dAlt).isLessThan(atTruth);
        }
        for (double dRate : new double[]{-3, -0.5, -0.05, 0.05, 0.5, 3}) {
            assertThat(model.logLikelihood(state(LAT, LON, ALT, RATE + dRate), truth()))
                    .as("rate offset %.2f m/s", dRate).isLessThan(atTruth);
        }
        for (double bearing : new double[]{0, 45, 90, 135, 180, 225, 270, 315}) {
            GeoPoint moved = Geodesy.destination(new GeoPoint(LAT, LON, ALT), bearing, 25.0);
            assertThat(model.logLikelihood(
                    state(moved.latitudeDeg(), moved.longitudeDeg(), ALT, RATE), truth()))
                    .as("25 m on bearing %.0f", bearing).isLessThan(atTruth);
        }
    }

    @Test
    @DisplayName("T-U-LIKELIHOOD: the likelihood decreases monotonically with error, per channel")
    void monotoneInEachChannel() {
        // Altitude
        double previous = Double.POSITIVE_INFINITY;
        for (double error = 0; error <= 200; error += 5) {
            double value = model.logLikelihood(state(LAT, LON, ALT + error, RATE), truth());
            assertThat(value).as("altitude error %.0f m", error).isLessThan(previous);
            previous = value;
        }

        // Vertical rate
        previous = Double.POSITIVE_INFINITY;
        for (double error = 0; error <= 20; error += 0.5) {
            double value = model.logLikelihood(state(LAT, LON, ALT, RATE + error), truth());
            assertThat(value).as("rate error %.1f m/s", error).isLessThan(previous);
            previous = value;
        }

        // Horizontal
        previous = Double.POSITIVE_INFINITY;
        for (double distance = 0; distance <= 500; distance += 10) {
            GeoPoint moved = Geodesy.destination(new GeoPoint(LAT, LON, ALT), 60.0,
                    Math.max(distance, 1e-9));
            double value = model.logLikelihood(
                    state(moved.latitudeDeg(), moved.longitudeDeg(), ALT, RATE), truth());
            assertThat(value).as("horizontal error %.0f m", distance).isLessThan(previous);
            previous = value;
        }
    }

    @Test
    @DisplayName("FR-3.1: the error scale is the configured sigma, not an arbitrary one")
    void errorIsScaledByTheConfiguredSigma() {
        // A one-sigma error must cost exactly 0.5 nats relative to a perfect match, in every
        // channel. That is what makes the sigmas meaningful rather than decorative knobs.
        double atTruth = model.logLikelihood(state(LAT, LON, ALT, RATE), truth());

        assertThat(atTruth - model.logLikelihood(
                state(LAT, LON, ALT + model.altitudeSigmaM(), RATE), truth()))
                .as("one sigma of altitude error").isEqualTo(0.5, within(1e-9));
        assertThat(atTruth - model.logLikelihood(
                state(LAT, LON, ALT, RATE + model.verticalRateSigmaMs()), truth()))
                .as("one sigma of rate error").isEqualTo(0.5, within(1e-9));

        // Two sigma costs four times as much as one, as a Gaussian must.
        assertThat(atTruth - model.logLikelihood(
                state(LAT, LON, ALT + 2 * model.altitudeSigmaM(), RATE), truth()))
                .isEqualTo(2.0, within(1e-9));
    }

    @Test
    @DisplayName("FR-3.1: a barometric altitude is trusted less than a GPS one")
    void pressureAltitudeIsWeightedMoreLoosely() {
        // A pressure altitude is what the STANDARD atmosphere puts at that pressure, and the day's
        // profile is not the standard one, so the same residual should cost less when the fix was
        // lost -- otherwise the filter chases an offset belonging to the atmosphere, not the
        // balloon.
        Observation gps = new Observation(EPOCH, LAT, LON, ALT, RATE, false);
        Observation barometric = new Observation(EPOCH, LAT, LON, ALT, RATE, true);
        BalloonState off = state(LAT, LON, ALT + 100.0, RATE);

        double gpsPenalty = model.logLikelihood(state(LAT, LON, ALT, RATE), gps)
                - model.logLikelihood(off, gps);
        double barometricPenalty = model.logLikelihood(state(LAT, LON, ALT, RATE), barometric)
                - model.logLikelihood(off, barometric);

        assertThat(barometricPenalty).isLessThan(gpsPenalty);
        // Specifically, by the square of the sigma factor.
        double factor = GaussianMeasurementModel.PRESSURE_ALTITUDE_SIGMA_FACTOR;
        assertThat(gpsPenalty / barometricPenalty).isEqualTo(factor * factor, within(1e-9));
    }

    @Test
    @DisplayName("FR-3.1: absent channels are skipped, not treated as zero")
    void absentChannelsAreSkipped() {
        // An observation with no vertical rate must not be scored as though the rate were zero,
        // which would punish every ascending particle.
        Observation noRate = new Observation(EPOCH, LAT, LON, ALT, Double.NaN, false);
        double ascending = model.logLikelihood(state(LAT, LON, ALT, 5.0), noRate);
        double descending = model.logLikelihood(state(LAT, LON, ALT, -30.0), noRate);
        assertThat(ascending).as("with no rate observed, the rate must not discriminate")
                .isEqualTo(descending, within(1e-12));
        assertThat(noRate.hasVerticalRate()).isFalse();

        Observation noAltitude = new Observation(EPOCH, LAT, LON, Double.NaN, RATE, false);
        assertThat(noAltitude.hasAltitude()).isFalse();
        assertThat(model.logLikelihood(state(LAT, LON, 1_000, RATE), noAltitude))
                .isEqualTo(model.logLikelihood(state(LAT, LON, 40_000, RATE), noAltitude),
                        within(1e-12));
    }

    @Test
    @DisplayName("FR-3.1: log-space keeps a badly wrong particle from underflowing to zero")
    void logSpaceSurvivesHugeErrors() {
        // A particle 5 km off has a likelihood around exp(-125000) -- identically zero in a
        // double. In log space it is an ordinary negative number, and that is the difference
        // between a filter that works and one whose weights all vanish at the first update.
        double veryWrong = model.logLikelihood(state(LAT, LON, ALT + 5_000, RATE), truth());
        assertThat(veryWrong).isFinite().isLessThan(-100_000);
        assertThat(Math.exp(veryWrong)).as("which is why the model never leaves log space")
                .isEqualTo(0.0);
    }

    @Test
    @DisplayName("The model describes itself and rejects nonsensical sigmas")
    void metadataAndValidation() {
        assertThat(model.name()).isEqualTo("GAUSSIAN");
        assertThat(model.altitudeSigmaM()).isEqualTo(10.0);
        assertThat(model.horizontalSigmaM()).isEqualTo(8.0);
        assertThatThrownBy(() -> new GaussianMeasurementModel(0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GaussianMeasurementModel(1, -1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
