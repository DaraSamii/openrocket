package info.openrocket.core.file.surface;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Triangulates simple polygons for the outer surface.
 * <p>
 * The boundary of the polygon is never split, so a face meets its neighbors exactly. Interior points on a regular
 * grid can be added with a constrained Delaunay insertion, which gives triangles of about the grid size instead of
 * long slivers between boundary points.
 */
final class SurfaceTriangulator {

	/**
	 * A triangulated polygon.
	 *
	 * @param u first coordinates of the boundary points followed by the added interior points
	 * @param v second coordinates
	 * @param triangles triangles as indices into the points, counter-clockwise
	 */
	record Result(double[] u, double[] v, int[] triangles) {
	}

	private SurfaceTriangulator() {
	}

	/**
	 * Splits a simple polygon into triangles by ear clipping.
	 *
	 * @param u first coordinates of the polygon, counter-clockwise
	 * @param v second coordinates
	 * @return triangles as indices into the polygon
	 * @throws IllegalStateException if the polygon cannot be split, e.g. because it crosses itself
	 */
	static int[] triangulate(double[] u, double[] v) {
		final int n = u.length;
		// Work relative to the polygon and with a tolerance for its size, so that points on a straight edge stay
		// collinear despite rounding
		double[] lu = new double[n];
		double[] lv = new double[n];
		double extent = 0;
		for (int i = 0; i < n; i++) {
			lu[i] = u[i] - u[0];
			lv[i] = v[i] - v[0];
			extent = Math.max(extent, Math.max(Math.abs(lu[i]), Math.abs(lv[i])));
		}
		final double eps = 1e-12 * extent * extent;
		List<Integer> remaining = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			remaining.add(i);
		}
		int[] result = new int[3 * (n - 2)];
		int count = 0;
		while (remaining.size() > 3) {
			boolean clipped = false;
			for (int k = 0; k < remaining.size(); k++) {
				int a = remaining.get((k + remaining.size() - 1) % remaining.size());
				int b = remaining.get(k);
				int c = remaining.get((k + 1) % remaining.size());
				if (cross(lu, lv, a, b, c) <= eps || containsOther(lu, lv, remaining, a, b, c, eps)) {
					continue;
				}
				result[count++] = a;
				result[count++] = b;
				result[count++] = c;
				remaining.remove(k);
				clipped = true;
				break;
			}
			if (!clipped) {
				throw new IllegalStateException("Polygon cannot be split into triangles");
			}
		}
		result[count++] = remaining.get(0);
		result[count++] = remaining.get(1);
		result[count] = remaining.get(2);
		return result;
	}

	/**
	 * Triangulates a simple polygon with interior points about the given spacing apart.
	 *
	 * @param u first coordinates of the polygon, counter-clockwise
	 * @param v second coordinates
	 * @param spacing distance between interior points; points closer than half of it to the boundary are left out
	 * @return the points and triangles
	 * @throws IllegalStateException if the polygon cannot be split, e.g. because it crosses itself
	 */
	static Result triangulate(double[] u, double[] v, double spacing) {
		Mesh mesh = new Mesh(u, v, triangulate(u, v));
		final int n = u.length;
		double minU = Double.POSITIVE_INFINITY;
		double maxU = Double.NEGATIVE_INFINITY;
		double minV = Double.POSITIVE_INFINITY;
		double maxV = Double.NEGATIVE_INFINITY;
		for (int i = 0; i < n; i++) {
			minU = Math.min(minU, u[i]);
			maxU = Math.max(maxU, u[i]);
			minV = Math.min(minV, v[i]);
			maxV = Math.max(maxV, v[i]);
		}
		// A triangular lattice gives nearly equilateral triangles
		final double rowHeight = spacing * Math.sqrt(3) / 2;
		int row = 0;
		for (double pv = minV + rowHeight / 2; pv < maxV; pv += rowHeight, row++) {
			for (double pu = minU + (row % 2 == 0 ? spacing / 2 : spacing); pu < maxU; pu += spacing) {
				if (inside(u, v, pu, pv) && boundaryDistance(u, v, pu, pv) >= spacing / 2) {
					mesh.insert(pu, pv);
				}
			}
		}
		return mesh.result();
	}

	private static double cross(double[] u, double[] v, int a, int b, int c) {
		return (u[b] - u[a]) * (v[c] - v[a]) - (v[b] - v[a]) * (u[c] - u[a]);
	}

	/** Whether another polygon vertex lies in or on the triangle a, b, c */
	private static boolean containsOther(double[] u, double[] v, List<Integer> remaining, int a, int b, int c,
										 double eps) {
		for (int p : remaining) {
			if (p == a || p == b || p == c
					|| (u[p] == u[a] && v[p] == v[a]) || (u[p] == u[c] && v[p] == v[c])) {
				continue;
			}
			if (cross(u, v, a, b, p) >= -eps && cross(u, v, b, c, p) >= -eps && cross(u, v, c, a, p) >= -eps) {
				return true;
			}
		}
		return false;
	}

	private static boolean inside(double[] u, double[] v, double pu, double pv) {
		boolean inside = false;
		for (int i = 0, j = u.length - 1; i < u.length; j = i++) {
			if ((v[i] > pv) != (v[j] > pv) && pu < (u[j] - u[i]) * (pv - v[i]) / (v[j] - v[i]) + u[i]) {
				inside = !inside;
			}
		}
		return inside;
	}

	private static double boundaryDistance(double[] u, double[] v, double pu, double pv) {
		double best = Double.POSITIVE_INFINITY;
		for (int i = 0; i < u.length; i++) {
			int j = (i + 1) % u.length;
			double du = u[j] - u[i];
			double dv = v[j] - v[i];
			double lengthSq = du * du + dv * dv;
			double t = lengthSq == 0 ? 0 : Math.max(0, Math.min(1, ((pu - u[i]) * du + (pv - v[i]) * dv) / lengthSq));
			best = Math.min(best, Math.hypot(pu - u[i] - t * du, pv - v[i] - t * dv));
		}
		return best;
	}

	/** A triangulation that points can be inserted into, keeping the boundary edges */
	private static final class Mesh {
		private final List<Double> pu = new ArrayList<>();
		private final List<Double> pv = new ArrayList<>();
		private final List<int[]> triangles = new ArrayList<>();
		/** Directed edge to the triangle that has it */
		private final Map<Long, Integer> edges = new HashMap<>();
		private final int boundaryCount;
		private int last = 0;

		Mesh(double[] u, double[] v, int[] initial) {
			boundaryCount = u.length;
			for (int i = 0; i < u.length; i++) {
				pu.add(u[i]);
				pv.add(v[i]);
			}
			for (int i = 0; i < initial.length; i += 3) {
				add(new int[]{initial[i], initial[i + 1], initial[i + 2]});
			}
		}

		private static long key(int a, int b) {
			return ((long) a << 32) | (b & 0xffffffffL);
		}

		private boolean isBoundary(int a, int b) {
			return a < boundaryCount && b < boundaryCount
					&& ((a + 1) % boundaryCount == b || (b + 1) % boundaryCount == a);
		}

		private void add(int[] t) {
			set(triangles.size(), t);
		}

		private void set(int index, int[] t) {
			if (index == triangles.size()) {
				triangles.add(t);
			} else {
				triangles.set(index, t);
			}
			for (int k = 0; k < 3; k++) {
				edges.put(key(t[k], t[(k + 1) % 3]), index);
			}
		}

		private double cross(int a, int b, double u, double v) {
			return (pu.get(b) - pu.get(a)) * (v - pv.get(a)) - (pv.get(b) - pv.get(a)) * (u - pu.get(a));
		}

		/** Inserts a point strictly inside a triangle; points on or near an edge are skipped */
		void insert(double u, double v) {
			int t = locate(u, v);
			if (t < 0) {
				return;
			}
			int[] tri = triangles.get(t);
			int p = pu.size();
			pu.add(u);
			pv.add(v);
			int a = tri[0];
			int b = tri[1];
			int c = tri[2];
			for (int k = 0; k < 3; k++) {
				edges.remove(key(tri[k], tri[(k + 1) % 3]));
			}
			set(t, new int[]{a, b, p});
			add(new int[]{b, c, p});
			add(new int[]{c, a, p});
			legalize(a, b, p);
			legalize(b, c, p);
			legalize(c, a, p);
			last = triangles.size() - 1;
		}

		/** Finds the triangle containing the point, walking from the last insertion and scanning if that fails */
		private int locate(double u, double v) {
			int t = last;
			for (int steps = 0; steps < triangles.size(); steps++) {
				int[] tri = triangles.get(t);
				int next = -1;
				for (int k = 0; k < 3 && next < 0; k++) {
					int a = tri[k];
					int b = tri[(k + 1) % 3];
					if (cross(a, b, u, v) < 0) {
						Integer neighbor = edges.get(key(b, a));
						if (neighbor == null) {
							break;		// Blocked by the boundary: fall back to scanning
						}
						next = neighbor;
					}
				}
				if (next < 0) {
					break;
				}
				t = next;
			}
			if (strictlyInside(triangles.get(t), u, v)) {
				return t;
			}
			for (int i = 0; i < triangles.size(); i++) {
				if (strictlyInside(triangles.get(i), u, v)) {
					return i;
				}
			}
			return -1;
		}

		private boolean strictlyInside(int[] tri, double u, double v) {
			for (int k = 0; k < 3; k++) {
				int a = tri[k];
				int b = tri[(k + 1) % 3];
				double length = Math.hypot(pu.get(b) - pu.get(a), pv.get(b) - pv.get(a));
				// Points within a small fraction of the edge length from an edge are skipped
				if (cross(a, b, u, v) <= 1e-6 * length * length) {
					return false;
				}
			}
			return true;
		}

		/** Restores the Delaunay property across the edge a-b of the triangle (a, b, p) */
		private void legalize(int a, int b, int p) {
			if (isBoundary(a, b)) {
				return;
			}
			Integer t1 = edges.get(key(a, b));
			Integer t2 = edges.get(key(b, a));
			if (t1 == null || t2 == null) {
				return;
			}
			int[] other = triangles.get(t2);
			int d = -1;
			for (int k = 0; k < 3; k++) {
				if (other[k] != a && other[k] != b) {
					d = other[k];
				}
			}
			if (!inCircle(a, b, p, d)) {
				return;
			}
			for (int k = 0; k < 3; k++) {
				edges.remove(key(triangles.get(t1)[k], triangles.get(t1)[(k + 1) % 3]));
				edges.remove(key(other[k], other[(k + 1) % 3]));
			}
			set(t1, new int[]{a, d, p});
			set(t2, new int[]{d, b, p});
			legalize(a, d, p);
			legalize(d, b, p);
		}

		/** Whether d lies inside the circumcircle of the counter-clockwise triangle a, b, c */
		private boolean inCircle(int a, int b, int c, int d) {
			double adx = pu.get(a) - pu.get(d);
			double ady = pv.get(a) - pv.get(d);
			double bdx = pu.get(b) - pu.get(d);
			double bdy = pv.get(b) - pv.get(d);
			double cdx = pu.get(c) - pu.get(d);
			double cdy = pv.get(c) - pv.get(d);
			double det = (adx * adx + ady * ady) * (bdx * cdy - cdx * bdy)
					- (bdx * bdx + bdy * bdy) * (adx * cdy - cdx * ady)
					+ (cdx * cdx + cdy * cdy) * (adx * bdy - bdx * ady);
			return det > 1e-30;
		}

		Result result() {
			double[] u = new double[pu.size()];
			double[] v = new double[pv.size()];
			for (int i = 0; i < u.length; i++) {
				u[i] = pu.get(i);
				v[i] = pv.get(i);
			}
			int[] t = new int[3 * triangles.size()];
			for (int i = 0; i < triangles.size(); i++) {
				System.arraycopy(triangles.get(i), 0, t, 3 * i, 3);
			}
			return new Result(u, v, t);
		}
	}
}
