package info.openrocket.swing.gui.util;

import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.Insets;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;

/**
 * Helper class for setting the location of a Swing window. E.g. to check when the window is outside the screen and
 * recenter it if so.
 *
 * @author Sibo Van Gool <sibo.vangool@hotmail.com>
 */
public abstract class WindowLocationUtil {
    /**
     * Sets the location of the window, but don't go outside the screen.
     * @param window the window to move
     * @param x the target x position on the screen
     * @param y the target y position on the screen
     */
    public static void setLocationWithinScreen(Window window, int x, int y) {
        java.awt.Dimension screenSize = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
        java.awt.Dimension windowSize = window.getSize();
        int screenWidth = screenSize.width;
        int screenHeight = screenSize.height;
        int windowWidth = windowSize.width;
        int windowHeight = windowSize.height;
        int xPos = x;
        int yPos = y;
        if (xPos + windowWidth > screenWidth) {
            xPos = screenWidth - windowWidth;
        }
        if (yPos + windowHeight > screenHeight) {
            yPos = screenHeight - windowHeight;
        }
        window.setLocation(xPos, yPos);
    }

    /**
     * Moves the window to the center of the screen if its location is outside the boundary of the screen.
     * @param window window to move
     */
    public static void moveIfOutsideOfMonitor(Window window) {
        GraphicsDevice currentDevice = window.getGraphicsConfiguration().getDevice();
        if (currentDevice != null && window.isVisible() &&
                !currentDevice.getDefaultConfiguration().getBounds().contains(window.getLocationOnScreen())) {
            int width = currentDevice.getDefaultConfiguration().getBounds().width;
            int height = currentDevice.getDefaultConfiguration().getBounds().height;
            window.setLocation(
                    ((width / 2) - (window.getSize().width / 2)) + currentDevice.getDefaultConfiguration().getBounds().x,
                    ((height / 2) - (window.getSize().height / 2)) + currentDevice.getDefaultConfiguration().getBounds().y
            );
        }
    }

    /**
     * Moves the window to the center of the screen if it is on another monitor as the parent window, or if its location
     * is outside the boundary of the parent's monitor screen.
     * @param window window to move
     * @param parent parent window
     */
    public static void moveIfOutsideOfParentMonitor(Window window, Window parent) {
        GraphicsDevice parentDevice = parent.getGraphicsConfiguration().getDevice();
        GraphicsDevice currentDevice = window.getGraphicsConfiguration().getDevice();
        if (parentDevice != null && (currentDevice == null || currentDevice != parentDevice ||
                (window.isVisible() && !parentDevice.getDefaultConfiguration().getBounds().contains(
                        window.getLocationOnScreen())))) {
            int width = parentDevice.getDefaultConfiguration().getBounds().width;
            int height = parentDevice.getDefaultConfiguration().getBounds().height;
            window.setLocation(
                    ((width / 2) - (window.getSize().width / 2)) + parentDevice.getDefaultConfiguration().getBounds().x,
                    ((height / 2) - (window.getSize().height / 2)) + parentDevice.getDefaultConfiguration().getBounds().y
            );
        }
    }

    /**
     * Centers a window on the main OpenRocket window it belongs to, inside the screen of that main window, so that new
     * windows always open in the same place, also with several screens. Call it after the window has its final size
     * and before it is shown. Without a main window, the window is centered on the screen.
     * @param window window to place
     */
    public static void centerOnMainWindow(Window window) {
        Window main = getMainWindow(window);
        if (main == null) {
            window.setLocationRelativeTo(null);
            return;
        }
        GraphicsConfiguration configuration = main.getGraphicsConfiguration();
        Rectangle screen = configuration.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        Rectangle usable = new Rectangle(screen.x + insets.left, screen.y + insets.top,
                screen.width - insets.left - insets.right, screen.height - insets.top - insets.bottom);
        window.setLocationByPlatform(false);
        window.setLocation(centeredLocation(main.getBounds(), window.getSize(), usable));
    }

    /**
     * @param main bounds of the main window
     * @param size size of the window to place
     * @param screen usable area of the main window's screen
     * @return the location that centers the window on the main window, moved inside the screen where needed
     */
    static Point centeredLocation(Rectangle main, Dimension size, Rectangle screen) {
        int x = main.x + (main.width - size.width) / 2;
        int y = main.y + (main.height - size.height) / 2;
        // A window larger than the screen keeps its top-left corner, with the title bar, on the screen
        x = Math.max(screen.x, Math.min(x, screen.x + screen.width - size.width));
        y = Math.max(screen.y, Math.min(y, screen.y + screen.height - size.height));
        return new Point(x, y);
    }

    /**
     * @return the top-level window of the window's owners, or else the top-level window of the active window, or else
     *         the first open frame; null if no such window is showing
     */
    private static Window getMainWindow(Window window) {
        Window owner = topLevel(window.getOwner());
        if (owner != null && owner.isShowing()) {
            return owner;
        }
        Window active = topLevel(KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow());
        if (active != null && active != window && active.isShowing()) {
            return active;
        }
        for (Frame frame : Frame.getFrames()) {
            if (frame != window && frame.isShowing()) {
                return frame;
            }
        }
        return null;
    }

    private static Window topLevel(Window window) {
        while (window != null && window.getOwner() != null) {
            window = window.getOwner();
        }
        return window;
    }
}
