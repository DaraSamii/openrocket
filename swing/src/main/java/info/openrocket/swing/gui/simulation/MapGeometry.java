package info.openrocket.swing.gui.simulation;

import java.util.Locale;

/**
 * Converts between distances east and north of a launch site, as in the landing plot, and latitude and longitude.
 * Near the launch site, a few kilometres around it, the earth is taken as flat, with the radius that the map tiles use,
 * so the coordinates match the map behind the plot.
 */
final class MapGeometry {
	/** Radius of the Web Mercator sphere of the map tiles, in m */
	static final double EARTH_RADIUS = 6378137;

	private MapGeometry() {
	}

	/**
	 * @param latitude latitude of the launch site, in degrees
	 * @param longitude longitude of the launch site, in degrees
	 * @param east distance east of the launch site, in m
	 * @param north distance north of the launch site, in m
	 * @return the latitude and longitude of the place, in degrees
	 */
	static double[] toLatLon(double latitude, double longitude, double east, double north) {
		double lat = latitude + Math.toDegrees(north / EARTH_RADIUS);
		double lon = longitude + Math.toDegrees(east / (EARTH_RADIUS * Math.cos(Math.toRadians(latitude))));
		return new double[]{lat, lon};
	}

	/**
	 * @return the bearing from the first to the second place, clockwise from north, in degrees from 0 to 360
	 */
	static double bearing(double east1, double north1, double east2, double north2) {
		double degrees = Math.toDegrees(Math.atan2(east2 - east1, north2 - north1));
		return degrees < 0 ? degrees + 360 : degrees;
	}

	/**
	 * @return the place as text to read, like "32.99031° N, 106.97502° W"
	 */
	static String format(double latitude, double longitude) {
		return String.format(Locale.ROOT, "%.5f° %s, %.5f° %s", Math.abs(latitude), latitude < 0 ? "S" : "N",
				Math.abs(longitude), longitude < 0 ? "W" : "E");
	}

	/**
	 * @return the place as text to copy into other programs, like "32.99031, -106.97502"
	 */
	static String formatDecimal(double latitude, double longitude) {
		return String.format(Locale.ROOT, "%.6f, %.6f", latitude, longitude);
	}

	/**
	 * @return the address of the place on the OpenStreetMap web site
	 */
	static String openStreetMapUrl(double latitude, double longitude) {
		return String.format(Locale.ROOT, "https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=17/%.6f/%.6f",
				latitude, longitude, latitude, longitude);
	}

	/**
	 * @return the address of the place on Google Maps
	 */
	static String googleMapsUrl(double latitude, double longitude) {
		return String.format(Locale.ROOT, "https://www.google.com/maps/search/?api=1&query=%.6f,%.6f",
				latitude, longitude);
	}
}
