package info.openrocket.swing.gui.simulation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Checks the Web Mercator tile maths against values from the OpenStreetMap "Slippy map tilenames" definition.
 */
public class MapTileAnnotationTest {
	@Test
	public void testWorldPixelsOfKnownPlaces() {
		// The equator at the prime meridian is the center of the world
		assertArrayEquals(new double[]{128, 128}, MapTileAnnotation.worldPixel(0, 0, 0), 1e-9);
		// The top-left corner of the world is at 85.0511 degrees north, 180 degrees west
		assertArrayEquals(new double[]{0, 0}, MapTileAnnotation.worldPixel(85.0511287798, -180, 0), 1e-6);
		// Spaceport America is in tile 3 / 1 of zoom 3 (x 1.62, y 3.23 tiles)
		double[] pixel = MapTileAnnotation.worldPixel(32.99, -106.97, 3);
		assertEquals(1, (int) (pixel[0] / 256));
		assertEquals(3, (int) (pixel[1] / 256));
	}

	@Test
	public void testTilePixelSizeAndZoom() {
		// One tile spans the equator at zoom 0: 2 pi 6378137 m / 256 = 156543.03 m per pixel
		assertEquals(156543.03392, MapTileAnnotation.metersPerTilePixel(0, 0), 1e-4);
		// At 60 degrees north a pixel covers half that
		assertEquals(156543.03392 / 2, MapTileAnnotation.metersPerTilePixel(60, 0), 1e-4);
		// The zoom is the one whose pixels are closest to a screen pixel
		assertEquals(10, MapTileAnnotation.zoomFor(0, 156543.03392 / 1024));
		assertEquals(10, MapTileAnnotation.zoomFor(0, 156543.03392 / 1100));
		assertEquals(0, MapTileAnnotation.zoomFor(0, 1e9));
		assertEquals(MapTileAnnotation.MAX_ZOOM, MapTileAnnotation.zoomFor(0, 1e-6));
	}
}
