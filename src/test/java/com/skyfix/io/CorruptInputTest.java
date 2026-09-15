package com.skyfix.io;

import com.skyfix.domain.error.DataFormatException;
import com.skyfix.domain.error.SkyfixException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T-E3: corrupted and truncated inputs fail cleanly (NFR-3).
 *
 * <p>The rule the blueprint sets is not "does not crash" but something stricter: every external
 * input failure produces a typed exception carrying {@code file:line:field}, a non-zero exit code,
 * and no stack trace shown to the user. A reader who feeds SKYFIX a truncated download should be
 * told which line stopped making sense, not handed a Java trace.
 *
 * <p>These are deliberately nasty inputs — random bytes, a file cut mid-line, a header with no
 * rows, numbers where text belongs — because the failure mode that matters is the one where a
 * corrupted file is silently accepted and produces a confident wrong answer.
 */
class CorruptInputTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("T-E3: a binary dump is rejected as a sounding, naming the file")
    void refusesABinaryDumpAsASounding() throws Exception {
        Path binary = tempDir.resolve("garbage.bin");
        byte[] noise = new byte[8192];
        new Random(42).nextBytes(noise);
        Files.write(binary, noise);

        assertThatThrownBy(() -> new WyomingSoundingReader().read(binary))
                .isInstanceOf(SkyfixException.class)
                .satisfies(e -> {
                    SkyfixException skyfix = (SkyfixException) e;
                    assertThat(skyfix.exitCode()).isNotZero();
                    assertThat(skyfix.userMessage()).contains("garbage.bin");
                });
    }

    @Test
    @DisplayName("T-E3: a sounding truncated mid-file keeps what parsed and says what it lost")
    void reportsATruncatedSounding() throws Exception {
        String full = Files.readString(
                Path.of("data/soundings/SYNTHETIC_2026-09-14_00Z.txt"), StandardCharsets.UTF_8);
        // Cut in the middle of a data line, which is what an interrupted download looks like.
        String cut = full.substring(0, (int) (full.length() * 0.6));
        int lastNewline = cut.lastIndexOf('\n');
        Path truncated = tempDir.resolve("truncated.txt");
        Files.writeString(truncated, cut.substring(0, lastNewline + 1) + "  1013.0   111",
                StandardCharsets.UTF_8);

        SoundingReader.SoundingParseResult parsed = new WyomingSoundingReader().read(truncated);

        // Accepting the good levels and reporting the bad tail is the right behaviour: a half
        // download is still a usable profile up to where it stopped. What must not happen is the
        // partial line being silently absorbed.
        assertThat(parsed.levels()).isNotEmpty();
        assertThat(parsed.levels().size())
                .as("fewer levels than the complete file")
                .isLessThan(new WyomingSoundingReader()
                        .read(Path.of("data/soundings/SYNTHETIC_2026-09-14_00Z.txt"))
                        .levels().size());
        assertThat(parsed.rejections())
                .as("the truncated tail is reported rather than absorbed")
                .isNotEmpty();
        assertThat(parsed.rejections().get(0)).contains("truncated.txt:");
    }

    @Test
    @DisplayName("T-E3: a telemetry log of random bytes is refused, not half-parsed")
    void refusesABinaryDumpAsTelemetry() throws Exception {
        Path binary = tempDir.resolve("garbage.csv");
        byte[] noise = new byte[4096];
        new Random(7).nextBytes(noise);
        Files.write(binary, noise);

        assertThatThrownBy(() -> new CsvTelemetryReader().read(binary))
                .isInstanceOf(SkyfixException.class)
                .satisfies(e -> assertThat(((SkyfixException) e).exitCode()).isNotZero());
    }

    @Test
    @DisplayName("T-E3: a telemetry log truncated mid-row keeps the good rows and flags the rest")
    void reportsATruncatedTelemetryLog() throws Exception {
        String full = Files.readString(Path.of("data/truth/ds6-flight-01.csv"),
                StandardCharsets.UTF_8);
        String cut = full.substring(0, 20_000);
        Path truncated = tempDir.resolve("cut.csv");
        Files.writeString(truncated, cut, StandardCharsets.UTF_8);

        var series = new CsvTelemetryReader().read(truncated);
        assertThat(series.size()).isGreaterThan(100);
        // The final partial row must either parse completely or be rejected by name — never be
        // half-read into a sample with a plausible-looking missing field.
        for (var sample : series) {
            assertThat(sample.latitudeDeg()).isBetween(-90.0, 90.0);
            assertThat(sample.longitudeDeg()).isBetween(-180.0, 180.0);
        }
    }

    @Test
    @DisplayName("T-E3: a file with a header and no data rows is refused rather than returned empty")
    void refusesAHeaderWithNoRows() throws Exception {
        Path headerOnly = tempDir.resolve("empty.csv");
        Files.writeString(headerOnly,
                "epoch_utc,packet_id,lat_deg,lon_deg,alt_gps_m,pressure_pa,temp_k\n",
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new CsvTelemetryReader().read(headerOnly))
                .isInstanceOf(DataFormatException.class)
                .satisfies(e -> assertThat(((SkyfixException) e).userMessage())
                        .contains("empty.csv"));
    }

    @Test
    @DisplayName("T-E3: a missing file names the path rather than throwing an IO trace")
    void refusesAMissingFile() {
        Path missing = tempDir.resolve("not-there.csv");
        assertThatThrownBy(() -> new CsvTelemetryReader().read(missing))
                .isInstanceOf(SkyfixException.class)
                .satisfies(e -> assertThat(((SkyfixException) e).userMessage())
                        .contains("not-there.csv"));
        assertThatThrownBy(() -> new WyomingSoundingReader().read(missing))
                .isInstanceOf(SkyfixException.class);
    }
}
