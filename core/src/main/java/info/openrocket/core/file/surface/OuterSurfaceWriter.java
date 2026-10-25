package info.openrocket.core.file.surface;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes an {@link OuterSurfaceMesh} as STL, STEP or 3MF.
 */
public final class OuterSurfaceWriter {

	public enum Format {
		/** ASCII STL, one solid per component, as OpenFOAM and other CFD tools read it; STL has no units */
		STL("stl", LengthUnit.METER),
		/** STEP AP214 faceted boundary representation */
		STEP("step", LengthUnit.MILLIMETER),
		/** 3D Manufacturing Format */
		THREE_MF("3mf", LengthUnit.MILLIMETER);

		private final String extension;
		private final LengthUnit defaultUnit;

		Format(String extension, LengthUnit defaultUnit) {
			this.extension = extension;
			this.defaultUnit = defaultUnit;
		}

		public String getExtension() {
			return extension;
		}

		/** @return the usual unit of the format: meters for STL, as OpenFOAM expects, millimeters otherwise */
		public LengthUnit getDefaultUnit() {
			return defaultUnit;
		}
	}

	/**
	 * The length unit of the coordinates in the file. STEP and 3MF also declare it in the file.
	 */
	public enum LengthUnit {
		METER("m", 1, "meter"),
		CENTIMETER("cm", 100, "centimeter"),
		MILLIMETER("mm", 1000, "millimeter"),
		INCH("in", 1 / 0.0254, "inch");

		private final String symbol;
		private final double perMeter;
		private final String threeMfName;

		LengthUnit(String symbol, double perMeter, String threeMfName) {
			this.symbol = symbol;
			this.perMeter = perMeter;
			this.threeMfName = threeMfName;
		}

		public String getSymbol() {
			return symbol;
		}

		/** @return how many of this unit make a meter */
		public double getPerMeter() {
			return perMeter;
		}
	}

	private OuterSurfaceWriter() {
	}

	/**
	 * Writes the mesh in the format's usual unit at full scale.
	 *
	 * @see #write(OuterSurfaceMesh, Format, String, LengthUnit, double, OutputStream)
	 */
	public static void write(OuterSurfaceMesh mesh, Format format, String name, OutputStream out) throws IOException {
		write(mesh, format, name, format.getDefaultUnit(), 1, out);
	}

	/**
	 * Writes the mesh.
	 *
	 * @param mesh the mesh
	 * @param format the file format
	 * @param name the name stored in the file, e.g. the rocket name
	 * @param unit the length unit of the coordinates
	 * @param scale a factor applied to the size of the rocket, e.g. 0.1 for a model at 1:10
	 * @param out the stream to write to; it is not closed
	 */
	public static void write(OuterSurfaceMesh mesh, Format format, String name, LengthUnit unit, double scale,
							 OutputStream out) throws IOException {
		final double factor = unit.getPerMeter() * scale;
		switch (format) {
			case STL -> writeStl(mesh, factor, out);
			case STEP -> writeStep(mesh, name, unit, factor, out);
			case THREE_MF -> write3mf(mesh, name, unit, factor, out);
		}
	}

	private static void writeStl(OuterSurfaceMesh mesh, double factor, OutputStream out) throws IOException {
		Map<String, List<OuterSurfaceMesh.Face>> regions = new LinkedHashMap<>();
		for (OuterSurfaceMesh.Face face : mesh.getFaces()) {
			regions.computeIfAbsent(face.region(), k -> new ArrayList<>()).add(face);
		}
		// Region names become OpenFOAM patch names, so they must be single words
		Map<String, Integer> used = new LinkedHashMap<>();
		Writer w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.US_ASCII));
		for (Map.Entry<String, List<OuterSurfaceMesh.Face>> region : regions.entrySet()) {
			String solid = region.getKey().replaceAll("[^A-Za-z0-9_]+", "_");
			if (solid.isEmpty()) {
				solid = "part";
			}
			int count = used.merge(solid, 1, Integer::sum);
			if (count > 1) {
				solid = solid + "_" + count;
			}
			w.write("solid " + solid + "\n");
			for (OuterSurfaceMesh.Face face : region.getValue()) {
				int[] t = face.triangles();
				for (int i = 0; i < t.length; i += 3) {
					double[] n = normal(mesh, new int[]{t[i], t[i + 1], t[i + 2]});
					w.write("  facet normal " + number(n[0]) + " " + number(n[1]) + " " + number(n[2]) + "\n");
					w.write("    outer loop\n");
					for (int k = 0; k < 3; k++) {
						double[] p = mesh.getVertices().get(t[i + k]);
						w.write("      vertex " + number(p[0] * factor) + " " + number(p[1] * factor) + " "
								+ number(p[2] * factor) + "\n");
					}
					w.write("    endloop\n");
					w.write("  endfacet\n");
				}
			}
			w.write("endsolid " + solid + "\n");
		}
		w.flush();
	}

	private static void write3mf(OuterSurfaceMesh mesh, String name, LengthUnit unit, double factor,
								 OutputStream out) throws IOException {
		ZipOutputStream zip = new ZipOutputStream(out);
		Writer w = new OutputStreamWriter(zip, StandardCharsets.UTF_8);

		zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
		w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
				+ "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">\n"
				+ "  <Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>\n"
				+ "  <Default Extension=\"model\" ContentType=\"application/vnd.ms-package.3dmanufacturing-3dmodel+xml\"/>\n"
				+ "</Types>\n");
		w.flush();
		zip.closeEntry();

		zip.putNextEntry(new ZipEntry("_rels/.rels"));
		w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
				+ "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n"
				+ "  <Relationship Target=\"/3D/3dmodel.model\" Id=\"rel0\" "
				+ "Type=\"http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel\"/>\n"
				+ "</Relationships>\n");
		w.flush();
		zip.closeEntry();

		zip.putNextEntry(new ZipEntry("3D/3dmodel.model"));
		w = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
		w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
				+ "<model unit=\"" + unit.threeMfName + "\" xml:lang=\"en-US\" "
				+ "xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\">\n"
				+ "  <metadata name=\"Application\">OpenRocket</metadata>\n"
				+ "  <resources>\n"
				+ "    <object id=\"1\" type=\"model\" name=\"" + xml(name) + "\">\n"
				+ "      <mesh>\n"
				+ "        <vertices>\n");
		for (double[] p : mesh.getVertices()) {
			w.write("          <vertex x=\"" + number(p[0] * factor) + "\" y=\"" + number(p[1] * factor)
					+ "\" z=\"" + number(p[2] * factor) + "\"/>\n");
		}
		w.write("        </vertices>\n"
				+ "        <triangles>\n");
		for (OuterSurfaceMesh.Face face : mesh.getFaces()) {
			int[] t = face.triangles();
			for (int i = 0; i < t.length; i += 3) {
				w.write("          <triangle v1=\"" + t[i] + "\" v2=\"" + t[i + 1] + "\" v3=\"" + t[i + 2] + "\"/>\n");
			}
		}
		w.write("        </triangles>\n"
				+ "      </mesh>\n"
				+ "    </object>\n"
				+ "  </resources>\n"
				+ "  <build>\n"
				+ "    <item objectid=\"1\"/>\n"
				+ "  </build>\n"
				+ "</model>\n");
		w.flush();
		zip.closeEntry();
		zip.finish();
	}

	private static void writeStep(OuterSurfaceMesh mesh, String name, LengthUnit unit, double factor,
								  OutputStream out) throws IOException {
		Writer w = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.US_ASCII));
		String label = step(name);
		String time = LocalDateTime.now().withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
		w.write("ISO-10303-21;\n"
				+ "HEADER;\n"
				+ "FILE_DESCRIPTION(('OpenRocket outer surface'),'2;1');\n"
				+ "FILE_NAME('" + label + "','" + time + "',(''),(''),'OpenRocket','OpenRocket','');\n"
				+ "FILE_SCHEMA(('AUTOMOTIVE_DESIGN { 1 0 10303 214 1 1 1 1 }'));\n"
				+ "ENDSEC;\n"
				+ "DATA;\n"
				+ "#1=APPLICATION_CONTEXT('automotive design');\n"
				+ "#2=APPLICATION_PROTOCOL_DEFINITION('international standard','automotive_design',2000,#1);\n"
				+ "#3=PRODUCT_CONTEXT('',#1,'mechanical');\n"
				+ "#4=PRODUCT('" + label + "','" + label + "','',(#3));\n"
				+ "#5=PRODUCT_DEFINITION_FORMATION('','',#4);\n"
				+ "#6=PRODUCT_DEFINITION_CONTEXT('part definition',#1,'design');\n"
				+ "#7=PRODUCT_DEFINITION('design','',#5,#6);\n"
				+ "#8=PRODUCT_DEFINITION_SHAPE('','',#7);\n"
				+ stepLengthUnit(unit)
				+ "#10=(NAMED_UNIT(*)PLANE_ANGLE_UNIT()SI_UNIT($,.RADIAN.));\n"
				+ "#11=(NAMED_UNIT(*)SI_UNIT($,.STERADIAN.)SOLID_ANGLE_UNIT());\n"
				+ "#12=UNCERTAINTY_MEASURE_WITH_UNIT(LENGTH_MEASURE(1.E-06),#9,'distance_accuracy_value','');\n"
				+ "#13=(GEOMETRIC_REPRESENTATION_CONTEXT(3)GLOBAL_UNCERTAINTY_ASSIGNED_CONTEXT((#12))"
				+ "GLOBAL_UNIT_ASSIGNED_CONTEXT((#9,#10,#11))REPRESENTATION_CONTEXT('',''));\n"
				+ "#14=CARTESIAN_POINT('',(0.,0.,0.));\n"
				+ "#15=AXIS2_PLACEMENT_3D('',#14,$,$);\n"
				+ "#16=PRODUCT_RELATED_PRODUCT_CATEGORY('part',$,(#4));\n");

		final int firstPoint = 20;
		List<double[]> vertices = mesh.getVertices();
		for (int i = 0; i < vertices.size(); i++) {
			double[] p = vertices.get(i);
			w.write("#" + (firstPoint + i) + "=CARTESIAN_POINT('',(" + stepNumber(p[0] * factor) + ","
					+ stepNumber(p[1] * factor) + "," + stepNumber(p[2] * factor) + "));\n");
		}

		int id = firstPoint + vertices.size();
		StringBuilder shellFaces = new StringBuilder();
		for (OuterSurfaceMesh.Face face : mesh.getFaces()) {
			int[] loop = face.loop();
			StringBuilder points = new StringBuilder();
			for (int i = 0; i < loop.length; i++) {
				points.append(i == 0 ? "#" : ",#").append(firstPoint + loop[i]);
			}
			double[] n = normal(mesh, loop);
			int loopId = id++;
			int boundId = id++;
			int directionId = id++;
			int placementId = id++;
			int planeId = id++;
			int faceId = id++;
			w.write("#" + loopId + "=POLY_LOOP('',(" + points + "));\n");
			w.write("#" + boundId + "=FACE_OUTER_BOUND('',#" + loopId + ",.T.);\n");
			w.write("#" + directionId + "=DIRECTION('',(" + stepNumber(n[0]) + "," + stepNumber(n[1]) + ","
					+ stepNumber(n[2]) + "));\n");
			w.write("#" + placementId + "=AXIS2_PLACEMENT_3D('',#" + (firstPoint + loop[0]) + ",#" + directionId + ",$);\n");
			w.write("#" + planeId + "=PLANE('',#" + placementId + ");\n");
			w.write("#" + faceId + "=FACE_SURFACE('',(#" + boundId + "),#" + planeId + ",.T.);\n");
			shellFaces.append(shellFaces.length() == 0 ? "#" : ",#").append(faceId);
		}
		int shellId = id++;
		int brepId = id++;
		int representationId = id++;
		w.write("#" + shellId + "=CLOSED_SHELL('',(" + shellFaces + "));\n");
		w.write("#" + brepId + "=FACETED_BREP('" + label + "',#" + shellId + ");\n");
		w.write("#" + representationId + "=FACETED_BREP_SHAPE_REPRESENTATION('',(#15,#" + brepId + "),#13);\n");
		w.write("#" + id + "=SHAPE_DEFINITION_REPRESENTATION(#8,#" + representationId + ");\n");
		w.write("ENDSEC;\n"
				+ "END-ISO-10303-21;\n");
		w.flush();
	}

	/**
	 * The length unit as entity #9; an inch is a conversion based unit of 25.4 mm defined in entities #17 to #19.
	 */
	private static String stepLengthUnit(LengthUnit unit) {
		return switch (unit) {
			case METER -> "#9=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT($,.METRE.));\n";
			case CENTIMETER -> "#9=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.CENTI.,.METRE.));\n";
			case MILLIMETER -> "#9=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.));\n";
			case INCH -> "#17=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.));\n"
					+ "#18=DIMENSIONAL_EXPONENTS(1.,0.,0.,0.,0.,0.,0.);\n"
					+ "#19=LENGTH_MEASURE_WITH_UNIT(LENGTH_MEASURE(25.4),#17);\n"
					+ "#9=(CONVERSION_BASED_UNIT('INCH',#19)LENGTH_UNIT()NAMED_UNIT(#18));\n";
		};
	}

	/** Unit normal of a planar polygon by Newell's method */
	static double[] normal(OuterSurfaceMesh mesh, int[] loop) {
		double nx = 0;
		double ny = 0;
		double nz = 0;
		for (int i = 0; i < loop.length; i++) {
			double[] a = mesh.getVertices().get(loop[i]);
			double[] b = mesh.getVertices().get(loop[(i + 1) % loop.length]);
			nx += (a[1] - b[1]) * (a[2] + b[2]);
			ny += (a[2] - b[2]) * (a[0] + b[0]);
			nz += (a[0] - b[0]) * (a[1] + b[1]);
		}
		double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
		return length == 0 ? new double[]{1, 0, 0} : new double[]{nx / length, ny / length, nz / length};
	}

	private static String number(double value) {
		return String.format(Locale.ROOT, "%.9g", value);
	}

	/** STEP reals need a decimal point */
	private static String stepNumber(double value) {
		return String.format(Locale.ROOT, "%.9E", value);
	}

	private static String xml(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	/** STEP strings are ASCII with quotes doubled */
	private static String step(String text) {
		return text.replaceAll("[^\\x20-\\x7E]", "_").replace("\\", "\\\\").replace("'", "''");
	}
}
