package com.skyfix.io;

import com.skyfix.core.atmos.AtmosphereModel;
import com.skyfix.core.atmos.AtmosphericState;
import com.skyfix.domain.Ensemble;
import com.skyfix.domain.GeoPoint;
import com.skyfix.domain.Geodesy;
import com.skyfix.domain.LandingEllipse;
import com.skyfix.domain.StateHistory;
import com.skyfix.domain.error.PersistenceException;
import com.skyfix.domain.error.SkyfixException;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.style.lines.SeriesLines;
import org.knowm.xchart.style.markers.SeriesMarkers;

import java.awt.BasicStroke;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Exports the run plots as PNG (FR-4.2, PL-1, PL-2, PL-6).
 *
 * <p>The deliverable is a CLI with exported charts rather than a GUI (ADR-8), so these files are
 * what a reader actually sees: they go into the report and they are what a recovery lead looks at.
 *
 * <p>Filenames are deterministic — {@code pl1-altitude.png}, {@code pl2-footprint.png},
 * {@code pl6-atmosphere-residual.png} — so a report can reference them and a test can assert they
 * exist and are non-empty.
 *
 * <p>Charts are rendered headless. {@link #configureHeadless()} is called before any AWT class
 * loads, because a JVM that has already decided it has a display will try to open one.
 */
public final class PlotExporter {

    /** Width of an exported chart, pixels. */
    public static final int WIDTH = 1000;
    /** Height of an exported chart, pixels. */
    public static final int HEIGHT = 620;
    /** Side of a square chart, used where the axes must share a scale. */
    public static final int SQUARE = 820;

    private PlotExporter() {
    }

    /**
     * Puts AWT into headless mode.
     *
     * <p>Rendering a chart touches AWT, which on a machine with no display would otherwise fail
     * with a {@code HeadlessException} at image creation. Called before any chart is built.
     */
    public static void configureHeadless() {
        System.setProperty("java.awt.headless", "true");
    }

    /**
     * Writes the plots for a pre-flight ensemble run: PL-1 and PL-2.
     *
     * @param runDir   the run's output directory
     * @param nominal  the nominal flight's trajectory
     * @param members  the retained members' trajectories, keyed by member index
     * @param ensemble the ensemble, for the landing scatter
     * @param ellipses the fitted confidence ellipses
     * @param launch   the launch position, marked on the footprint
     * @return the files written, in a deterministic order
     * @throws PersistenceException if a chart cannot be written
     */
    public static List<Path> exportEnsemblePlots(Path runDir, StateHistory nominal,
                                                 Map<Integer, StateHistory> members,
                                                 Ensemble ensemble,
                                                 List<LandingEllipse> ellipses,
                                                 GeoPoint launch) throws PersistenceException {
        configureHeadless();
        List<Path> written = new ArrayList<>();
        written.add(write(runDir.resolve("pl1-altitude.png"), altitudeChart(nominal, members)));
        written.add(write(runDir.resolve("pl2-footprint.png"),
                footprintChart(ensemble, ellipses, launch)));
        return written;
    }

    /**
     * Writes PL-6, the atmosphere model's residual against the DS-3 reference table.
     *
     * <p>T-V1 reports one worst-case number; this shows the shape of the error with altitude,
     * which is what tells a reader whether the model is uniformly good or merely good on average.
     *
     * @param runDir     the output directory
     * @param atmosphere the model to check
     * @param reference  the reference rows: altitude m, temperature K, pressure Pa, density kg/m^3
     * @param tolerance  the T-V1 tolerance, as a fraction, drawn as a threshold band
     * @return the file written
     * @throws SkyfixException if the model refuses an altitude, or the chart cannot be written
     */
    public static Path exportAtmosphereResidual(Path runDir, AtmosphereModel atmosphere,
                                                List<double[]> reference, double tolerance)
            throws SkyfixException {
        configureHeadless();
        return write(runDir.resolve("pl6-atmosphere-residual.png"),
                residualChart(atmosphere, reference, tolerance));
    }

    /**
     * PL-1 — altitude against time, with the retained members behind the nominal flight.
     *
     * <p>The members are drawn individually rather than collapsed into a min/max band. With a
     * retained sample this small, the individual traces <em>are</em> the spread, and they show
     * where it comes from: the fan opens on the ascent as the members diverge in burst altitude,
     * then the descents run roughly parallel.
     */
    private static XYChart altitudeChart(StateHistory nominal, Map<Integer, StateHistory> members) {
        XYChart chart = new XYChartBuilder()
                .width(WIDTH).height(HEIGHT)
                .title("PL-1  Altitude against time")
                .xAxisTitle("time since launch (min)")
                .yAxisTitle("altitude (km)")
                .build();
        ChartStyle.apply(chart.getStyler());
        chart.getStyler().setYAxisMin(0.0);
        chart.getStyler().setXAxisMin(0.0);

        boolean firstMember = true;
        for (Map.Entry<Integer, StateHistory> entry : members.entrySet()) {
            StateHistory history = entry.getValue();
            if (history.states().size() < 2) {
                continue;
            }
            XYSeries series = chart.addSeries("member " + entry.getKey(),
                    minutes(history), kilometres(history));
            series.setLineColor(ChartStyle.MUTED);
            series.setLineWidth(ChartStyle.HAIRLINE);
            series.setLineStyle(SeriesLines.SOLID);
            series.setMarker(SeriesMarkers.NONE);
            // Only the first member earns a legend entry; twenty identical rows would be noise.
            series.setShowInLegend(firstMember);
            if (firstMember) {
                series.setLabel("ensemble members (" + members.size() + ")");
                firstMember = false;
            }
        }

        XYSeries nominalSeries = chart.addSeries("nominal flight",
                minutes(nominal), kilometres(nominal));
        nominalSeries.setLineColor(ChartStyle.SERIES_1);
        nominalSeries.setLineWidth(ChartStyle.PRIMARY_LINE);
        nominalSeries.setLineStyle(SeriesLines.SOLID);
        nominalSeries.setMarker(SeriesMarkers.NONE);

        nominal.burst().ifPresent(burst -> {
            XYSeries marker = chart.addSeries("burst",
                    new double[]{burst.timeSeconds() / 60.0},
                    new double[]{burst.altitudeM() / 1000.0});
            marker.setXYSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Scatter);
            marker.setMarkerColor(ChartStyle.SERIES_2);
            marker.setMarker(SeriesMarkers.CIRCLE);
        });
        return chart;
    }

    /**
     * PL-2 — the landing footprint: every member's landing point, the fitted ellipses, the launch
     * site and the ellipse centre.
     *
     * <p><strong>The axes share a scale</strong>, to within the asymmetry of the plot margins —
     * a couple of percent on a square canvas, since the left margin carries tick labels and the
     * right does not. An ellipse drawn on axes with genuinely different
     * metres-per-pixel is not the ellipse that was fitted — it would show a shape the data does not
     * have. So both axes are given the same span in kilometres, even when that makes a strongly
     * along-wind footprint render as a sliver. That sliver is the honest answer: under a wind that
     * barely changes direction with altitude, almost all of the uncertainty is in how long the
     * balloon stays up, and that lands along one axis. The title carries the aspect ratio so the
     * shape is stated as a number too, not only implied by the picture.
     *
     * <p><strong>The frame is the footprint, not the launch site.</strong> A long flight lands a
     * couple of hundred kilometres downwind, and including the launch point in an equal-scale
     * frame makes the span the drift distance rather than the footprint — the ellipses then
     * collapse to an unreadable smear in a mostly empty panel. Launch is a different question,
     * answered by the distance and bearing in the subtitle and by {@code footprint.geojson} on a
     * real map. The marker is drawn only when the launch site genuinely falls inside the frame.
     */
    private static XYChart footprintChart(Ensemble ensemble, List<LandingEllipse> ellipses,
                                          GeoPoint launch) {
        GeoPoint centre = ellipses.isEmpty() ? launch : ellipses.get(0).centre();
        double metresPerDegreeLat = Geodesy.metresPerDegreeLatitude();
        double metresPerDegreeLon = Geodesy.metresPerDegreeLongitude(centre.latitudeDeg());

        double driftKm = Geodesy.haversineMetres(launch, centre) / 1000.0;
        double bearing = Geodesy.initialBearingDeg(launch, centre);

        // The title stays short enough to fit the canvas -- an 820 px panel clips a long one at
        // both ends, which is worse than saying less. The dimensions go in the legend, where
        // there is room and where they sit beside the mark they describe.
        String title = String.format("PL-2  Landing footprint, %.0f km downwind on %.0f deg",
                driftKm, bearing);

        XYChart chart = new XYChartBuilder()
                .width(SQUARE).height(SQUARE)
                .title(title)
                .xAxisTitle("east of footprint centre (km)")
                .yAxisTitle("north of footprint centre (km)")
                .build();
        ChartStyle.apply(chart.getStyler());

        // Offsets are measured from the footprint centre, so the frame can close around the
        // ellipses however far downwind they are.
        List<double[]> scatter = new ArrayList<>();
        for (Ensemble.Member member : ensemble.members()) {
            if (member.landing().isEmpty()) {
                continue;
            }
            GeoPoint p = member.landing().get();
            scatter.add(new double[]{
                    Geodesy.normaliseLongitude(p.longitudeDeg() - centre.longitudeDeg())
                            * metresPerDegreeLon / 1000.0,
                    (p.latitudeDeg() - centre.latitudeDeg()) * metresPerDegreeLat / 1000.0});
        }

        XYSeries landings = chart.addSeries(
                "member landings (" + scatter.size() + ")",
                column(scatter, 0), column(scatter, 1));
        landings.setXYSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Scatter);
        landings.setMarkerColor(ChartStyle.MUTED);
        landings.setMarker(SeriesMarkers.CIRCLE);

        // Nested confidence levels are ordered, so they take two steps of one hue rather than two
        // unrelated categorical colours: the wider ellipse is the lighter step.
        List<LandingEllipse> ordered = new ArrayList<>(ellipses);
        ordered.sort((a, b) -> Double.compare(b.confidence(), a.confidence()));
        for (int i = 0; i < ordered.size(); i++) {
            LandingEllipse ellipse = ordered.get(i);
            double[][] outline = ellipseOutline(ellipse, centre, metresPerDegreeLat,
                    metresPerDegreeLon);
            XYSeries series = chart.addSeries(
                    String.format("%.0f%%  %.1f x %.2f km  (%.0f km2)",
                            ellipse.confidence() * 100,
                            ellipse.semiMajorM() * 2 / 1000.0,
                            ellipse.semiMinorM() * 2 / 1000.0,
                            ellipse.areaKm2()),
                    outline[0], outline[1]);
            series.setLineColor(i == 0 ? ChartStyle.BLUE_LIGHT : ChartStyle.SERIES_1);
            series.setLineWidth(ChartStyle.PRIMARY_LINE);
            series.setLineStyle(SeriesLines.SOLID);
            series.setMarker(SeriesMarkers.NONE);
        }

        // The ellipse centre is the one landmark always worth marking: it is the single best
        // guess, and the point a chase plan starts from.
        XYSeries centreSeries = chart.addSeries("footprint centre",
                new double[]{0.0}, new double[]{0.0});
        centreSeries.setXYSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Scatter);
        centreSeries.setMarkerColor(ChartStyle.SERIES_2);
        centreSeries.setMarker(SeriesMarkers.DIAMOND);

        // XChart exposes a max-label count on X but only a pixel spacing hint on Y.
        chart.getStyler().setXAxisMaxLabelCount(9);
        chart.getStyler().setYAxisTickMarkSpacingHint(70);

        double[] frame = equalScaleFrame(scatter, ordered, centre, metresPerDegreeLat,
                metresPerDegreeLon);
        chart.getStyler().setXAxisMin(frame[0]);
        chart.getStyler().setXAxisMax(frame[1]);
        chart.getStyler().setYAxisMin(frame[2]);
        chart.getStyler().setYAxisMax(frame[3]);

        // Only draw launch if it is genuinely in frame; otherwise the marker would be clipped to
        // an edge and read as a position it is not.
        double launchEast = Geodesy.normaliseLongitude(
                launch.longitudeDeg() - centre.longitudeDeg()) * metresPerDegreeLon / 1000.0;
        double launchNorth = (launch.latitudeDeg() - centre.latitudeDeg())
                * metresPerDegreeLat / 1000.0;
        if (launchEast >= frame[0] && launchEast <= frame[1]
                && launchNorth >= frame[2] && launchNorth <= frame[3]) {
            XYSeries launchSeries = chart.addSeries("launch", new double[]{launchEast},
                    new double[]{launchNorth});
            launchSeries.setXYSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Scatter);
            launchSeries.setMarkerColor(ChartStyle.SERIES_3);
            launchSeries.setMarker(SeriesMarkers.SQUARE);
        }
        return chart;
    }

    /**
     * PL-6 — relative error of the atmosphere model against the DS-3 table, with altitude.
     *
     * <p>Three series on one axis, because all three are the same quantity in the same unit. A
     * second y-scale would invent a relationship between them that the data does not have.
     */
    private static XYChart residualChart(AtmosphereModel atmosphere, List<double[]> reference,
                                         double tolerance) throws SkyfixException {
        int n = reference.size();
        double[] altitudeKm = new double[n];
        double[] temperature = new double[n];
        double[] pressure = new double[n];
        double[] density = new double[n];

        for (int i = 0; i < n; i++) {
            double[] row = reference.get(i);
            AtmosphericState state = atmosphere.stateAt(row[0]);
            altitudeKm[i] = row[0] / 1000.0;
            temperature[i] = 100.0 * (state.temperatureK() - row[1]) / row[1];
            pressure[i] = 100.0 * (state.pressurePa() - row[2]) / row[2];
            density[i] = 100.0 * (state.densityKgM3() - row[3]) / row[3];
        }

        XYChart chart = new XYChartBuilder()
                .width(WIDTH).height(HEIGHT)
                .title("PL-6  " + atmosphere.name() + " residual against the reference table")
                .xAxisTitle("geometric altitude (km)")
                .yAxisTitle("relative error (%)")
                .build();
        ChartStyle.apply(chart.getStyler());

        // The tolerance is chrome rather than a series, so it wears muted ink rather than a
        // categorical hue -- a threshold is not a fourth measurement.
        double tolerancePercent = tolerance * 100.0;
        double[] edges = {altitudeKm[0], altitudeKm[n - 1]};
        XYSeries upper = chart.addSeries(
                String.format("T-V1 tolerance  %.2f%%", tolerancePercent),
                edges, new double[]{tolerancePercent, tolerancePercent});
        upper.setLineColor(ChartStyle.MUTED);
        upper.setLineWidth(ChartStyle.HAIRLINE);
        upper.setLineStyle(SeriesLines.SOLID);
        upper.setMarker(SeriesMarkers.NONE);
        XYSeries lower = chart.addSeries("tolerance-lower", edges,
                new double[]{-tolerancePercent, -tolerancePercent});
        lower.setLineColor(ChartStyle.MUTED);
        lower.setLineWidth(ChartStyle.HAIRLINE);
        lower.setLineStyle(SeriesLines.SOLID);
        lower.setMarker(SeriesMarkers.NONE);
        lower.setShowInLegend(false);

        addResidual(chart, "temperature", altitudeKm, temperature, ChartStyle.SERIES_1);
        addResidual(chart, "pressure", altitudeKm, pressure, ChartStyle.SERIES_2);
        addResidual(chart, "density", altitudeKm, density, ChartStyle.SERIES_3);

        // Keep the tolerance lines in frame even when the residual is far inside them, so the
        // chart shows how much headroom there is rather than magnifying noise to fill the panel.
        chart.getStyler().setYAxisMin(-tolerancePercent * 1.25);
        chart.getStyler().setYAxisMax(tolerancePercent * 1.25);
        return chart;
    }

    private static void addResidual(XYChart chart, String name, double[] x, double[] y,
                                    java.awt.Color colour) {
        XYSeries series = chart.addSeries(name, x, y);
        series.setLineColor(colour);
        series.setMarkerColor(colour);
        series.setLineWidth(ChartStyle.PRIMARY_LINE);
        series.setLineStyle(SeriesLines.SOLID);
        series.setMarker(SeriesMarkers.CIRCLE);
    }

    /** Traces an ellipse in kilometres east and north of the given origin. */
    private static double[][] ellipseOutline(LandingEllipse ellipse, GeoPoint origin,
                                             double metresPerDegreeLat,
                                             double metresPerDegreeLon) {
        int vertices = 180;
        double[] east = new double[vertices + 1];
        double[] north = new double[vertices + 1];
        double azimuth = Math.toRadians(ellipse.azimuthDeg());

        double centreEast = Geodesy.normaliseLongitude(
                ellipse.centre().longitudeDeg() - origin.longitudeDeg()) * metresPerDegreeLon;
        double centreNorth = (ellipse.centre().latitudeDeg() - origin.latitudeDeg())
                * metresPerDegreeLat;

        for (int i = 0; i <= vertices; i++) {
            double t = 2.0 * Math.PI * i / vertices;
            double alongMajor = ellipse.semiMajorM() * Math.cos(t);
            double alongMinor = ellipse.semiMinorM() * Math.sin(t);
            east[i] = (centreEast + alongMajor * Math.sin(azimuth)
                    + alongMinor * Math.cos(azimuth)) / 1000.0;
            north[i] = (centreNorth + alongMajor * Math.cos(azimuth)
                    - alongMinor * Math.sin(azimuth)) / 1000.0;
        }
        return new double[][]{east, north};
    }

    /**
     * The plot frame, with both axes given the same span so a kilometre reads the same distance in
     * either direction on a square canvas.
     *
     * @return {@code {xMin, xMax, yMin, yMax}} in kilometres about the footprint centre
     */
    private static double[] equalScaleFrame(List<double[]> scatter, List<LandingEllipse> ellipses,
                                            GeoPoint centre, double metresPerDegreeLat,
                                            double metresPerDegreeLon) {
        double minEast = 0, maxEast = 0, minNorth = 0, maxNorth = 0;
        for (double[] point : scatter) {
            minEast = Math.min(minEast, point[0]);
            maxEast = Math.max(maxEast, point[0]);
            minNorth = Math.min(minNorth, point[1]);
            maxNorth = Math.max(maxNorth, point[1]);
        }
        for (LandingEllipse ellipse : ellipses) {
            double[][] outline = ellipseOutline(ellipse, centre, metresPerDegreeLat,
                    metresPerDegreeLon);
            for (int i = 0; i < outline[0].length; i++) {
                minEast = Math.min(minEast, outline[0][i]);
                maxEast = Math.max(maxEast, outline[0][i]);
                minNorth = Math.min(minNorth, outline[1][i]);
                maxNorth = Math.max(maxNorth, outline[1][i]);
            }
        }
        double span = Math.max(maxEast - minEast, maxNorth - minNorth);
        span = span <= 0 ? 1.0 : span * 1.12;
        double frameEast = (minEast + maxEast) / 2.0;
        double frameNorth = (minNorth + maxNorth) / 2.0;
        return new double[]{
                frameEast - span / 2.0, frameEast + span / 2.0,
                frameNorth - span / 2.0, frameNorth + span / 2.0};
    }

    private static double[] minutes(StateHistory history) {
        return history.states().stream().mapToDouble(s -> s.timeSeconds() / 60.0).toArray();
    }

    private static double[] kilometres(StateHistory history) {
        return history.states().stream().mapToDouble(s -> s.altitudeM() / 1000.0).toArray();
    }

    private static double[] column(List<double[]> rows, int index) {
        double[] out = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            out[i] = rows.get(i)[index];
        }
        return out;
    }

    private static Path write(Path path, XYChart chart) throws PersistenceException {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            // XChart appends the extension itself, so it is handed the stem.
            String stem = path.toString().replaceAll("\\.png$", "");
            BitmapEncoder.saveBitmap(chart, stem, BitmapEncoder.BitmapFormat.PNG);
            return path;
        } catch (IOException e) {
            throw new PersistenceException("cannot write chart to " + path, e);
        }
    }
}
