package info.openrocket.core.models.wind;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import info.openrocket.core.models.wind.OpenMeteoWindProfile.Level;
import info.openrocket.core.models.wind.OpenMeteoWindProfile.Model;

public class OpenMeteoWindProfileTest {
	private static final double EPSILON = 1e-9;

	/**
	 * A response for a site 1400 m above sea level with two hours. The 1000 hPa level is under the ground and the
	 * 850 hPa level is among the ground levels (1400 + 180 m), so neither is used. Some values are missing.
	 */
	private static final String RESPONSE = """
			{"latitude": 32.99, "longitude": -106.97, "elevation": 1400.0, "timezone": "America/Denver",
			 "hourly": {
			  "time": ["2025-06-20T13:00", "2025-06-20T14:00"],
			  "wind_speed_10m": [1.0, 2.0], "wind_direction_10m": [90, 180],
			  "wind_speed_80m": [3.0, 4.0], "wind_direction_80m": [90, 190],
			  "wind_speed_120m": [null, 5.0], "wind_direction_120m": [null, 200],
			  "wind_speed_180m": [5.0, 6.0], "wind_direction_180m": [90, 210],
			  "wind_speed_1000hPa": [9.0, 9.0], "wind_direction_1000hPa": [0, 0],
			  "geopotential_height_1000hPa": [110, 110],
			  "wind_speed_850hPa": [7.0, 7.0], "wind_direction_850hPa": [0, 0],
			  "geopotential_height_850hPa": [1490, 1500],
			  "wind_speed_700hPa": [10.0, 12.0], "wind_direction_700hPa": [270, 280],
			  "geopotential_height_700hPa": [3100, 3110],
			  "wind_speed_500hPa": [null, 20.0], "wind_direction_500hPa": [300, 310],
			  "geopotential_height_500hPa": [5880, 5890]
			 }}
			""";

	@Test
	public void testLevelsOfTheHourAboveSeaLevelAndAboveTheGround() throws IOException {
		List<Level> levels = OpenMeteoWindProfile.parse(RESPONSE, LocalDateTime.of(2025, 6, 20, 14, 25));

		assertEquals(List.of(1410.0, 1480.0, 1520.0, 1580.0, 3110.0, 5890.0),
				levels.stream().map(Level::altitude).toList());
		assertEquals(2.0, levels.get(0).speed(), EPSILON);
		assertEquals(Math.PI, levels.get(0).direction(), EPSILON);
		assertEquals(12.0, levels.get(4).speed(), EPSILON);
		assertEquals(Math.toRadians(280), levels.get(4).direction(), EPSILON);
	}

	@Test
	public void testMissingValuesLeaveTheirLevelOut() throws IOException {
		List<Level> levels = OpenMeteoWindProfile.parse(RESPONSE, LocalDateTime.of(2025, 6, 20, 13, 0));

		assertEquals(List.of(1410.0, 1480.0, 1580.0, 3100.0), levels.stream().map(Level::altitude).toList());
	}

	@Test
	public void testErrorsAndMissingHoursAreReported() {
		IOException error = assertThrows(IOException.class, () -> OpenMeteoWindProfile.parse(
				"{\"error\": true, \"reason\": \"Parameter 'start_date' is out of allowed range\"}",
				LocalDateTime.of(2030, 1, 1, 12, 0)));
		assertEquals("Parameter 'start_date' is out of allowed range", error.getMessage());

		assertThrows(IOException.class, () -> OpenMeteoWindProfile.parse(RESPONSE,
				LocalDateTime.of(2025, 6, 21, 13, 0)));
		assertThrows(IOException.class, () -> OpenMeteoWindProfile.parse("<html>", LocalDateTime.now()));
	}

	@Test
	public void testRecentDaysUseTheForecastAndOlderDaysTheHistoricalForecast() {
		LocalDate today = LocalDate.of(2026, 10, 23);

		URI recent = OpenMeteoWindProfile.uri(45.5, -73.6, LocalDateTime.of(2026, 10, 30, 9, 0), Model.NOAA_GFS,
				today);
		URI old = OpenMeteoWindProfile.uri(45.5, -73.6, LocalDateTime.of(2024, 5, 1, 9, 0), Model.NOAA_GFS, today);

		assertTrue(recent.toString().startsWith(OpenMeteoWindProfile.FORECAST_URL + "?"), recent.toString());
		assertTrue(old.toString().startsWith(OpenMeteoWindProfile.HISTORICAL_URL + "?"), old.toString());
		String query = recent.getQuery();
		assertTrue(query.contains("start_date=2026-10-30&end_date=2026-10-30"), query);
		assertTrue(query.contains("models=gfs_seamless"), query);
		assertTrue(query.contains("wind_speed_unit=ms"), query);
		assertTrue(query.contains("timezone=auto"), query);
		assertTrue(query.contains("geopotential_height_500hPa"), query);
	}
}
