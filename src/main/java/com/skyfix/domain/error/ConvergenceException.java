package com.skyfix.domain.error;

/**
 * A solver or filter failed to converge — an integration that never reaches the ground inside
 * {@code tEnd}, or a particle filter whose weights all collapse to zero. Exit code 5.
 */
public class ConvergenceException extends SkyfixException {

    private static final long serialVersionUID = 1L;

    public ConvergenceException(String message) {
        super(message);
    }

    @Override
    public int exitCode() {
        return 5;
    }
}
