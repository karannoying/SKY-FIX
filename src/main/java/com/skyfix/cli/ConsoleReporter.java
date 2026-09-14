package com.skyfix.cli;

import com.skyfix.domain.StateHistory;
import com.skyfix.domain.error.SkyfixException;
import com.skyfix.persistence.ValidationResult;

import java.io.PrintStream;
import java.util.List;
import java.util.Locale;

/**
 * Renders tables, summaries and errors for the terminal (NFR-3, NFR-6).
 *
 * <p>Errors are rendered as one actionable line naming the offending field or {@code file:line}.
 * A user never sees a stack trace; an unexpected failure points at a log file instead.
 */
public final class ConsoleReporter {

    private final PrintStream out;
    private final PrintStream err;

    /**
     * @param out where normal output goes
     * @param err where errors go
     */
    public ConsoleReporter(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    /**
     * Prints the model-versus-reference table that report §10 and §11 quote (FR-4.4).
     *
     * @param results  the validation results
     * @param skipped  cases that are specified but not yet implemented
     * @return how many results breached their tolerance
     */
    public long printValidationTable(List<ValidationResult> results, List<String> skipped) {
        out.println();
        out.println("SKYFIX reference-case validation");
        out.println("=".repeat(96));
        out.printf(Locale.ROOT, "%-6s %-34s %14s %14s %11s %7s%n",
                "CASE", "QUANTITY", "MODEL", "REFERENCE", "ERROR", "RESULT");
        out.println("-".repeat(96));

        String currentCase = null;
        long failures = 0;
        for (ValidationResult r : results) {
            if (!r.caseId().equals(currentCase)) {
                if (currentCase != null) {
                    out.println();
                }
                currentCase = r.caseId();
            }
            boolean relative = ValidationResult.RELATIVE.equals(r.toleranceKind());
            out.printf(Locale.ROOT, "%-6s %-34s %14.6g %14.6g %10s %7s%n",
                    r.caseId(),
                    truncate(r.quantity(), 34),
                    r.modelValue(),
                    r.referenceValue(),
                    relative ? String.format(Locale.ROOT, "%.4f%%", r.error() * 100)
                             : String.format(Locale.ROOT, "%.4g", r.error()),
                    r.passed() ? "PASS" : "FAIL");
            if (!r.passed()) {
                failures++;
            }
        }

        out.println("-".repeat(96));
        summariseByCase(results);

        if (!skipped.isEmpty()) {
            out.println();
            out.println("Not yet implemented (reported, not silently passed):");
            for (String s : skipped) {
                out.println("  - " + s);
            }
        }

        out.println();
        out.printf(Locale.ROOT, "%d checks, %d passed, %d failed%n",
                results.size(), results.size() - failures, failures);
        return failures;
    }

    private void summariseByCase(List<ValidationResult> results) {
        for (String caseId : results.stream().map(ValidationResult::caseId).distinct().toList()) {
            List<ValidationResult> inCase = results.stream()
                    .filter(r -> r.caseId().equals(caseId)).toList();
            double worst = inCase.stream().mapToDouble(ValidationResult::error).max().orElse(0);
            long failed = inCase.stream().filter(r -> !r.passed()).count();
            boolean relative = ValidationResult.RELATIVE.equals(inCase.get(0).toleranceKind());
            out.printf(Locale.ROOT, "  %-6s %2d checks, tolerance %-10s worst %-12s %s%n",
                    caseId, inCase.size(),
                    relative ? String.format(Locale.ROOT, "%.3f%%",
                            inCase.get(0).tolerance() * 100)
                            : String.format(Locale.ROOT, "%.4g %s", inCase.get(0).tolerance(),
                                    inCase.get(0).unit()),
                    relative ? String.format(Locale.ROOT, "%.5f%%", worst * 100)
                            : String.format(Locale.ROOT, "%.4g", worst),
                    failed == 0 ? "PASS" : failed + " FAILED");
        }
    }

    /**
     * Prints the outcome of a prediction.
     *
     * @param runId     the run this belongs to
     * @param history   the trajectory
     * @param windField which wind field was used
     * @param outputDir where the exports went
     */
    public void printPrediction(long runId, StateHistory history, String windField,
                                java.nio.file.Path outputDir) {
        out.println();
        out.printf(Locale.ROOT, "Run %d - pre-flight prediction (wind: %s)%n", runId, windField);
        out.println("-".repeat(64));
        history.burst().ifPresent(b -> out.printf(Locale.ROOT,
                "  burst        %,10.0f m   at T+%.0f s%n", b.altitudeM(), b.timeSeconds()));
        history.landing().ifPresent(l -> {
            out.printf(Locale.ROOT, "  landing      %10.6f, %.6f%n",
                    l.latitudeDeg(), l.longitudeDeg());
            out.printf(Locale.ROOT, "  flight time  %,10.0f s   (%.0f min)%n",
                    l.timeSeconds(), l.timeSeconds() / 60.0);
            out.printf(Locale.ROOT, "  touchdown    %10.2f m/s%n", l.verticalRateMs());
        });
        out.printf(Locale.ROOT, "  apogee       %,10.0f m%n", history.apogeeM());
        out.printf(Locale.ROOT, "  steps        %,10d%n", history.stepCount());
        if (history.windExtrapolatedCount() > 0) {
            out.printf(Locale.ROOT,
                    "  WARNING      %,10d steps used wind held above the sounding's top level%n",
                    history.windExtrapolatedCount());
        }
        out.println();
        out.println("  exports      " + outputDir);
    }

    /**
     * Prints a line of normal output.
     *
     * @param message the message
     */
    public void info(String message) {
        out.println(message);
    }

    /**
     * Renders a SKYFIX failure as one actionable line (NFR-3).
     *
     * @param e the failure
     */
    public void error(SkyfixException e) {
        err.println("error: " + e.userMessage());
    }

    /**
     * Renders an unexpected failure, pointing at the log rather than printing a trace.
     *
     * @param e       the failure
     * @param logPath where the full trace was written
     */
    public void unexpectedError(Throwable e, String logPath) {
        err.println("error: unexpected failure (" + e.getClass().getSimpleName() + "): "
                + e.getMessage());
        err.println("       full details in " + logPath);
    }

    private static String truncate(String text, int width) {
        return text.length() <= width ? text : text.substring(0, width - 1) + "...";
    }
}
