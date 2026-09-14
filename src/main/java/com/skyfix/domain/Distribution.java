package com.skyfix.domain;

import com.skyfix.domain.error.ValidationException;

/**
 * A one-dimensional distribution a dispersed parameter is drawn from (FR-2.4).
 *
 * <p>Each kind exposes an inverse CDF, which is all Latin-hypercube sampling needs: the sampler
 * produces a stratified uniform in [0, 1) per dimension and maps it through
 * {@link #quantile(double)}.
 *
 * <p>The normal kind is truncated, because the parameters being dispersed are physical: a free
 * lift of zero or a negative drag coefficient is not a rare event, it is a nonsense one. Truncation
 * is applied by clamping the quantile range rather than by rejection, so a stratified draw keeps
 * exactly one sample per stratum — rejection would break the property T-U-LHS asserts.
 */
public sealed interface Distribution {

    /**
     * The value at a given cumulative probability.
     *
     * @param p a probability, strictly between 0 and 1
     * @return the parameter value at that quantile
     */
    double quantile(double p);

    /** @return a short description for run records and console output */
    String describe();

    /** @return the smallest value this distribution can produce */
    double minimum();

    /** @return the largest value this distribution can produce */
    double maximum();

    /**
     * A uniform distribution over a closed interval.
     *
     * @param low  the lower bound
     * @param high the upper bound, strictly greater than {@code low}
     */
    record Uniform(double low, double high) implements Distribution {

        /**
         * @throws IllegalArgumentException if the interval is empty or inverted
         */
        public Uniform {
            if (!(high > low)) {
                throw new IllegalArgumentException(
                        "uniform high (" + high + ") must exceed low (" + low + ")");
            }
        }

        @Override
        public double quantile(double p) {
            return low + p * (high - low);
        }

        @Override
        public String describe() {
            return String.format("uniform[%.4g, %.4g]", low, high);
        }

        @Override
        public double minimum() {
            return low;
        }

        @Override
        public double maximum() {
            return high;
        }
    }

    /**
     * A normal distribution truncated to a plausible physical range.
     *
     * @param mean              the mean
     * @param standardDeviation the standard deviation, strictly positive
     * @param low               the lower truncation bound
     * @param high              the upper truncation bound
     */
    record TruncatedNormal(double mean, double standardDeviation, double low, double high)
            implements Distribution {

        /**
         * @throws IllegalArgumentException if the standard deviation is not positive, or the
         *                                  truncation interval is empty
         */
        public TruncatedNormal {
            if (!(standardDeviation > 0.0)) {
                throw new IllegalArgumentException(
                        "standard deviation must be positive, was " + standardDeviation);
            }
            if (!(high > low)) {
                throw new IllegalArgumentException(
                        "truncation high (" + high + ") must exceed low (" + low + ")");
            }
        }

        /**
         * A normal truncated symmetrically at a number of standard deviations.
         *
         * @param mean              the mean
         * @param standardDeviation the standard deviation
         * @param sigmaLimit        how many standard deviations to truncate at
         * @return the distribution
         */
        public static TruncatedNormal symmetric(double mean, double standardDeviation,
                                                double sigmaLimit) {
            return new TruncatedNormal(mean, standardDeviation,
                    mean - sigmaLimit * standardDeviation,
                    mean + sigmaLimit * standardDeviation);
        }

        /**
         * A normal whose standard deviation is a fraction of the nominal value — the usual way a
         * catalogue tolerance is quoted ("free lift is known to about 15%").
         *
         * @param nominal          the nominal value
         * @param relativeSigma    the standard deviation as a fraction of {@code nominal}
         * @param sigmaLimit       how many standard deviations to truncate at
         * @return the distribution
         * @throws ValidationException if the nominal value is not positive, which would make a
         *                             relative spread meaningless
         */
        public static TruncatedNormal relative(double nominal, double relativeSigma,
                                               double sigmaLimit) throws ValidationException {
            if (!(nominal > 0.0)) {
                throw ValidationException.field("nominal", nominal,
                        "must be positive for a relative dispersion to be meaningful");
            }
            if (!(relativeSigma > 0.0)) {
                throw ValidationException.field("relative_sigma", relativeSigma,
                        "must be greater than 0");
            }
            return symmetric(nominal, nominal * relativeSigma, sigmaLimit);
        }

        @Override
        public double quantile(double p) {
            double value = mean + standardDeviation * Gaussian.inverseCdf(p);
            return Math.max(low, Math.min(high, value));
        }

        @Override
        public String describe() {
            return String.format("normal(%.4g, %.4g) truncated to [%.4g, %.4g]",
                    mean, standardDeviation, low, high);
        }

        @Override
        public double minimum() {
            return low;
        }

        @Override
        public double maximum() {
            return high;
        }
    }

    /**
     * A parameter held exactly at one value — the way to disable dispersion on one dimension
     * without special-casing it in the sampler.
     *
     * @param value the fixed value
     */
    record Fixed(double value) implements Distribution {

        @Override
        public double quantile(double p) {
            return value;
        }

        @Override
        public String describe() {
            return String.format("fixed %.4g", value);
        }

        @Override
        public double minimum() {
            return value;
        }

        @Override
        public double maximum() {
            return value;
        }
    }
}
