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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
 * Map tiles behind a plot of east and north distances from a launch site.
 * <p>
 * Only the visible tiles are downloaded, the ones nearest the middle of the view first, at most two at a time, with
 * an OpenRocket User-Agent; requests for tiles that are no longer visible are dropped. Tiles are kept on disk for a
 * week. While a tile loads, a coarser tile that is already loaded is shown in its place, scaled up, so the map does
 * not go blank when zooming. A tile that fails is tried again after a growing wait. The map is credited on the plot,
 * following the tile usage policies of OpenStreetMap and OpenTopoMap.
 */
public class MapTileAnnotation extends AbstractXYAnnotation {
	/** Map tile sources */
	public enum MapLayer {
		OPENSTREETMAP("https://tile.openstreetmap.org/%d/%d/%d.png", 19,
				"© OpenStreetMap contributors"),
		OPENTOPOMAP("https://%s.tile.opentopomap.org/%d/%d/%d.png", 17,
				"Map data: © OpenStreetMap contributors, SRTM | Map style: © OpenTopoMap (CC-BY-SA)");

		private final String urlPattern;
		private final int maxZoom;
		private final String attribution;

		MapLayer(String urlPattern, int maxZoom, String attribution) {
			this.urlPattern = urlPattern;
			this.maxZoom = maxZoom;
			this.attribution = attribution;
		}

		String url(int zoom, int x, int y) {
			if (this == OPENTOPOMAP) {
				// OpenTopoMap spreads its tiles over three servers
				String server = String.valueOf((char) ('a' + Math.floorMod(x + y, 3)));
				return String.format(Locale.ROOT, urlPattern, server, zoom, x, y);
			}
			return String.format(Locale.ROOT, urlPattern, zoom, x, y);
		}

		int getMaxZoom() {
			return maxZoom;
		}

		String getAttribution() {
			return attribution;
		}
	}

	/** The loading state of the visible tiles */
	public record Status(int visible, int loaded, int failed) {
	}

	/** Radius of the Web Mercator sphere, in m */
	static final double EARTH_RADIUS = MapGeometry.EARTH_RADIUS;
	static final int TILE_SIZE = 256;
	static final int MAX_ZOOM = 19;
	/** How many zoom levels coarser a tile may be to stand in for one that is loading */
	static final int FALLBACK_LEVELS = 6;
	/** More tiles than this are never drawn at once; a larger plot uses coarser tiles */
	static final int MAX_TILES = 96;
	private static final long CACHE_MILLIS = 7L * 24 * 60 * 60 * 1000;
	private static final int TIMEOUT_MILLIS = 15000;
	private static final int MEMORY_TILES = 512;

	private static final ThreadPoolExecutor LOADER = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
			new PriorityBlockingQueue<>(), runnable -> {
				Thread thread = new Thread(runnable, "MapTileLoader");
				thread.setDaemon(true);
				return thread;
			});
	private static final ScheduledExecutorService RETRY_TIMER = Executors.newSingleThreadScheduledExecutor(
			runnable -> {
				Thread thread = new Thread(runnable, "MapTileRetry");
				thread.setDaemon(true);
				return thread;
			});
	private static final Map<String, BufferedImage> MEMORY_CACHE = Collections.synchronizedMap(
			new LinkedHashMap<>(MEMORY_TILES, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
					return size() > MEMORY_TILES;
				}
			});
	/** Tiles that are queued or downloading */
	private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
	/** Tiles that the server does not have */
	private static final Set<String> MISSING = ConcurrentHashMap.newKeySet();
	/** Failed tiles: the number of failures and the time after which to try again */
	private static final Map<String, long[]> FAILURES = new ConcurrentHashMap<>();

	private final double latitude;
	private final double longitude;
	private final Unit distanceUnit;
	private final Runnable redraw;
	private final Runnable statusChanged;
	private volatile MapLayer layer = MapLayer.OPENSTREETMAP;
	/** The tiles of the current view; queued tiles that are no longer in it are dropped */
	private volatile Set<String> wanted = Set.of();
	private volatile Status status = new Status(0, 0, 0);

	/**
	 * @param latitude latitude of the launch site, in degrees
	 * @param longitude longitude of the launch site, in degrees
	 * @param distanceUnit unit of the plot axes
	 * @param redraw redraws the chart, when a tile has been loaded
	 * @param statusChanged called on the event dispatch thread when {@link #getStatus()} changes
	 */
	public MapTileAnnotation(double latitude, double longitude, Unit distanceUnit, Runnable redraw,
			Runnable statusChanged) {
		this.latitude = latitude;
		this.longitude = longitude;
		this.distanceUnit = distanceUnit;
		this.redraw = redraw;
		this.statusChanged = statusChanged;
	}

	public void setLayer(MapLayer layer) {
		this.layer = layer;
		this.wanted = Set.of();
	}

	public MapLayer getLayer() {
		return layer;
	}

	/**
	 * @return how many of the visible tiles are loaded and how many failed
	 */
	public Status getStatus() {
		return status;
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

	/**
	 * @return the wait before trying a tile again after it failed this many times, in ms: 2, 4, 8 ... up to 60 s
	 */
	static long retryDelayMillis(int failures) {
		return Math.min(60_000L, 1000L << Math.min(failures, 6));
	}

	@Override
	public void draw(Graphics2D g2, XYPlot plot, Rectangle2D dataArea, ValueAxis domainAxis, ValueAxis rangeAxis,
			int rendererIndex, PlotRenderingInfo info) {
		MapLayer currentLayer = layer;
		RectangleEdge domainEdge = Plot.resolveDomainAxisLocation(plot.getDomainAxisLocation(), plot.getOrientation());
		RectangleEdge rangeEdge = Plot.resolveRangeAxisLocation(plot.getRangeAxisLocation(), plot.getOrientation());
		double metersPerScreenPixel = distanceUnit.fromUnit(domainAxis.getRange().getLength()) / dataArea.getWidth();
		int zoom = Math.min(currentLayer.getMaxZoom(), zoomFor(latitude, metersPerScreenPixel));
		double resolution;
		double[] pad;
		double left;
		double right;
		double top;
		double bottom;
		int tiles;
		int firstX;
		int lastX;
		int firstY;
		int lastY;
		while (true) {
			resolution = metersPerTilePixel(latitude, zoom);
			pad = worldPixel(latitude, longitude, zoom);
			// Near the launch site, east and north are straight in the map: one tile pixel is the same size everywhere
			left = pad[0] + distanceUnit.fromUnit(domainAxis.getLowerBound()) / resolution;
			right = pad[0] + distanceUnit.fromUnit(domainAxis.getUpperBound()) / resolution;
			top = pad[1] - distanceUnit.fromUnit(rangeAxis.getUpperBound()) / resolution;
			bottom = pad[1] - distanceUnit.fromUnit(rangeAxis.getLowerBound()) / resolution;
			tiles = 1 << zoom;
			firstX = (int) Math.floor(left / TILE_SIZE);
			lastX = (int) Math.floor(right / TILE_SIZE);
			firstY = Math.max(0, (int) Math.floor(top / TILE_SIZE));
			lastY = Math.min(tiles - 1, (int) Math.floor(bottom / TILE_SIZE));
			// A large plot, like a maximized window, can need many tiles; coarser tiles then cover it, scaled up a
			// little, so the map stays on screen and does not download a large area
			if ((long) (lastX - firstX + 1) * (lastY - firstY + 1) <= MAX_TILES || zoom == 0) {
				break;
			}
			zoom--;
		}
		double centerX = (left + right) / 2 / TILE_SIZE;
		double centerY = (top + bottom) / 2 / TILE_SIZE;

		Shape oldClip = g2.getClip();
		Object oldInterpolation = g2.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
		g2.clip(dataArea);
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		// First publish all the tiles that this view needs: a loader thread that starts a request before the set is
		// published would otherwise drop it as no longer visible, and the tile would stay missing
		Set<String> nowWanted = new HashSet<>();
		for (int tileY = firstY; tileY <= lastY; tileY++) {
			for (int tileX = firstX; tileX <= lastX; tileX++) {
				String key = key(currentLayer, zoom, Math.floorMod(tileX, tiles), tileY);
				if (!MEMORY_CACHE.containsKey(key) && !MISSING.contains(key)) {
					nowWanted.add(key);
				}
			}
		}
		wanted = nowWanted;

		int visible = 0;
		int loaded = 0;
		int failed = 0;
		for (int tileY = firstY; tileY <= lastY; tileY++) {
			for (int tileX = firstX; tileX <= lastX; tileX++) {
				int x = Math.floorMod(tileX, tiles);
				String key = key(currentLayer, zoom, x, tileY);
				visible++;
				double x0 = toJava2DX(domainAxis, dataArea, domainEdge, (tileX * TILE_SIZE - pad[0]) * resolution);
				double x1 = toJava2DX(domainAxis, dataArea, domainEdge, ((tileX + 1) * TILE_SIZE - pad[0]) * resolution);
				double y0 = toJava2DY(rangeAxis, dataArea, rangeEdge, (pad[1] - tileY * TILE_SIZE) * resolution);
				double y1 = toJava2DY(rangeAxis, dataArea, rangeEdge, (pad[1] - (tileY + 1) * TILE_SIZE) * resolution);
				BufferedImage image = MEMORY_CACHE.get(key);
				if (image != null) {
					loaded++;
					drawImage(g2, image, 0, 0, TILE_SIZE, x0, y0, x1, y1);
					continue;
				}
				if (MISSING.contains(key)) {
					continue;
				}
				drawFallback(g2, currentLayer, zoom, x, tileY, x0, y0, x1, y1);
				long[] failure = FAILURES.get(key);
				if (failure != null && System.currentTimeMillis() < failure[1]) {
					failed++;
					continue;
				}
				double priority = Math.hypot(tileX + 0.5 - centerX, tileY + 0.5 - centerY);
				request(currentLayer, zoom, x, tileY, key, priority);
			}
		}
		drawAttribution(g2, dataArea, currentLayer.getAttribution());
		if (oldInterpolation != null) {
			g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterpolation);
		}
		g2.setClip(oldClip);
		updateStatus(new Status(visible, loaded, failed));
	}

	private double toJava2DX(ValueAxis axis, Rectangle2D area, RectangleEdge edge, double meters) {
		return axis.valueToJava2D(distanceUnit.toUnit(meters), area, edge);
	}

	private double toJava2DY(ValueAxis axis, Rectangle2D area, RectangleEdge edge, double meters) {
		return axis.valueToJava2D(distanceUnit.toUnit(meters), area, edge);
	}

	private static void drawImage(Graphics2D g2, BufferedImage image, int sx, int sy, int size, double x0, double y0,
			double x1, double y1) {
		g2.drawImage(image, (int) Math.floor(x0), (int) Math.floor(y0), (int) Math.ceil(x1) + 1,
				(int) Math.ceil(y1) + 1, sx, sy, sx + size, sy + size, null);
	}

	/**
	 * Draws already loaded tiles of other zoom levels in place of a tile that is loading: the part of the nearest
	 * coarser tile, scaled up, which covers it when zooming in, and over it the finer tiles, scaled down, which are
	 * loaded when zooming out.
	 */
	private static void drawFallback(Graphics2D g2, MapLayer layer, int zoom, int x, int y, double x0, double y0,
			double x1, double y1) {
		for (int up = 1; up <= Math.min(FALLBACK_LEVELS, zoom); up++) {
			int[] part = ancestorPart(x, y, up);
			BufferedImage image = MEMORY_CACHE.get(key(layer, zoom - up, part[0], part[1]));
			if (image != null) {
				drawImage(g2, image, part[2], part[3], part[4], x0, y0, x1, y1);
				break;
			}
		}
		for (int down = 2; down >= 1; down--) {
			if (zoom + down > layer.getMaxZoom()) {
				continue;
			}
			int count = 1 << down;
			for (int j = 0; j < count; j++) {
				for (int i = 0; i < count; i++) {
					BufferedImage image = MEMORY_CACHE.get(key(layer, zoom + down, (x << down) + i, (y << down) + j));
					if (image != null) {
						drawImage(g2, image, 0, 0, TILE_SIZE, x0 + (x1 - x0) * i / count, y0 + (y1 - y0) * j / count,
								x0 + (x1 - x0) * (i + 1) / count, y0 + (y1 - y0) * (j + 1) / count);
					}
				}
			}
		}
	}

	/**
	 * @param up how many zoom levels coarser
	 * @return the coarser tile that contains the tile, as {x, y}, and the square of it that the tile covers, as
	 * 		{left, top, size} in pixels of the coarser tile
	 */
	static int[] ancestorPart(int x, int y, int up) {
		int size = TILE_SIZE >> up;
		return new int[]{x >> up, y >> up, (x & ((1 << up) - 1)) * size, (y & ((1 << up) - 1)) * size,
				Math.max(1, size)};
	}

	private void request(MapLayer layer, int zoom, int x, int y, String key, double priority) {
		if (!IN_FLIGHT.add(key)) {
			return;
		}
		LOADER.execute(new TileTask(priority, () -> {
			try {
				if (!wanted.contains(key)) {
					return;		// no longer visible; asked for again when it is
				}
				BufferedImage image = load(layer, zoom, x, y, key);
				if (image != null) {
					MEMORY_CACHE.put(key, image);
					FAILURES.remove(key);
					SwingUtilities.invokeLater(redraw);
				}
			} finally {
				IN_FLIGHT.remove(key);
			}
		}));
	}

	private void updateStatus(Status newStatus) {
		if (!newStatus.equals(status)) {
			status = newStatus;
			SwingUtilities.invokeLater(statusChanged);
		}
	}

	private static String key(MapLayer layer, int zoom, int x, int y) {
		return layer.name() + "/" + zoom + "/" + x + "/" + y;
	}

	/**
	 * Reads the tile from the disk cache, or downloads it. A failed download is tried again later; the chart is
	 * redrawn then so that it asks for the tile again.
	 *
	 * @return the tile, or null if it is missing or could not be loaded now
	 */
	private BufferedImage load(MapLayer layer, int zoom, int x, int y, String key) {
		File file = new File(SystemInfo.getUserApplicationDirectory(),
				String.format(Locale.ROOT, "MapTiles/%s/%d/%d/%d.png", layer.name().toLowerCase(Locale.ROOT),
						zoom, x, y));
		try {
			if (file.isFile() && System.currentTimeMillis() - file.lastModified() < CACHE_MILLIS) {
				BufferedImage cached = ImageIO.read(file);
				if (cached != null) {
					return cached;
				}
			}
			HttpURLConnection connection = new DefaultConnectionSource().getConnection(layer.url(zoom, x, y));
			connection.setConnectTimeout(TIMEOUT_MILLIS);
			connection.setReadTimeout(TIMEOUT_MILLIS);
			connection.setRequestProperty("User-Agent",
					"OpenRocket/" + BuildProperties.getVersion() + " (+https://openrocket.info)");
			try {
				int code = connection.getResponseCode();
				if (code == HttpURLConnection.HTTP_NOT_FOUND) {
					MISSING.add(key);
					return null;
				}
				if (code != HttpURLConnection.HTTP_OK) {
					throw new IOException("HTTP " + code);
				}
				try (InputStream in = connection.getInputStream()) {
					Files.createDirectories(file.getParentFile().toPath());
					File partial = new File(file.getPath() + ".part");
					Files.copy(in, partial.toPath(), StandardCopyOption.REPLACE_EXISTING);
					Files.move(partial.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
				}
			} finally {
				connection.disconnect();
			}
			BufferedImage image = ImageIO.read(file);
			if (image == null) {
				throw new IOException("Not an image");
			}
			return image;
		} catch (IOException e) {
			long[] failure = FAILURES.merge(key, new long[]{1, 0}, (old, one) -> new long[]{old[0] + 1, 0});
			long delay = retryDelayMillis((int) failure[0]);
			failure[1] = System.currentTimeMillis() + delay;
			RETRY_TIMER.schedule(() -> SwingUtilities.invokeLater(redraw), delay + 50, TimeUnit.MILLISECONDS);
			SwingUtilities.invokeLater(redraw);
			return null;
		}
	}

	private static void drawAttribution(Graphics2D g2, Rectangle2D dataArea, String attribution) {
		g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 10f));
		FontMetrics metrics = g2.getFontMetrics();
		int width = metrics.stringWidth(attribution) + 6;
		int height = metrics.getHeight();
		int x = (int) (dataArea.getMaxX() - width);
		int y = (int) (dataArea.getMaxY() - height);
		g2.setColor(new Color(255, 255, 255, 200));
		g2.fillRect(x, y, width, height);
		g2.setColor(Color.DARK_GRAY);
		g2.drawString(attribution, x + 3, y + metrics.getAscent());
	}

	/**
	 * A tile download, run before the ones farther from the middle of the view.
	 */
	private static final class TileTask implements Runnable, Comparable<TileTask> {
		private final double priority;
		private final Runnable task;

		private TileTask(double priority, Runnable task) {
			this.priority = priority;
			this.task = task;
		}

		@Override
		public void run() {
			task.run();
		}

		@Override
		public int compareTo(TileTask other) {
			return Double.compare(priority, other.priority);
		}
	}
}
