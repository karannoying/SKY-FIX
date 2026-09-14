package com.skyfix.app;

import com.skyfix.core.atmos.AtmosphereModel;
import com.skyfix.core.atmos.ConstantWindField;
import com.skyfix.core.atmos.SoundingWindField;
import com.skyfix.core.atmos.Ussa1976Atmosphere;
import com.skyfix.core.atmos.WindField;
import com.skyfix.core.flight.FlightSimulator;
import com.skyfix.core.flight.IntegratorFactory;
import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.SimSettings;
import com.skyfix.domain.StateHistory;
import com.skyfix.domain.error.SkyfixException;
import com.skyfix.io.CsvWriter;
import com.skyfix.io.GeoJsonWriter;
import com.skyfix.persistence.Database;
import com.skyfix.persistence.Mission;
import com.skyfix.persistence.RunDao;
import com.skyfix.persistence.RunRecord;
import com.skyfix.persistence.RunStateDao;
import com.skyfix.persistence.StoredBalloonConfig;
import com.skyfix.persistence.StoredSounding;
import com.skyfix.persistence.SoundingDao;

import java.nio.file.Path;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Runs a pre-flight prediction and persists everything needed to reproduce it (UC-1, FR-2.3).
 *
 * <p>At the MVP cut-line this is a single deterministic flight — the ensemble and its confidence
 * ellipse arrive with FR-2.4. The run record it writes already carries the seed, git SHA, config
 * hash, integrator, step size, member count and host cores, so adding members later changes the
 * numbers in the row rather than its shape.
 */
public final class PredictionService {

    private static final Logger LOG = Logger.getLogger(PredictionService.class.getName());

    private final RunDao runs;
    private final RunStateDao states;
    private final SoundingDao soundings;
    private final AtmosphereModel atmosphere = new Ussa1976Atmosphere();

    /**
     * @param database the database to write into
     */
    public PredictionService(Database database) {
        this.runs = new RunDao(database);
        this.states = new RunStateDao(database);
        this.soundings = new SoundingDao(database);
    }

    /**
     * Predicts a landing point and stores the run.
     *
     * @param mission    the mission
     * @param config     the stored balloon configuration
     * @param soundingId the sounding to use, or {@code null} for a windless prediction
     * @param settings   integration settings
     * @param context    provenance for this execution
     * @param outputDir  where CSV and GeoJSON exports are written
     * @return the run record and the trajectory
     * @throws SkyfixException if the flight cannot be integrated or the run cannot be stored
     */
    public PredictionResult predict(Mission mission, StoredBalloonConfig config, Long soundingId,
                                    SimSettings settings, RunContext context, Path outputDir)
            throws SkyfixException {

        // Everything that can be rejected is rejected before a row is written. An unknown
        // integrator would otherwise be caught by the CHECK constraint on run.integrator and
        // surface as a database error (exit 6) rather than as a ValidationException naming the
        // field (exit 2) -- and would leave a RUNNING row behind for a run that never started.
        IntegratorFactory.create(settings.integrator());
        WindField wind = resolveWindField(soundingId);
        BalloonConfig balloon = config.config();

        RunRecord run = runs.save(new RunRecord(null, mission.id(), config.id(), soundingId, null,
                "PREFLIGHT", settings.integrator(), settings.stepSeconds(), 1,
                context.seed(), context.gitSha(), balloon.configHash(), context.hostCores(),
                context.startedUtc(), null, RunRecord.RUNNING));

        try {
            StateHistory history = new FlightSimulator(atmosphere, wind)
                    .run(balloon, settings, mission.launch());

            states.saveHistory(run.id(), 0, history);

            Path runDir = outputDir.resolve("run-" + run.id());
            CsvWriter.writeTrajectory(runDir.resolve("trajectory.csv"), history);
            CsvWriter.writeSummary(runDir.resolve("summary.csv"), run.id(), history);
            GeoJsonWriter.writeFlight(runDir.resolve("flight.geojson"), history);

            runs.finish(run.id(), context.elapsedMs(), RunRecord.OK);

            if (history.windExtrapolatedCount() > 0) {
                LOG.warning(() -> history.windExtrapolatedCount() + " of " + history.stepCount()
                        + " steps queried the wind above the sounding's top level; the wind there "
                        + "is held, not known");
            }
            return new PredictionResult(run.withId(run.id()), history, runDir,
                    wind.name());
        } catch (SkyfixException e) {
            runs.finish(run.id(), context.elapsedMs(), RunRecord.FAILED);
            LOG.log(Level.SEVERE, "prediction run " + run.id() + " failed", e);
            throw e;
        }
    }

    private WindField resolveWindField(Long soundingId) throws SkyfixException {
        if (soundingId == null) {
            LOG.info("no sounding supplied; predicting in still air");
            return ConstantWindField.calm();
        }
        Optional<StoredSounding> stored = soundings.findById(soundingId);
        if (stored.isEmpty()) {
            throw com.skyfix.domain.error.ValidationException.field("sounding_id", soundingId,
                    "does not match any imported sounding");
        }
        return SoundingWindField.of(stored.get().stationId(), stored.get().levels());
    }

    /**
     * The outcome of a prediction.
     *
     * @param run          the stored run record
     * @param history      the trajectory
     * @param outputDir    the directory the exports were written to
     * @param windFieldName which wind field was used, for the console summary
     */
    public record PredictionResult(RunRecord run, StateHistory history, Path outputDir,
                                   String windFieldName) {
    }
}
