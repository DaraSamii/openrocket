package info.openrocket.swing.gui.choosers;

import java.awt.Component;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

import info.openrocket.core.file.surface.OuterSurfaceMesh;
import info.openrocket.core.file.surface.OuterSurfaceWriter;
import info.openrocket.core.l10n.Translator;
import info.openrocket.core.preferences.ApplicationPreferences;
import info.openrocket.core.startup.Application;
import net.miginfocom.swing.MigLayout;

/**
 * Options for exporting the outer surface: how finely it is divided, the length unit and scale of the file, and
 * whether rail buttons are included. The choices are remembered in the preferences.
 */
public class OuterSurfaceOptionChooser extends JPanel {
	private static final Translator trans = Application.getTranslator();

	private static final String PREF_DETAIL = "OuterSurfaceExport.detail";
	private static final String PREF_UNIT = "OuterSurfaceExport.unit.";
	private static final String PREF_SCALE = "OuterSurfaceExport.scale";
	private static final String PREF_RAIL_BUTTONS = "OuterSurfaceExport.railButtons";

	private final OuterSurfaceWriter.Format format;
	private final JComboBox<OuterSurfaceMesh.Detail> detail;
	private final JComboBox<OuterSurfaceWriter.LengthUnit> unit;
	private final SpinnerNumberModel scale;
	private final JCheckBox railButtons;

	/**
	 * @param format the file format, whose usual unit is the default
	 */
	public OuterSurfaceOptionChooser(OuterSurfaceWriter.Format format) {
		super(new MigLayout("", "[][grow]"));
		this.format = format;
		ApplicationPreferences prefs = Application.getPreferences();

		//// Detail
		add(new JLabel(trans.get("OuterSurfaceOptionChooser.lbl.Detail")));
		detail = new JComboBox<>(OuterSurfaceMesh.Detail.values());
		detail.setSelectedItem(valueOf(OuterSurfaceMesh.Detail.class,
				prefs.getString(PREF_DETAIL, null), OuterSurfaceMesh.Detail.NORMAL));
		detail.setRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
														  boolean focus) {
				OuterSurfaceMesh.Detail d = (OuterSurfaceMesh.Detail) value;
				String text = d == null ? "" : String.format(trans.get("OuterSurfaceOptionChooser.detail." + d.name()),
						d.getAngularDivisions());
				return super.getListCellRendererComponent(list, text, index, selected, focus);
			}
		});
		detail.setToolTipText(trans.get("OuterSurfaceOptionChooser.lbl.Detail.ttip"));
		add(detail, "growx, wrap");

		//// Unit
		add(new JLabel(trans.get("OuterSurfaceOptionChooser.lbl.Unit")));
		unit = new JComboBox<>(OuterSurfaceWriter.LengthUnit.values());
		unit.setSelectedItem(valueOf(OuterSurfaceWriter.LengthUnit.class,
				prefs.getString(PREF_UNIT + format.name(), null), format.getDefaultUnit()));
		unit.setRenderer(new DefaultListCellRenderer() {
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
														  boolean focus) {
				OuterSurfaceWriter.LengthUnit u = (OuterSurfaceWriter.LengthUnit) value;
				String text = u == null ? "" : trans.get("OuterSurfaceOptionChooser.unit." + u.name());
				return super.getListCellRendererComponent(list, text, index, selected, focus);
			}
		});
		unit.setToolTipText(trans.get(format == OuterSurfaceWriter.Format.STL
				? "OuterSurfaceOptionChooser.lbl.Unit.ttip.STL" : "OuterSurfaceOptionChooser.lbl.Unit.ttip"));
		add(unit, "growx, wrap");

		//// Scale
		add(new JLabel(trans.get("OuterSurfaceOptionChooser.lbl.Scale")));
		double savedScale = prefs.getDouble(PREF_SCALE, 1);
		scale = new SpinnerNumberModel(savedScale > 0 ? savedScale : 1, 0.001, 1000, 0.1);
		JSpinner scaleSpinner = new JSpinner(scale);
		scaleSpinner.setEditor(new JSpinner.NumberEditor(scaleSpinner, "0.###"));
		scaleSpinner.setToolTipText(trans.get("OuterSurfaceOptionChooser.lbl.Scale.ttip"));
		add(scaleSpinner, "growx, wrap");

		//// Rail buttons
		railButtons = new JCheckBox(trans.get("OuterSurfaceOptionChooser.checkbox.RailButtons"));
		railButtons.setSelected(prefs.getBoolean(PREF_RAIL_BUTTONS, true));
		railButtons.setToolTipText(trans.get("OuterSurfaceOptionChooser.checkbox.RailButtons.ttip"));
		add(railButtons, "span, wrap");
	}

	private static <E extends Enum<E>> E valueOf(Class<E> type, String name, E fallback) {
		try {
			return name == null ? fallback : Enum.valueOf(type, name);
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}

	/** @return the mesh options chosen */
	public OuterSurfaceMesh.Options getMeshOptions() {
		return new OuterSurfaceMesh.Options((OuterSurfaceMesh.Detail) detail.getSelectedItem(), railButtons.isSelected());
	}

	/** @return the length unit chosen */
	public OuterSurfaceWriter.LengthUnit getUnit() {
		return (OuterSurfaceWriter.LengthUnit) unit.getSelectedItem();
	}

	/** @return the scale factor chosen */
	public double getScale() {
		return scale.getNumber().doubleValue();
	}

	/** Remembers the choices for the next export */
	public void storePreferences() {
		ApplicationPreferences prefs = Application.getPreferences();
		prefs.putString(PREF_DETAIL, ((OuterSurfaceMesh.Detail) detail.getSelectedItem()).name());
		prefs.putString(PREF_UNIT + format.name(), getUnit().name());
		prefs.putDouble(PREF_SCALE, getScale());
		prefs.putBoolean(PREF_RAIL_BUTTONS, railButtons.isSelected());
	}
}
