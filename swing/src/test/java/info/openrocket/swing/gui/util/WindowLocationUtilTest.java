package info.openrocket.swing.gui.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;

import org.junit.jupiter.api.Test;

public class WindowLocationUtilTest {
	/** A second screen to the right of a 1920 x 1080 primary screen */
	private static final Rectangle SECOND_SCREEN = new Rectangle(1920, 0, 2560, 1440);

	@Test
	public void testWindowIsCenteredOnTheMainWindowOnItsScreen() {
		Rectangle main = new Rectangle(2200, 200, 1200, 800);

		Point location = WindowLocationUtil.centeredLocation(main, new Dimension(400, 300), SECOND_SCREEN);

		assertEquals(new Point(2200 + 400, 200 + 250), location);
	}

	@Test
	public void testWindowStaysOnTheScreenOfAMainWindowNearItsEdge() {
		// The main window hangs over the left edge of the second screen, into the primary screen
		Rectangle main = new Rectangle(1700, 1000, 600, 400);

		Point location = WindowLocationUtil.centeredLocation(main, new Dimension(800, 600), SECOND_SCREEN);

		assertEquals(new Point(1920, 1440 - 600), location);
	}

	@Test
	public void testWindowLargerThanTheScreenKeepsItsTitleBarOnTheScreen() {
		Rectangle main = new Rectangle(1920, 0, 2560, 1440);

		Point location = WindowLocationUtil.centeredLocation(main, new Dimension(3000, 2000), SECOND_SCREEN);

		assertEquals(new Point(1920, 0), location);
	}
}
