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

	@Test
	public void testFailedTilesAreTriedAgainAfterAGrowingWait() {
		assertEquals(2000, MapTileAnnotation.retryDelayMillis(1));
		assertEquals(4000, MapTileAnnotation.retryDelayMillis(2));
		assertEquals(32000, MapTileAnnotation.retryDelayMillis(5));
		assertEquals(60000, MapTileAnnotation.retryDelayMillis(6));
		assertEquals(60000, MapTileAnnotation.retryDelayMillis(40));
	}

	/**
	 * While tile 5/7 of zoom 10 loads, the quarter of tile 2/3 of zoom 9 that covers it stands in, or the sixteenth of
	 * tile 1/1 of zoom 8.
	 */
	@Test
	public void testACoarserTileStandsInForALoadingOne() {
		assertArrayEquals(new int[]{2, 3, 128, 128, 128}, MapTileAnnotation.ancestorPart(5, 7, 1));
		assertArrayEquals(new int[]{1, 1, 64, 192, 64}, MapTileAnnotation.ancestorPart(5, 7, 2));
	}

	@Test
	public void testScaleBarLengthsAreRound() {
		assertEquals(100, PlotOverlayAnnotation.roundLength(137), 1e-9);
		assertEquals(200, PlotOverlayAnnotation.roundLength(250), 1e-9);
		assertEquals(500, PlotOverlayAnnotation.roundLength(999), 1e-9);
		assertEquals(0.5, PlotOverlayAnnotation.roundLength(0.7), 1e-12);
	}
}
