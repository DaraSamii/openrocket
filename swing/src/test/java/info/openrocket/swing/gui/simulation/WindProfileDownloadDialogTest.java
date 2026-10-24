package info.openrocket.swing.gui.simulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Locale;
import java.util.ResourceBundle;

import org.junit.jupiter.api.Test;

import info.openrocket.core.models.wind.OpenMeteoWindProfile.Model;

public class WindProfileDownloadDialogTest {
	@Test
	public void testEveryWeatherModelHasALabel() {
		ResourceBundle bundle = ResourceBundle.getBundle("l10n.messages", Locale.ROOT);
		for (Model model : Model.values()) {
			assertFalse(bundle.getString("WindProfileDownloadDlg.model." + model.name()).isBlank(), model.name());
		}
	}

	@Test
	public void testPlacesAreShownWithTheirHemispheres() {
		assertEquals("32.9903\u00B0 N, 106.9750\u00B0 W", WindProfileDownloadDialog.formatPlace(32.9903, -106.975));
		assertEquals("45.5000\u00B0 S, 73.6000\u00B0 E", WindProfileDownloadDialog.formatPlace(-45.5, 73.6));
	}
}
