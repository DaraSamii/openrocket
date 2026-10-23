package info.openrocket.swing.gui.simulation;

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
}
