package com.skyfix.io;

import com.skyfix.domain.BalloonState;
import com.skyfix.domain.StateHistory;
import com.skyfix.domain.error.PersistenceException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Writes a flight as GeoJSON, so a recovery team can drop the track and the landing point
 * straight into a map (BLUEPRINT §6, FR-2.4).
 *
 * <p>GeoJSON orders coordinates longitude-first, which is the opposite of how the rest of SKYFIX
 * writes a position; that inversion happens here and nowhere else. Altitude is carried as the
 * optional third coordinate.
 */
public final class GeoJsonWriter {

    private GeoJsonWriter() {
    }

    /**
     * Writes a trajectory as a {@code FeatureCollection}: the track as a LineString, plus point
     * features for burst and landing.
     *
     * @param path    the file to write; parent directories are created
     * @param history the trajectory
     * @throws PersistenceException if the file cannot be written
     */
    public static void writeFlight(Path path, StateHistory history) throws PersistenceException {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                out.write("{\"type\":\"FeatureCollection\",\"features\":[");

                out.write("{\"type\":\"Feature\",\"properties\":{\"name\":\"track\"},"
                        + "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[");
                boolean first = true;
                for (BalloonState s : history) {
                    if (!first) {
                        out.write(",");
                    }
                    out.write(coordinate(s.longitudeDeg(), s.latitudeDeg(), s.altitudeM()));
                    first = false;
                }
                out.write("]}}");

                if (history.burst().isPresent()) {
                    BalloonState b = history.burst().get();
                    out.write(",");
                    out.write(point("burst", b.longitudeDeg(), b.latitudeDeg(), b.altitudeM()));
                }
                if (history.landing().isPresent()) {
                    BalloonState l = history.landing().get();
                    out.write(",");
                    out.write(point("landing", l.longitudeDeg(), l.latitudeDeg(), l.altitudeM()));
                }
                out.write("]}");
                out.newLine();
            }
        } catch (IOException e) {
            throw new PersistenceException("cannot write GeoJSON to " + path, e);
        }
    }

    private static String point(String name, double lon, double lat, double alt) {
        return "{\"type\":\"Feature\",\"properties\":{\"name\":\"" + name + "\"},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":"
                + coordinate(lon, lat, alt) + "}}";
    }

    private static String coordinate(double lon, double lat, double alt) {
        // GeoJSON is [longitude, latitude, altitude] — the inversion lives here alone.
        return String.format(Locale.ROOT, "[%.7f,%.7f,%.1f]", lon, lat, alt);
    }
}
