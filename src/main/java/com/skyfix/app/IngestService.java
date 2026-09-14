package com.skyfix.app;

import com.skyfix.core.atmos.SoundingLevel;
import com.skyfix.domain.BalloonConfig;
import com.skyfix.domain.error.SkyfixException;
import com.skyfix.io.ConfigLoader;
import com.skyfix.io.SoundingReader;
import com.skyfix.io.WyomingSoundingReader;
import com.skyfix.persistence.BalloonConfigDao;
import com.skyfix.persistence.Database;
import com.skyfix.persistence.Mission;
import com.skyfix.persistence.MissionDao;
import com.skyfix.persistence.SoundingDao;
import com.skyfix.persistence.StoredBalloonConfig;
import com.skyfix.persistence.StoredSounding;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Imports missions, balloon configurations and soundings (FR-1.1, FR-1.3).
 *
 * <p>Every import is idempotent: a mission is keyed by name, a configuration by its hash
 * (ADR-10), a sounding by (station, epoch, source). Re-running {@code ingest} on unchanged inputs
 * therefore changes nothing rather than accumulating near-duplicates.
 */
public final class IngestService {

    private static final Logger LOG = Logger.getLogger(IngestService.class.getName());

    private final MissionDao missions;
    private final BalloonConfigDao configs;
    private final SoundingDao soundings;
    private final ConfigLoader loader = new ConfigLoader();

    /**
     * @param database the database to write into
     */
    public IngestService(Database database) {
        this.missions = new MissionDao(database);
        this.configs = new BalloonConfigDao(database);
        this.soundings = new SoundingDao(database);
    }

    /**
     * Imports a mission and its balloon configuration.
     *
     * @param missionPath the {@code mission.json} file
     * @param balloonPath the {@code balloon.json} file
     * @return what was stored, and whether each part was new
     * @throws SkyfixException if either file is unparseable or breaks a rule
     */
    public IngestResult ingestMission(Path missionPath, Path balloonPath) throws SkyfixException {
        ConfigLoader.MissionSpec spec = loader.loadMission(missionPath);
        BalloonConfig balloon = loader.loadBalloon(balloonPath);

        Optional<Mission> existing = missions.findByName(spec.name());
        boolean missionIsNew = existing.isEmpty();
        Mission mission = existing.orElseGet(() -> {
            try {
                return missions.save(new Mission(null, spec.name(), spec.launch(),
                        spec.groundElevationM(), spec.launchEpochUtc()));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        boolean configIsNew = configs.findByHash(balloon.configHash()).isEmpty();
        StoredBalloonConfig stored = configs.findOrSave(mission.id(), balloon);

        LOG.info(() -> "ingested mission \"" + spec.name() + "\" (new=" + missionIsNew
                + ") with config " + balloon.configHash().substring(0, 12)
                + " (new=" + configIsNew + ")");
        return new IngestResult(mission, stored, missionIsNew, configIsNew);
    }

    /**
     * Imports a sounding file (FR-1.1).
     *
     * @param path     the sounding file
     * @param epochUtc the observation time to file it under, ISO-8601 UTC
     * @return the stored sounding and the parse report
     * @throws SkyfixException if the file cannot be parsed or stored
     */
    public SoundingIngestResult ingestSounding(Path path, String epochUtc) throws SkyfixException {
        SoundingReader reader = new WyomingSoundingReader();
        SoundingReader.SoundingParseResult parsed = reader.read(path);

        Optional<StoredSounding> existing = soundings.findByNaturalKey(
                parsed.stationId(), epochUtc, reader.sourceName());
        if (existing.isPresent()) {
            LOG.info(() -> "sounding " + parsed.stationId() + " @ " + epochUtc
                    + " is already imported; skipping");
            return new SoundingIngestResult(existing.get(), parsed.rejections(), false);
        }

        StoredSounding stored = soundings.save(new StoredSounding(null, parsed.stationId(),
                epochUtc, reader.sourceName(), parsed.fileSha256(), parsed.levels()));
        LOG.info(() -> "ingested " + stored.levelCount() + " levels from " + path.getFileName()
                + " (" + parsed.rejectedCount() + " lines rejected)");
        return new SoundingIngestResult(stored, parsed.rejections(), true);
    }

    /**
     * Loads the levels of a stored sounding, for building a wind field.
     *
     * @param soundingId the sounding
     * @return its levels, or an empty list if it is not stored
     * @throws SkyfixException if the query fails
     */
    public List<SoundingLevel> levelsOf(long soundingId) throws SkyfixException {
        return soundings.findById(soundingId).map(StoredSounding::levels).orElse(List.of());
    }

    /**
     * What an {@code ingest} of a mission stored.
     *
     * @param mission      the mission row
     * @param config       the balloon configuration row
     * @param missionIsNew whether the mission was created by this call
     * @param configIsNew  whether the configuration was created by this call
     */
    public record IngestResult(Mission mission, StoredBalloonConfig config, boolean missionIsNew,
                               boolean configIsNew) {
    }

    /**
     * What an {@code ingest} of a sounding stored.
     *
     * @param sounding   the sounding row, with its levels
     * @param rejections one message per rejected line, each naming {@code file:line:reason}
     * @param isNew      whether this call imported it, as opposed to finding it already present
     */
    public record SoundingIngestResult(StoredSounding sounding, List<String> rejections,
                                       boolean isNew) {
    }
}
