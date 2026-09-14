package com.skyfix.domain.error;

/**
 * An input file could not be parsed. Carries {@code file:line} so the user can go straight to the
 * offending line (FR-1.1, NFR-3). Exit code 3.
 */
public class DataFormatException extends SkyfixException {

    private static final long serialVersionUID = 1L;

    public DataFormatException(String file, int line, String message) {
        super(message, file, line, null);
    }

    public DataFormatException(String file, int line, String message, Throwable cause) {
        super(message, file, line, cause);
    }

    @Override
    public int exitCode() {
        return 3;
    }
}
