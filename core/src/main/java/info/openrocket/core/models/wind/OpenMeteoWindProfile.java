package info.openrocket.core.models.wind;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import info.openrocket.core.communication.DefaultConnectionSource;
import info.openrocket.core.util.BuildProperties;

/**
 * A wind profile for a place and an hour from the weather models of Open-Meteo (https://open-meteo.com), whose data
 * is licensed under CC BY 4.0. The profile has the wind near the ground at fixed heights, and the wind at the standard
 * pressure levels above it, at their geopotential height.
 * <p>
 * Recent and forecast days, up to about two weeks ahead, come from the forecast API; older days, from 2022 on, come
 * from the historical forecast API, which keeps the past forecasts of the same models.
 */
public final class OpenMeteoWindProfile {
	/** Weather models of Open-Meteo that have pressure levels */
	public enum Model {
		BEST_MATCH("best_match"),
		NOAA_GFS("gfs_seamless"),
		ECMWF_IFS("ecmwf_ifs025"),
		DWD_ICON("icon_seamless"),
		CANADA_GEM("gem_seamless"),
		METEO_FRANCE("meteofrance_seamless"),
		JMA("jma_seamless");

		private final String id;

		Model(String id) {
			this.id = id;
		}

		public String getId() {
			return id;
		}
	}

	/**
	 * A wind level.
	 *
	 * @param altitude altitude above sea level, in m
	 * @param speed wind speed, in m/s
	 * @param direction direction the wind comes from, clockwise from north, in radians
	 */
	public record Level(double altitude, double speed, double direction) {
	}

	static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast";
	static final String HISTORICAL_URL = "https://historical-forecast-api.open-meteo.com/v1/forecast";
	/** Days back that the forecast API keeps; older days come from the historical forecast API */
	static final int FORECAST_PAST_DAYS = 90;
	/** Heights above the ground of the wind near the ground, in m */
	static final int[] HEIGHTS = {10, 80, 120, 180};
	/** Standard pressure levels, in hPa */
	static final int[] PRESSURE_LEVELS = {1000, 975, 950, 925, 900, 850, 800, 700, 600, 500, 400, 300, 250, 200,
			150, 100, 70, 50, 30};
	private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00");
	private static final int TIMEOUT_MILLIS = 20000;

	private OpenMeteoWindProfile() {
	}

	/**
	 * @param time local time at the launch site
	 * @param today the current date, which decides whether the forecast or the historical API has the day
	 * @return the request for the wind of the whole local day
	 */
	static URI uri(double latitude, double longitude, LocalDateTime time, Model model, LocalDate today) {
		LocalDate day = time.toLocalDate();
		String base = day.isBefore(today.minusDays(FORECAST_PAST_DAYS)) ? HISTORICAL_URL : FORECAST_URL;
		List<String> variables = new ArrayList<>();
		for (int height : HEIGHTS) {
			variables.add("wind_speed_" + height + "m");
			variables.add("wind_direction_" + height + "m");
		}
		for (int level : PRESSURE_LEVELS) {
			variables.add("wind_speed_" + level + "hPa");
			variables.add("wind_direction_" + level + "hPa");
			variables.add("geopotential_height_" + level + "hPa");
		}
		String query = String.format(Locale.ROOT,
				"latitude=%.5f&longitude=%.5f&start_date=%s&end_date=%s&hourly=%s&models=%s"
						+ "&wind_speed_unit=ms&timezone=auto",
				latitude, longitude, day, day, URLEncoder.encode(String.join(",", variables), StandardCharsets.UTF_8),
				model.getId());
		return URI.create(base + "?" + query);
	}

	/**
	 * Download the wind profile.
	 *
	 * @param time local time at the launch site; the profile is the one of that hour
	 * @return the levels, from the lowest up
	 * @throws IOException if the download fails, or Open-Meteo has no wind for the place and time
	 */
	public static List<Level> download(double latitude, double longitude, LocalDateTime time, Model model)
			throws IOException {
		String url = uri(latitude, longitude, time, model, LocalDate.now()).toString();
		HttpURLConnection connection = new DefaultConnectionSource().getConnection(url);
		connection.setConnectTimeout(TIMEOUT_MILLIS);
		connection.setReadTimeout(TIMEOUT_MILLIS);
		connection.setRequestProperty("User-Agent",
				"OpenRocket/" + BuildProperties.getVersion() + " (+https://openrocket.info)");
		try {
			// Open-Meteo explains a rejected request in the body of the error response
			InputStream body = connection.getResponseCode() < 400 ? connection.getInputStream()
					: connection.getErrorStream();
			if (body == null) {
				throw new IOException("HTTP " + connection.getResponseCode() + " from Open-Meteo");
			}
			try (body) {
				return parse(new String(body.readAllBytes(), StandardCharsets.UTF_8), time);
			}
		} finally {
			connection.disconnect();
		}
	}

	/**
	 * @param json the response of Open-Meteo
	 * @param time local time of the profile
	 * @return the levels of the hour of the time, from the lowest up; levels under the ground and levels without data
	 * 		are left out
	 * @throws IOException if the response is an error, or has no wind for the hour
	 */
	static List<Level> parse(String json, LocalDateTime time) throws IOException {
		JsonObject root;
		try {
			root = JsonParser.parseString(json).getAsJsonObject();
		} catch (JsonParseException | IllegalStateException e) {
			throw new IOException("Unexpected response from Open-Meteo", e);
		}
		if (root.has("error")) {
			throw new IOException(root.has("reason") ? root.get("reason").getAsString() : "Open-Meteo error");
		}
		JsonObject hourly = root.getAsJsonObject("hourly");
		if (hourly == null || !hourly.has("time")) {
			throw new IOException("No hourly data from Open-Meteo");
		}
		int index = indexOf(hourly.getAsJsonArray("time"), HOUR.format(time));
		if (index < 0) {
			throw new IOException("No data from Open-Meteo for " + HOUR.format(time));
		}
		double elevation = root.has("elevation") ? root.get("elevation").getAsDouble() : 0;

		List<Level> levels = new ArrayList<>();
		for (int height : HEIGHTS) {
			addLevel(levels, elevation + height, value(hourly, "wind_speed_" + height + "m", index),
					value(hourly, "wind_direction_" + height + "m", index));
		}
		double groundLevelsTop = elevation + HEIGHTS[HEIGHTS.length - 1];
		for (int level : PRESSURE_LEVELS) {
			double altitude = value(hourly, "geopotential_height_" + level + "hPa", index);
			// Pressure levels under the ground, or among the ground levels, are not used
			if (altitude > groundLevelsTop) {
				addLevel(levels, altitude, value(hourly, "wind_speed_" + level + "hPa", index),
						value(hourly, "wind_direction_" + level + "hPa", index));
			}
		}
		if (levels.isEmpty()) {
			throw new IOException("No wind data from Open-Meteo for " + HOUR.format(time));
		}
		levels.sort(Comparator.comparingDouble(Level::altitude));
		return levels;
	}

	private static void addLevel(List<Level> levels, double altitude, double speed, double directionDegrees) {
		if (Double.isFinite(altitude) && Double.isFinite(speed) && Double.isFinite(directionDegrees)) {
			levels.add(new Level(altitude, speed, Math.toRadians(directionDegrees)));
		}
	}

	private static int indexOf(JsonArray times, String time) {
		for (int i = 0; i < times.size(); i++) {
			if (time.equals(times.get(i).getAsString())) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * @return the value of a variable at an index, or NaN if the variable or the value is missing
	 */
	private static double value(JsonObject hourly, String variable, int index) {
		JsonArray values = hourly.getAsJsonArray(variable);
		if (values == null || index >= values.size()) {
			return Double.NaN;
		}
		JsonElement value = values.get(index);
		return value.isJsonNull() ? Double.NaN : value.getAsDouble();
	}
}
