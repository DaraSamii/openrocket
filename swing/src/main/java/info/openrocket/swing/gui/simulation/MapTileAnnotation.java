package info.openrocket.swing.gui.simulation;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;

import org.jfree.chart.annotations.AbstractXYAnnotation;
import org.jfree.chart.axis.ValueAxis;
import org.jfree.chart.plot.Plot;
import org.jfree.chart.plot.PlotRenderingInfo;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.ui.RectangleEdge;

import info.openrocket.core.arch.SystemInfo;
import info.openrocket.core.communication.DefaultConnectionSource;
import info.openrocket.core.unit.Unit;
import info.openrocket.core.util.BuildProperties;

/**
 * OpenStreetMap tiles behind a plot of east and north distances from a launch site. Following the OpenStreetMap tile
 * usage policy (https://operations.osmfoundation.org/policies/tiles/), only the visible tiles are downloaded, with an
 * OpenRocket User-Agent and at most two at a time, they are kept on disk for a week, and the map is credited on the
 * plot.
 */
public class MapTileAnnotation extends AbstractXYAnnotation {
	static final String TILE_URL = "https://tile.openstreetmap.org/%d/%d/%d.png";
	static final String ATTRIBUTION = "© OpenStreetMap contributors";
	/** Radius of the Web Mercator sphere, in m */
	static final double EARTH_RADIUS = 6378137;
	static final int TILE_SIZE = 256;
	static final int MAX_ZOOM = 19;
	/** More tiles than this are never drawn at once, so a far zoomed-out plot cannot download a large area */
	private static final int MAX_TILES = 64;
	private static final long CACHE_MILLIS = 7L * 24 * 60 * 60 * 1000;
	private static final int TIMEOUT_MILLIS = 15000;

	private static final ExecutorService LOADER = Executors.newFixedThreadPool(2, runnable -> {
		Thread thread = new Thread(runnable, "MapTileLoader");
		thread.setDaemon(true);
		return thread;
	});
	private static final Map<String, BufferedImage> MEMORY_CACHE = Collections.synchronizedMap(
			new LinkedHashMap<>(256, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
					return size() > 256;
				}
			});
	private static final Set<String> REQUESTED = ConcurrentHashMap.newKeySet();

	private final double latitude;
	private final double longitude;
	private final Unit distanceUnit;
	private final Runnable redraw;

	/**
	 * @param latitude latitude of the launch site, in degrees
	 * @param longitude longitude of the launch site, in degrees
	 * @param distanceUnit unit of the plot axes
	 * @param redraw redraws the chart, when a tile has been loaded
	 */
	public MapTileAnnotation(double latitude, double longitude, Unit distanceUnit, Runnable redraw) {
		this.latitude = latitude;
		this.longitude = longitude;
		this.distanceUnit = distanceUnit;
		this.redraw = redraw;
	}

	/**
	 * @return the size of a tile pixel at the launch site at a zoom level, in m
	 */
	static double metersPerTilePixel(double latitude, int zoom) {
		return 2 * Math.PI * EARTH_RADIUS * Math.cos(Math.toRadians(latitude)) / (TILE_SIZE * Math.pow(2, zoom));
	}

	/**
	 * @return the zoom level whose tile pixels are closest to the size of a screen pixel
	 */
	static int zoomFor(double latitude, double metersPerScreenPixel) {
		double zoom = Math.log(metersPerTilePixel(latitude, 0) / metersPerScreenPixel) / Math.log(2);
		return (int) Math.max(0, Math.min(MAX_ZOOM, Math.round(zoom)));
	}

	/**
	 * @return the Web Mercator pixel coordinates of a place at a zoom level
	 */
	static double[] worldPixel(double latitude, double longitude, int zoom) {
		double size = TILE_SIZE * Math.pow(2, zoom);
		double sinLatitude = Math.sin(Math.toRadians(latitude));
		double x = (longitude + 180) / 360 * size;
		double y = (0.5 - Math.log((1 + sinLatitude) / (1 - sinLatitude)) / (4 * Math.PI)) * size;
		return new double[]{x, y};
	}

	@Override
	public void draw(Graphics2D g2, XYPlot plot, Rectangle2D dataArea, ValueAxis domainAxis, ValueAxis rangeAxis,
			int rendererIndex, PlotRenderingInfo info) {
		RectangleEdge domainEdge = Plot.resolveDomainAxisLocation(plot.getDomainAxisLocation(), plot.getOrientation());
		RectangleEdge rangeEdge = Plot.resolveRangeAxisLocation(plot.getRangeAxisLocation(), plot.getOrientation());
		double metersPerScreenPixel = distanceUnit.fromUnit(domainAxis.getRange().getLength()) / dataArea.getWidth();
		int zoom = zoomFor(latitude, metersPerScreenPixel);
		double resolution = metersPerTilePixel(latitude, zoom);
		double[] pad = worldPixel(latitude, longitude, zoom);

		// Near the launch site, east and north are straight in the map: one tile pixel is the same size everywhere
		double left = pad[0] + distanceUnit.fromUnit(domainAxis.getLowerBound()) / resolution;
		double right = pad[0] + distanceUnit.fromUnit(domainAxis.getUpperBound()) / resolution;
		double top = pad[1] - distanceUnit.fromUnit(rangeAxis.getUpperBound()) / resolution;
		double bottom = pad[1] - distanceUnit.fromUnit(rangeAxis.getLowerBound()) / resolution;
		int tiles = 1 << zoom;
		int firstX = (int) Math.floor(left / TILE_SIZE);
		int lastX = (int) Math.floor(right / TILE_SIZE);
		int firstY = Math.max(0, (int) Math.floor(top / TILE_SIZE));
		int lastY = Math.min(tiles - 1, (int) Math.floor(bottom / TILE_SIZE));
		if ((long) (lastX - firstX + 1) * (lastY - firstY + 1) > MAX_TILES) {
			return;
		}

		Shape oldClip = g2.getClip();
		Object oldInterpolation = g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		g2.clip(dataArea);
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		for (int tileY = firstY; tileY <= lastY; tileY++) {
			for (int tileX = firstX; tileX <= lastX; tileX++) {
				BufferedImage image = tile(zoom, Math.floorMod(tileX, tiles), tileY);
				if (image == null) {
					continue;
				}
				double x0 = domainAxis.valueToJava2D(distanceUnit.toUnit((tileX * TILE_SIZE - pad[0]) * resolution),
						dataArea, domainEdge);
				double x1 = domainAxis.valueToJava2D(
						distanceUnit.toUnit(((tileX + 1) * TILE_SIZE - pad[0]) * resolution), dataArea, domainEdge);
				double y0 = rangeAxis.valueToJava2D(distanceUnit.toUnit((pad[1] - tileY * TILE_SIZE) * resolution),
						dataArea, rangeEdge);
				double y1 = rangeAxis.valueToJava2D(
						distanceUnit.toUnit((pad[1] - (tileY + 1) * TILE_SIZE) * resolution), dataArea, rangeEdge);
				g2.drawImage(image, (int) Math.floor(x0), (int) Math.floor(y0),
						(int) Math.ceil(x1 - x0) + 1, (int) Math.ceil(y1 - y0) + 1, null);
			}
		}
		drawAttribution(g2, dataArea);
		if (oldInterpolation != null) {
			g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterpolation);
		}
		g2.setClip(oldClip);
	}

	private static void drawAttribution(Graphics2D g2, Rectangle2D dataArea) {
		g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 10f));
		FontMetrics metrics = g2.getFontMetrics();
		int width = metrics.stringWidth(ATTRIBUTION) + 6;
		int height = metrics.getHeight();
		int x = (int) (dataArea.getMaxX() - width);
		int y = (int) (dataArea.getMaxY() - height);
		g2.setColor(new Color(255, 255, 255, 200));
		g2.fillRect(x, y, width, height);
		g2.setColor(Color.DARK_GRAY);
		g2.drawString(ATTRIBUTION, x + 3, y + metrics.getAscent());
	}

	/**
	 * @return the tile if it is loaded, else null after requesting it
	 */
	private BufferedImage tile(int zoom, int x, int y) {
		String key = zoom + "/" + x + "/" + y;
		BufferedImage image = MEMORY_CACHE.get(key);
		if (image == null && REQUESTED.add(key)) {
			LOADER.execute(() -> {
				BufferedImage loaded = load(zoom, x, y);
				if (loaded != null) {
					MEMORY_CACHE.put(key, loaded);
					// The renderer does not pass annotation changes on to the chart, so redraw it directly
					SwingUtilities.invokeLater(redraw);
				}
				// A failed tile stays requested, so it is not asked for again while OpenRocket runs
			});
		}
		return image;
	}

	private static BufferedImage load(int zoom, int x, int y) {
		File file = new File(SystemInfo.getUserApplicationDirectory(),
				String.format(Locale.ROOT, "MapTiles/%d/%d/%d.png", zoom, x, y));
		try {
			if (file.isFile() && System.currentTimeMillis() - file.lastModified() < CACHE_MILLIS) {
				return ImageIO.read(file);
			}
			HttpURLConnection connection = new DefaultConnectionSource().getConnection(
					String.format(Locale.ROOT, TILE_URL, zoom, x, y));
			connection.setConnectTimeout(TIMEOUT_MILLIS);
			connection.setReadTimeout(TIMEOUT_MILLIS);
			connection.setRequestProperty("User-Agent",
					"OpenRocket/" + BuildProperties.getVersion() + " (+https://openrocket.info)");
			try (InputStream in = connection.getInputStream()) {
				Files.createDirectories(file.getParentFile().toPath());
				File partial = new File(file.getPath() + ".part");
				Files.copy(in, partial.toPath(), StandardCopyOption.REPLACE_EXISTING);
				Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
			} finally {
				connection.disconnect();
			}
			return ImageIO.read(file);
		} catch (IOException e) {
			return null;
		}
	}
}
