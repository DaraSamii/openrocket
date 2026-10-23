package info.openrocket.swing.gui.main;

import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.SplashScreen;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.WindowEvent;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JWindow;

import info.openrocket.core.util.BuildProperties;
import info.openrocket.swing.gui.util.WindowLocationUtil;

/**
 * Helper methods for manipulating the Java runtime splash screen.
 * <p>
 * Notes:
 * SplashScreen.update() takes randomly between 4 and 500 ms to complete,
 * even after it has been called ~100 times (and therefore pre-compiled).
 * Therefore it cannot be relied upon to perform for example color fades.
 * Care should be taken to call update() only once or twice per second.
 * 
 * @author Sampo Niskanen <sampo.niskanen@iki.fi>
 */
public class Splash {
	
	// The right edge of the text base line for the version string
	private static final int VERSION_POSITION_X = 617;
	private static final int VERSION_POSITION_Y = 150;
	private static final Font VERSION_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 14);
	private static final Color VERSION_COLOR = Color.WHITE;

	/** The splash screen shown on the startup screen instead of the Java one, or null */
	private static JWindow moved = null;
	
	
	/**
	 * Initialize the splash screen with additional information.  This method should
	 * be called as soon as reasonably possible during program startup.
	 * 
	 * @return	<code>true</code> if the splash screen could be successfully initialized
	 */
	public static boolean init() {
		// Get the splash screen
		SplashScreen s = getSplashScreen();
		if (s == null)
			return false;
		
		// Create graphics context and set antialiasing on
		Graphics2D g2 = s.createGraphics();
		g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
				RenderingHints.VALUE_STROKE_NORMALIZE);
		g2.setRenderingHint(RenderingHints.KEY_RENDERING,
				RenderingHints.VALUE_RENDER_QUALITY);
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
				RenderingHints.VALUE_ANTIALIAS_ON);
		
		// Draw the version number
		drawVersionNumber(g2);
		
		// Update the splash screen
		s.update();
		return true;
	}
	
	
	
	/**
	 * Java shows the splash screen before OpenRocket runs, centered on the whole desktop, so with several screens it
	 * can be between two of them. If so, the same splash screen is shown on the screen that OpenRocket opens on
	 * instead. Like the Java splash screen, it closes when the first other window opens.
	 */
	public static void moveToStartupScreen() {
		SplashScreen s = getSplashScreen();
		if (s == null || GraphicsEnvironment.isHeadless()) {
			return;
		}
		GraphicsConfiguration screen = WindowLocationUtil.getStartupScreen();
		if (screen.getBounds().contains(s.getBounds())) {
			return;
		}
		BufferedImage image;
		try {
			image = ImageIO.read(s.getImageURL());
		} catch (IOException e) {
			return;
		}
		if (image == null) {
			return;
		}
		Graphics2D g2 = image.createGraphics();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		drawVersionNumber(g2);
		g2.dispose();

		JWindow window = new JWindow(screen);
		window.setContentPane(new JLabel(new ImageIcon(image)));
		window.pack();
		WindowLocationUtil.centerOnScreen(window, screen);
		s.close();
		window.setVisible(true);
		moved = window;

		Toolkit.getDefaultToolkit().addAWTEventListener(new AWTEventListener() {
			@Override
			public void eventDispatched(AWTEvent event) {
				if (event.getID() == WindowEvent.WINDOW_OPENED && event.getSource() != moved) {
					Toolkit.getDefaultToolkit().removeAWTEventListener(this);
					moved.dispose();
					moved = null;
				}
			}
		}, AWTEvent.WINDOW_EVENT_MASK);
	}

	/**
	 * @return whether a splash screen, the Java one or the one moved to the startup screen, is showing
	 */
	public static boolean isShowing() {
		return getSplashScreen() != null || (moved != null && moved.isShowing());
	}

	private static void drawVersionNumber(Graphics2D g2) {
		String text = "Version " + BuildProperties.getVersion();
		GlyphVector gv = VERSION_FONT.createGlyphVector(g2.getFontRenderContext(), text);
		
		Rectangle2D rect = gv.getVisualBounds();
		double width = rect.getWidth();
		
		g2.setColor(VERSION_COLOR);
		g2.drawGlyphVector(gv, (float) (VERSION_POSITION_X - width), VERSION_POSITION_Y);
		
	}
	
	
	/**
	 * Return the current splash screen or <code>null</code> if not available or already closed.
	 * This method catches the possible exceptions and returns null if they occur.
	 * 
	 * @return	the current (visible) splash screen, or <code>null</code>.
	 */
	public static SplashScreen getSplashScreen() {
		try {
			SplashScreen splash = SplashScreen.getSplashScreen();
			if (splash != null && splash.isVisible()) {
				return splash;
			} else {
				return null;
			}
		} catch (RuntimeException e) {
			return null;
		}
	}
	
	
	
}
