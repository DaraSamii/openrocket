package info.openrocket.swing.gui.configdialog;

import java.awt.BasicStroke;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;

import javax.swing.ButtonGroup;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

import info.openrocket.core.l10n.Translator;
import info.openrocket.core.rocketcomponent.FinShapeTools;
import info.openrocket.core.rocketcomponent.FinShapeTools.EdgeCurve;
import info.openrocket.core.rocketcomponent.FinShapeTools.FinShapeException;
import info.openrocket.core.startup.Application;
import info.openrocket.core.unit.UnitGroup;
import info.openrocket.core.util.CoordinateIF;
import info.openrocket.swing.gui.SpinnerEditor;
import info.openrocket.swing.gui.adaptors.DoubleModel;
import info.openrocket.swing.gui.components.UnitSelector;
import info.openrocket.swing.gui.theme.UITheme;
import info.openrocket.swing.gui.util.GUIUtil;
import net.miginfocom.swing.MigLayout;

/**
 * Asks for the settings of a free-form fin shape tool, shows the new outline over the old one, and gives the new fin
 * points.
 */
class FinShapeToolDialog extends JDialog {
	private static final Translator trans = Application.getTranslator();

	/** The shape tools */
	enum Tool {
		ROUND_CORNER,
		CHAMFER_CORNER,
		CURVE_EDGE,
		SMOOTH_OUTLINE
	}

	private final Tool tool;
	private final List<CoordinateIF> original;
	private final int index;
	private final JLabel problemLabel = new JLabel(" ");
	private final JButton okButton = new JButton(trans.get("dlg.but.ok"));
	private final Preview preview = new Preview();

	private DoubleModel size;
	private final JSpinner segments = new JSpinner(new SpinnerNumberModel(FinShapeTools.DEFAULT_SEGMENTS, 2, 200, 1));
	private JComboBox<EdgeCurve> curveCombo;
	private DoubleModel[] control;
	private JRadioButton convex;
	private List<CoordinateIF> result;

	/**
	 * @param points the fin points
	 * @param index the corner point for the corner tools; the end point of the edge (edge from index - 1) for the
	 *              edge tool; unused for smoothing
	 */
	FinShapeToolDialog(Window owner, Tool tool, List<CoordinateIF> points, int index) {
		super(owner, trans.get("FinShapeToolDlg.title." + tool.name()), ModalityType.APPLICATION_MODAL);
		this.tool = tool;
		this.original = List.copyOf(points);
		this.index = index;

		JPanel panel = new JPanel(new MigLayout("fill", "[][grow, fill][]", ""));
		switch (tool) {
			case ROUND_CORNER -> addSizeRow(panel, "FinShapeToolDlg.lbl.radius", maxOrZero(true), 0.25, false);
			case CHAMFER_CORNER -> addSizeRow(panel, "FinShapeToolDlg.lbl.distance", maxOrZero(false), 0.25, false);
			case CURVE_EDGE -> addCurveRows(panel);
			case SMOOTH_OUTLINE -> {
			}
		}
		if (tool != Tool.CHAMFER_CORNER) {
			JLabel segmentsLabel = new JLabel(trans.get(tool == Tool.SMOOTH_OUTLINE
					? "FinShapeToolDlg.lbl.segmentsBetweenPoints" : "FinShapeToolDlg.lbl.segments"));
			segmentsLabel.setToolTipText(trans.get("FinShapeToolDlg.lbl.segments.ttip"));
			panel.add(segmentsLabel);
			panel.add(segments, "wrap");
			segments.addChangeListener(e -> refresh());
		}
		panel.add(preview, "span, grow, push, w 360lp, h 240lp, wrap");
		problemLabel.setForeground(UITheme.getColor(UITheme.Keys.ERROR, Color.RED));
		panel.add(problemLabel, "span, growx, wrap");

		JButton cancelButton = new JButton(trans.get("dlg.but.cancel"));
		cancelButton.addActionListener(e -> {
			result = null;
			dispose();
		});
		okButton.addActionListener(e -> dispose());
		panel.add(okButton, "span, split 2, right, tag ok");
		panel.add(cancelButton, "tag cancel");

		setContentPane(panel);
		GUIUtil.setDisposableDialogOptions(this, okButton);
		refresh();
	}

	/**
	 * @return the new fin points, or null if the dialog was cancelled
	 */
	List<CoordinateIF> getResult() {
		return result;
	}

	private double maxOrZero(boolean radius) {
		try {
			return radius ? FinShapeTools.maxCornerRadius(original, index)
					: FinShapeTools.maxChamferDistance(original, index);
		} catch (FinShapeException e) {
			return 0;
		}
	}

	/**
	 * Adds a length with its unit and the largest allowed value.
	 *
	 * @param fraction the starting value, as a part of the largest value
	 * @param signed whether negative values are allowed
	 */
	private void addSizeRow(JPanel panel, String labelKey, double max, double fraction, boolean signed) {
		size = new DoubleModel(max * fraction, UnitGroup.UNITS_LENGTH, signed ? -max : 0, max);
		JLabel label = new JLabel(trans.get(labelKey));
		label.setToolTipText(trans.get(labelKey + ".ttip"));
		panel.add(label);
		panel.add(spinner(size));
		panel.add(new UnitSelector(size), "wrap");
		panel.add(new JLabel(String.format(trans.get("FinShapeToolDlg.lbl.max"),
				UnitGroup.UNITS_LENGTH.getDefaultUnit().toStringUnit(max))), "skip 1, span, wrap");
	}

	private JSpinner spinner(DoubleModel model) {
		JSpinner spinner = new JSpinner(model.getSpinnerModel());
		spinner.setEditor(new SpinnerEditor(spinner));
		model.addChangeListener(e -> refresh());
		return spinner;
	}

	private void addCurveRows(JPanel panel) {
		curveCombo = new JComboBox<>(EdgeCurve.values());
		curveCombo.setRenderer(new DefaultListCellRenderer() {
			@Override
			public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int i,
					boolean isSelected, boolean cellHasFocus) {
				super.getListCellRendererComponent(list, value, i, isSelected, cellHasFocus);
				if (value instanceof EdgeCurve curve) {
					setText(trans.get("FinShapeToolDlg.curve." + curve.name()));
				}
				return this;
			}
		});
		panel.add(new JLabel(trans.get("FinShapeToolDlg.lbl.curve")));
		panel.add(curveCombo, "span, wrap");

		CardLayout cards = new CardLayout();
		JPanel cardPanel = new JPanel(cards);

		// Circular arc: bulge
		JPanel arc = new JPanel(new MigLayout("ins 0, fillx", "[][grow, fill][]"));
		double maxBulge;
		try {
			maxBulge = FinShapeTools.maxArcBulge(original, index);
		} catch (FinShapeException e) {
			maxBulge = 0;
		}
		addSizeRow(arc, "FinShapeToolDlg.lbl.bulge", maxBulge, 0.25, true);
		cardPanel.add(arc, EdgeCurve.ARC.name());

		// Bézier curves: control points
		double[][] defaults;
		try {
			defaults = FinShapeTools.defaultControlPoints(original, index);
		} catch (FinShapeException e) {
			defaults = new double[][]{{0, 0}, {0, 0}};
		}
		control = new DoubleModel[4];
		for (int i = 0; i < 4; i++) {
			control[i] = new DoubleModel(defaults[i / 2][i % 2], UnitGroup.UNITS_LENGTH);
		}
		cardPanel.add(controlPointPanel(1), EdgeCurve.QUADRATIC_BEZIER.name());
		cardPanel.add(controlPointPanel(2), EdgeCurve.CUBIC_BEZIER.name());

		// Quarter ellipse: outwards or inwards
		JPanel ellipse = new JPanel(new MigLayout("ins 0"));
		convex = new JRadioButton(trans.get("FinShapeToolDlg.lbl.convex"), true);
		JRadioButton concave = new JRadioButton(trans.get("FinShapeToolDlg.lbl.concave"));
		ButtonGroup group = new ButtonGroup();
		group.add(convex);
		group.add(concave);
		convex.addActionListener(e -> refresh());
		concave.addActionListener(e -> refresh());
		ellipse.add(convex);
		ellipse.add(concave);
		cardPanel.add(ellipse, EdgeCurve.QUARTER_ELLIPSE.name());

		curveCombo.addActionListener(e -> {
			cards.show(cardPanel, ((EdgeCurve) curveCombo.getSelectedItem()).name());
			refresh();
		});
		panel.add(cardPanel, "span, growx, wrap");
	}

	private JComponent controlPointPanel(int count) {
		JPanel panel = new JPanel(new MigLayout("ins 0, fillx", "[][grow, fill][]para[][grow, fill][]"));
		for (int point = 0; point < count; point++) {
			panel.add(new JLabel(String.format(trans.get("FinShapeToolDlg.lbl.controlX"), point + 1)));
			panel.add(spinner(control[2 * point]));
			panel.add(new UnitSelector(control[2 * point]));
			panel.add(new JLabel(String.format(trans.get("FinShapeToolDlg.lbl.controlY"), point + 1)));
			panel.add(spinner(control[2 * point + 1]));
			panel.add(new UnitSelector(control[2 * point + 1]), "wrap");
		}
		panel.setToolTipText(trans.get("FinShapeToolDlg.lbl.control.ttip"));
		return panel;
	}

	private void refresh() {
		int n = ((Number) segments.getValue()).intValue();
		try {
			result = switch (tool) {
				case ROUND_CORNER -> FinShapeTools.roundCorner(original, index, size.getValue(), n);
				case CHAMFER_CORNER -> FinShapeTools.chamferCorner(original, index, size.getValue());
				case SMOOTH_OUTLINE -> FinShapeTools.smoothOutline(original, n);
				case CURVE_EDGE -> switch ((EdgeCurve) curveCombo.getSelectedItem()) {
					case ARC -> FinShapeTools.arcEdge(original, index, size.getValue(), n);
					case QUADRATIC_BEZIER -> FinShapeTools.bezierEdge(original, index, controlPoint(0), null, n);
					case CUBIC_BEZIER -> FinShapeTools.bezierEdge(original, index, controlPoint(0), controlPoint(1), n);
					case QUARTER_ELLIPSE -> FinShapeTools.quarterEllipseEdge(original, index, convex.isSelected(), n);
				};
			};
			problemLabel.setText(" ");
		} catch (FinShapeException e) {
			result = null;
			String text = trans.get("FinShapeToolDlg.problem." + e.getProblem().name());
			if (!Double.isNaN(e.getLimit())) {
				text = String.format(text, UnitGroup.UNITS_LENGTH.getDefaultUnit().toStringUnit(e.getLimit()));
			}
			problemLabel.setText(text);
		}
		okButton.setEnabled(result != null);
		preview.repaint();
	}

	private double[] controlPoint(int point) {
		return new double[]{control[2 * point].getValue(), control[2 * point + 1].getValue()};
	}

	/**
	 * Draws the old outline dashed and the new one solid, with the root and, for Bézier curves, the control points.
	 */
	private class Preview extends JComponent {
		private static final int MARGIN = 12;

		@Override
		public Dimension getPreferredSize() {
			return new Dimension(360, 240);
		}

		@Override
		protected void paintComponent(Graphics graphics) {
			Graphics2D g = (Graphics2D) graphics.create();
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setColor(UITheme.getColor(UITheme.Keys.BACKGROUND, Color.WHITE));
			g.fillRect(0, 0, getWidth(), getHeight());

			List<double[]> shown = new ArrayList<>();
			for (CoordinateIF p : original) {
				shown.add(new double[]{p.getX(), p.getY()});
			}
			if (result != null) {
				for (CoordinateIF p : result) {
					shown.add(new double[]{p.getX(), p.getY()});
				}
			}
			boolean bezier = tool == Tool.CURVE_EDGE && curveCombo.getSelectedItem() != EdgeCurve.ARC
					&& curveCombo.getSelectedItem() != EdgeCurve.QUARTER_ELLIPSE;
			int controls = curveCombo != null && curveCombo.getSelectedItem() == EdgeCurve.CUBIC_BEZIER ? 2 : 1;
			if (bezier) {
				for (int i = 0; i < controls; i++) {
					shown.add(controlPoint(i));
				}
			}
			double minX = Double.MAX_VALUE;
			double maxX = -Double.MAX_VALUE;
			double minY = 0;
			double maxY = -Double.MAX_VALUE;
			for (double[] p : shown) {
				minX = Math.min(minX, p[0]);
				maxX = Math.max(maxX, p[0]);
				minY = Math.min(minY, p[1]);
				maxY = Math.max(maxY, p[1]);
			}
			double scale = Math.min((getWidth() - 2 * MARGIN) / Math.max(maxX - minX, 1e-6),
					(getHeight() - 2 * MARGIN) / Math.max(maxY - minY, 1e-6));
			double left = MARGIN + (getWidth() - 2 * MARGIN - scale * (maxX - minX)) / 2 - scale * minX;
			double bottom = getHeight() - MARGIN - (getHeight() - 2 * MARGIN - scale * (maxY - minY)) / 2 + scale * minY;

			Color text = UITheme.getColor(UITheme.Keys.TEXT, Color.BLACK);
			g.setColor(UITheme.getColor(UITheme.Keys.TEXT_DIM, Color.GRAY));
			g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{5, 4}, 0));
			g.draw(outline(original, scale, left, bottom));
			if (result != null) {
				g.setColor(UITheme.getColor(UITheme.Keys.INFO, Color.BLUE));
				g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g.draw(outline(result, scale, left, bottom));
			}
			if (bezier) {
				g.setColor(text);
				for (int i = 0; i < controls; i++) {
					double[] c = controlPoint(i);
					g.fill(new Ellipse2D.Double(left + scale * c[0] - 3, bottom - scale * c[1] - 3, 6, 6));
				}
			}
			g.dispose();
		}

		private Path2D outline(List<CoordinateIF> points, double scale, double left, double bottom) {
			Path2D.Double path = new Path2D.Double();
			for (int i = 0; i < points.size(); i++) {
				double x = left + scale * points.get(i).getX();
				double y = bottom - scale * points.get(i).getY();
				if (i == 0) {
					path.moveTo(x, y);
				} else {
					path.lineTo(x, y);
				}
			}
			path.closePath();	// the root
			return path;
		}
	}
}
