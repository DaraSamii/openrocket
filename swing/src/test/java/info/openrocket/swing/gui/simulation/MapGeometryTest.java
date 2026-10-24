package info.openrocket.swing.gui.simulation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class MapGeometryTest {
	@Test
	public void testDistancesBecomeDegreesOnTheMapSphere() {
		// 1 km north is 1000 / 6378137 rad of latitude; 1 km east at 60 degrees north is twice as many degrees
		double degreesPerKm = Math.toDegrees(1000 / MapGeometry.EARTH_RADIUS);
		assertArrayEquals(new double[]{45 + degreesPerKm, 7}, MapGeometry.toLatLon(45, 7, 0, 1000), 1e-12);
		assertArrayEquals(new double[]{60, -100 + 2 * degreesPerKm}, MapGeometry.toLatLon(60, -100, 1000, 0), 1e-9);
	}

	@Test
	public void testBearingIsClockwiseFromNorth() {
		assertEquals(0, MapGeometry.bearing(0, 0, 0, 10), 1e-9);
		assertEquals(90, MapGeometry.bearing(0, 0, 10, 0), 1e-9);
		assertEquals(225, MapGeometry.bearing(5, 5, 0, 0), 1e-9);
		assertEquals(315, MapGeometry.bearing(0, 0, -1, 1), 1e-9);
	}

	@Test
	public void testPlacesAreWrittenForReadingAndForCopying() {
		assertEquals("32.99031° N, 106.97502° W", MapGeometry.format(32.990311, -106.975022));
		assertEquals("32.990311, -106.975022", MapGeometry.formatDecimal(32.990311, -106.975022));
		assertEquals("https://www.openstreetmap.org/?mlat=45.500000&mlon=-73.600000#map=17/45.500000/-73.600000",
				MapGeometry.openStreetMapUrl(45.5, -73.6));
	}
}
