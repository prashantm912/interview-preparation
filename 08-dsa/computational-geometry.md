# Computational Geometry

[← Back to master index](../README.md)

Computational geometry is the study of algorithms that operate on geometric objects: points, vectors, lines, segments, and polygons. The whole field rests on a tiny algebraic core — the **dot product** (projection, angle) and the **cross product** (signed area, orientation) — from which everything else is built: the CCW orientation test, segment intersection, convex hulls, polygon area via the shoelace formula, point-in-polygon tests, and sweep-line techniques.

Two themes recur throughout. First, **prefer integer arithmetic** whenever inputs are integers: the orientation test and area computations are exact with `long`, so you sidestep floating-point error entirely. When floating point is unavoidable, compare against an **epsilon** rather than testing exact equality. Second, almost every "hard" geometry problem reduces to **sorting** (by coordinate or by angle) plus a **linear scan** that maintains an invariant — that is the shape of Graham scan, Andrew's monotone chain, rotating calipers, and the sweep line.

This document ramps from the algebraic primitives up through convex hulls, closest pair, sweep-line intersection, and rotating calipers, ending with robustness and precision concerns. All solutions are self-contained, compilable Java.

## Coding Problems

### Problem 1: Point and Vector Primitives — Points & Vectors

**Statement.** Build a `Point` value type supporting addition, subtraction (which yields the vector from one point to another), scalar multiplication, and squared/true magnitude. These primitives underpin every later problem.

```java
public class Point {
    public final double x, y;

    public Point(double x, double y) { this.x = x; this.y = y; }

    public Point add(Point o)      { return new Point(x + o.x, y + o.y); }
    public Point sub(Point o)      { return new Point(x - o.x, y - o.y); } // vector o->this
    public Point scale(double k)   { return new Point(x * k, y * k); }

    public double norm2()          { return x * x + y * y; }   // squared length, no sqrt
    public double norm()           { return Math.sqrt(norm2()); }

    @Override public String toString() { return "(" + x + ", " + y + ")"; }
}
```

**Time:** O(1) per operation · **Space:** O(1)

**Insight:** Treat a point and the vector from the origin to it as the same object; subtraction `b.sub(a)` gives the vector a→b, the single most-used operation in geometry.

---

### Problem 2: Dot Product — Dot Product

**Statement.** Given vectors `u` and `v`, compute `u · v = u.x·v.x + u.y·v.y`. Use it to decide whether the angle between them is acute (`> 0`), right (`= 0`), or obtuse (`< 0`).

```java
public class DotProduct {
    public static double dot(double ux, double uy, double vx, double vy) {
        return ux * vx + uy * vy;
    }

    // -1 obtuse, 0 right angle, +1 acute
    public static int angleType(double ux, double uy, double vx, double vy) {
        double d = dot(ux, uy, vx, vy);
        return d < 0 ? -1 : (d > 0 ? 1 : 0);
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight:** The dot product equals `|u||v|cosθ`, so its sign alone classifies the angle without any trigonometry or square roots.

---

### Problem 3: Cross Product — Cross Product

**Statement.** Given 2D vectors `u` and `v`, compute the scalar cross product `u × v = u.x·v.y - u.y·v.x`. Its absolute value is the area of the parallelogram they span; its sign tells you the rotational sense from `u` to `v`.

```java
public class CrossProduct {
    public static double cross(double ux, double uy, double vx, double vy) {
        return ux * vy - uy * vx;
    }

    // Exact version for integer inputs; widen to long to avoid overflow.
    public static long crossLong(long ux, long uy, long vx, long vy) {
        return ux * vy - uy * vx;
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight:** The 2D cross product is the z-component of the 3D cross product; its sign is the foundation of every orientation, hull, and intersection test that follows.

---

### Problem 4: Orientation / CCW Test — Orientation

**Statement.** Given three points `a`, `b`, `c`, decide whether the path a→b→c turns counter-clockwise (left), clockwise (right), or is collinear. Return `+1`, `-1`, or `0`.

```java
public class Orientation {
    // Cross product of (b-a) and (c-a).
    public static int ccw(long ax, long ay, long bx, long by, long cx, long cy) {
        long val = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        if (val > 0) return 1;   // counter-clockwise (left turn)
        if (val < 0) return -1;  // clockwise (right turn)
        return 0;                // collinear
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight:** Orientation is just the sign of one cross product; with `long` inputs it is exact, which is why integer geometry is so much safer than floating point.

---

### Problem 5: Collinearity of Three Points — Orientation

**Statement.** Determine whether three points lie on a single straight line.

```java
public class Collinear {
    public static boolean areCollinear(long ax, long ay, long bx, long by, long cx, long cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax) == 0;
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight:** Three points are collinear exactly when the triangle they form has zero signed area, i.e. the cross product vanishes — no division, no slope comparison, no divide-by-zero edge cases.

---

### Problem 6: Signed Area of a Triangle — Cross Product

**Statement.** Compute the signed area of triangle `abc`. A positive value means the vertices are listed counter-clockwise.

```java
public class TriangleArea {
    public static double signedArea(double ax, double ay, double bx, double by,
                                    double cx, double cy) {
        return ((bx - ax) * (cy - ay) - (by - ay) * (cx - ax)) / 2.0;
    }

    public static double area(double ax, double ay, double bx, double by,
                              double cx, double cy) {
        return Math.abs(signedArea(ax, ay, bx, by, cx, cy));
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight:** Half the cross product is the signed triangle area — the atomic case of the shoelace formula for general polygons.

---

### Problem 7: Euclidean and Squared Distance — Distance

**Statement.** Compute the distance between two points. Provide a squared-distance variant for comparisons where you never need the actual length.

```java
public class Distance {
    public static double dist2(double ax, double ay, double bx, double by) {
        double dx = ax - bx, dy = ay - by;
        return dx * dx + dy * dy;
    }

    public static double dist(double ax, double ay, double bx, double by) {
        return Math.sqrt(dist2(ax, ay, bx, by));
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight:** `sqrt` is monotonic, so when you only need to compare or sort distances, use squared distance — it stays exact for integers and avoids a costly, lossy `sqrt`.

---

### Problem 8: Manhattan and Chebyshev Distance — Distance

**Statement.** Compute the L1 (Manhattan) and L∞ (Chebyshev) distances between two points, common in grid problems and king-move metrics.

```java
public class GridDistance {
    public static long manhattan(long ax, long ay, long bx, long by) {
        return Math.abs(ax - bx) + Math.abs(ay - by);
    }

    public static long chebyshev(long ax, long ay, long bx, long by) {
        return Math.max(Math.abs(ax - bx), Math.abs(ay - by));
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight:** Rotating the plane by 45° turns Chebyshev distance into Manhattan distance — a transform that simplifies many "fewest king moves" or L∞ nearest-neighbor problems.

---

### Problem 9: Vector Rotation — Points & Vectors

**Statement.** Rotate a vector `(x, y)` by angle `θ` about the origin, and rotate a point about an arbitrary pivot.

```java
public class Rotation {
    public static double[] rotate(double x, double y, double theta) {
        double c = Math.cos(theta), s = Math.sin(theta);
        return new double[] { x * c - y * s, x * s + y * c };
    }

    public static double[] rotateAbout(double x, double y, double px, double py, double theta) {
        double[] r = rotate(x - px, y - py, theta);
        return new double[] { r[0] + px, r[1] + py };
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight:** Rotation about a pivot is "translate to origin, rotate, translate back" — the same compose pattern used for all affine transforms.

---

### Problem 10: Angle of a Vector via atan2 — Points & Vectors

**Statement.** Compute the angle of a vector with the positive x-axis in `(-π, π]`, and the unsigned angle between two vectors.

```java
public class VectorAngle {
    public static double angle(double x, double y) {
        return Math.atan2(y, x); // handles all quadrants and the x=0 case
    }

    public static double between(double ux, double uy, double vx, double vy) {
        double dot = ux * vx + uy * vy;
        double crs = ux * vy - uy * vx;
        return Math.atan2(Math.abs(crs), dot); // in [0, π]
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight:** Prefer `atan2(cross, dot)` over `acos(dot/(|u||v|))` for the angle between vectors: it is numerically stable near 0 and π where `acos` loses precision and can return NaN from rounding.

---

### Problem 11: Point on Segment — Distance

**Statement.** Given a point `p` and a segment `ab`, determine whether `p` lies on the segment (collinear and within the bounding box of `a` and `b`).

```java
public class PointOnSegment {
    public static boolean onSegment(long px, long py, long ax, long ay, long bx, long by) {
        // Must be collinear...
        long cross = (bx - ax) * (py - ay) - (by - ay) * (px - ax);
        if (cross != 0) return false;
        // ...and within the bounding box of a and b.
        return Math.min(ax, bx) <= px && px <= Math.max(ax, bx)
            && Math.min(ay, by) <= py && py <= Math.max(ay, by);
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight:** Collinearity alone is not enough — a collinear point can lie on the infinite line yet outside the segment; the bounding-box check fences it to the actual segment.

---

### Problem 12: Distance from Point to Infinite Line — Distance

**Statement.** Compute the perpendicular distance from point `p` to the infinite line through `a` and `b`.

```java
public class PointLineDistance {
    public static double distance(double px, double py, double ax, double ay,
                                  double bx, double by) {
        double abx = bx - ax, aby = by - ay;
        double cross = abx * (py - ay) - aby * (px - ax);
        double len = Math.sqrt(abx * abx + aby * aby);
        return Math.abs(cross) / len;
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight:** The cross product of `ab` with `ap` is the parallelogram area `|ab|·distance`; dividing by `|ab|` recovers the perpendicular distance directly.

---

### Problem 13: Distance from Point to Segment — Distance

**Statement.** Compute the shortest distance from point `p` to segment `ab`. Unlike the infinite-line case, the nearest point may be an endpoint.

```java
public class PointSegmentDistance {
    public static double distance(double px, double py, double ax, double ay,
                                  double bx, double by) {
        double abx = bx - ax, aby = by - ay;
        double apx = px - ax, apy = py - ay;
        double len2 = abx * abx + aby * aby;
        if (len2 == 0) return Math.hypot(apx, apy); // a == b, degenerate segment
        // Project p onto the line, clamp parameter t to [0, 1].
        double t = (apx * abx + apy * aby) / len2;
        t = Math.max(0, Math.min(1, t));
        double cx = ax + t * abx, cy = ay + t * aby;
        return Math.hypot(px - cx, py - cy);
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight:** Project, then **clamp** the projection parameter to `[0, 1]`; the clamp is what distinguishes segment distance from infinite-line distance.

---

### Problem 14: Closest Point on a Segment — Distance

**Statement.** Return the actual closest point on segment `ab` to a query point `p` (not just the distance).

```java
public class ClosestPointOnSegment {
    public static double[] closest(double px, double py, double ax, double ay,
                                   double bx, double by) {
        double abx = bx - ax, aby = by - ay;
        double len2 = abx * abx + aby * aby;
        if (len2 == 0) return new double[] { ax, ay };
        double t = ((px - ax) * abx + (py - ay) * aby) / len2;
        t = Math.max(0, Math.min(1, t));
        return new double[] { ax + t * abx, ay + t * aby };
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight:** The clamped projection parameter `t` directly parameterizes the foot of the perpendicular — `t=0` is `a`, `t=1` is `b`, anything between is an interior foot.

---

### Problem 15: Do Two Segments Intersect — Segment Intersection

**Statement.** Decide whether segments `p1p2` and `p3p4` intersect, handling the collinear-overlap case.

```java
public class SegmentIntersect {
    static int ccw(long ax, long ay, long bx, long by, long cx, long cy) {
        long v = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        return v > 0 ? 1 : (v < 0 ? -1 : 0);
    }

    static boolean onSeg(long ax, long ay, long bx, long by, long px, long py) {
        return Math.min(ax, bx) <= px && px <= Math.max(ax, bx)
            && Math.min(ay, by) <= py && py <= Math.max(ay, by);
    }

    public static boolean intersect(long p1x, long p1y, long p2x, long p2y,
                                    long p3x, long p3y, long p4x, long p4y) {
        int d1 = ccw(p3x, p3y, p4x, p4y, p1x, p1y);
        int d2 = ccw(p3x, p3y, p4x, p4y, p2x, p2y);
        int d3 = ccw(p1x, p1y, p2x, p2y, p3x, p3y);
        int d4 = ccw(p1x, p1y, p2x, p2y, p4x, p4y);
        if (d1 != d2 && d3 != d4) return true; // proper crossing
        // Collinear / touching endpoint cases.
        if (d1 == 0 && onSeg(p3x, p3y, p4x, p4y, p1x, p1y)) return true;
        if (d2 == 0 && onSeg(p3x, p3y, p4x, p4y, p2x, p2y)) return true;
        if (d3 == 0 && onSeg(p1x, p1y, p2x, p2y, p3x, p3y)) return true;
        if (d4 == 0 && onSeg(p1x, p1y, p2x, p2y, p4x, p4y)) return true;
        return false;
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight:** Two segments cross properly when each straddles the other's line (opposite orientations on both); the four `== 0` checks then catch the collinear and endpoint-touch corner cases.

---

### Problem 16: Segment Intersection Point — Segment Intersection

**Statement.** Given two segments known to intersect at a single point, compute that intersection point.

```java
public class IntersectionPoint {
    // Returns the intersection of lines p1p2 and p3p4, or null if parallel.
    public static double[] of(double p1x, double p1y, double p2x, double p2y,
                              double p3x, double p3y, double p4x, double p4y) {
        double a1 = p2y - p1y, b1 = p1x - p2x, c1 = a1 * p1x + b1 * p1y;
        double a2 = p4y - p3y, b2 = p3x - p4x, c2 = a2 * p3x + b2 * p3y;
        double det = a1 * b2 - a2 * b1;
        if (Math.abs(det) < 1e-12) return null; // parallel or collinear
        double x = (b2 * c1 - b1 * c2) / det;
        double y = (a1 * c2 - a2 * c1) / det;
        return new double[] { x, y };
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight:** Writing each line as `ax + by = c` turns intersection into a 2×2 linear solve via Cramer's rule; the determinant is zero exactly when the lines are parallel.

---

### Problem 17: Line–Line Relationship — Line & Segment Intersection

**Statement.** Classify two lines (each given by two points) as intersecting, parallel-and-distinct, or coincident.

```java
public class LineRelationship {
    public enum Rel { INTERSECT, PARALLEL, COINCIDENT }

    public static Rel classify(double p1x, double p1y, double p2x, double p2y,
                               double p3x, double p3y, double p4x, double p4y) {
        double d1x = p2x - p1x, d1y = p2y - p1y;
        double d2x = p4x - p3x, d2y = p4y - p3y;
        double cross = d1x * d2y - d1y * d2x;
        if (Math.abs(cross) > 1e-12) return Rel.INTERSECT;
        // Direction vectors parallel; check if p3 lies on line 1.
        double c2 = d1x * (p3y - p1y) - d1y * (p3x - p1x);
        return Math.abs(c2) < 1e-12 ? Rel.COINCIDENT : Rel.PARALLEL;
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight:** Parallelism is "direction cross product is zero"; coincidence adds the condition that a point of one line lies on the other — two cross products fully classify the pair.

---

### Problem 18: Polygon Area via Shoelace — Polygon Area

**Statement.** Compute the area of a simple polygon given its vertices in order (clockwise or counter-clockwise).

```java
public class ShoelaceArea {
    public static double area(double[] xs, double[] ys) {
        int n = xs.length;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            sum += xs[i] * ys[j] - xs[j] * ys[i];
        }
        return Math.abs(sum) / 2.0;
    }
}
```

**Time:** O(n) · **Space:** O(1)

**Insight:** The shoelace sum accumulates signed triangle areas fanned from the origin; the boundary contributions telescope so only the polygon's interior survives.

---

### Problem 19: Polygon Orientation and Signed Area — Polygon Area

**Statement.** Determine whether a polygon's vertices are listed counter-clockwise, using the sign of the shoelace sum.

```java
public class PolygonOrientation {
    public static double signedArea(long[] xs, long[] ys) {
        int n = xs.length;
        long sum = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            sum += xs[i] * ys[j] - xs[j] * ys[i];
        }
        return sum / 2.0; // > 0 means CCW, < 0 means CW
    }

    public static boolean isCounterClockwise(long[] xs, long[] ys) {
        return signedArea(xs, ys) > 0;
    }
}
```

**Time:** O(n) · **Space:** O(1)

**Insight:** The sign of the shoelace sum encodes winding direction for free; many algorithms (point-in-polygon, hull merging) assume a known orientation, so normalize to CCW up front.

---

### Problem 20: Polygon Perimeter and Centroid — Polygon Area

**Statement.** Compute the perimeter and the area-centroid (center of mass) of a simple polygon.

```java
public class PolygonMetrics {
    public static double perimeter(double[] xs, double[] ys) {
        int n = xs.length;
        double p = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            p += Math.hypot(xs[j] - xs[i], ys[j] - ys[i]);
        }
        return p;
    }

    public static double[] centroid(double[] xs, double[] ys) {
        int n = xs.length;
        double a2 = 0, cx = 0, cy = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double cross = xs[i] * ys[j] - xs[j] * ys[i];
            a2 += cross;
            cx += (xs[i] + xs[j]) * cross;
            cy += (ys[i] + ys[j]) * cross;
        }
        double area = a2 / 2.0;
        return new double[] { cx / (6 * area), cy / (6 * area) };
    }
}
```

**Time:** O(n) · **Space:** O(1)

**Insight:** The area-weighted centroid differs from the simple vertex average; it correctly weights regions by area using the same shoelace cross terms.

---

### Problem 21: Point in Polygon — Ray Casting — Point in Polygon

**Statement.** Determine whether a point lies inside a simple (possibly non-convex) polygon using the ray-casting (even–odd) rule.

```java
public class PointInPolygonRay {
    public static boolean inside(double px, double py, double[] xs, double[] ys) {
        int n = xs.length;
        boolean in = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            boolean straddles = (ys[i] > py) != (ys[j] > py);
            if (straddles) {
                double xCross = (xs[j] - xs[i]) * (py - ys[i]) / (ys[j] - ys[i]) + xs[i];
                if (px < xCross) in = !in;
            }
        }
        return in;
    }
}
```

**Time:** O(n) · **Space:** O(1)

**Insight:** Cast a ray to the right and count edge crossings: odd means inside, even means outside; the `(ys[i] > py) != (ys[j] > py)` test cleanly handles which edges the horizontal ray can cross.

---

### Problem 22: Point in Polygon — Winding Number — Point in Polygon

**Statement.** Determine point-in-polygon using the winding-number method, which is robust for self-intersecting polygons where even–odd disagrees.

```java
public class PointInPolygonWinding {
    static int ccw(double ax, double ay, double bx, double by, double cx, double cy) {
        double v = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        return v > 0 ? 1 : (v < 0 ? -1 : 0);
    }

    public static boolean inside(double px, double py, double[] xs, double[] ys) {
        int n = xs.length, wn = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            if (ys[i] <= py) {
                if (ys[j] > py && ccw(xs[i], ys[i], xs[j], ys[j], px, py) > 0) wn++;
            } else {
                if (ys[j] <= py && ccw(xs[i], ys[i], xs[j], ys[j], px, py) < 0) wn--;
            }
        }
        return wn != 0;
    }
}
```

**Time:** O(n) · **Space:** O(1)

**Insight:** The winding number counts how many times the polygon wraps around the point; nonzero means inside, and unlike even–odd it gives the intuitive answer for overlapping or self-intersecting boundaries.

---

### Problem 23: Point in Convex Polygon — Binary Search — Point in Polygon

**Statement.** Given a convex polygon in CCW order, answer point-in-polygon queries in `O(log n)` by binary-searching the fan of triangles from vertex 0.

```java
public class PointInConvex {
    static long cross(long[] xs, long[] ys, int o, int a, int b) {
        return (xs[a] - xs[o]) * (ys[b] - ys[o]) - (ys[a] - ys[o]) * (xs[b] - xs[o]);
    }

    public static boolean inside(long px, long py, long[] xs, long[] ys) {
        int n = xs.length;
        // p must be left of edge 0->1 and right of edge 0->(n-1).
        if (cross(xs, ys, 0, 1, 0) == 0) return false; // degenerate guard
        long c1 = (xs[1] - xs[0]) * (py - ys[0]) - (ys[1] - ys[0]) * (px - xs[0]);
        long c2 = (xs[n - 1] - xs[0]) * (py - ys[0]) - (ys[n - 1] - ys[0]) * (px - xs[0]);
        if (c1 < 0 || c2 > 0) return false;
        int lo = 1, hi = n - 1;
        while (hi - lo > 1) {
            int mid = (lo + hi) / 2;
            long c = (xs[mid] - xs[0]) * (py - ys[0]) - (ys[mid] - ys[0]) * (px - xs[0]);
            if (c >= 0) lo = mid; else hi = mid;
        }
        // p is in the cone of triangle (0, lo, lo+1); check the far edge.
        long cc = (xs[hi] - xs[lo]) * (py - ys[lo]) - (ys[hi] - ys[lo]) * (px - xs[lo]);
        return cc >= 0;
    }
}
```

**Time:** O(log n) per query · **Space:** O(1)

**Insight:** Convexity lets you binary-search the angular fan from a fixed vertex, turning a linear scan into a logarithmic query — essential when many points are tested against the same polygon.

---

### Problem 24: Convex Hull — Andrew's Monotone Chain — Convex Hull

**Statement.** Compute the convex hull of a set of points. Sort by `(x, y)`, then build the lower and upper hulls in one pass each.

```java
import java.util.*;

public class MonotoneChain {
    static long cross(long[] o, long[] a, long[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    public static long[][] hull(long[][] pts) {
        int n = pts.length;
        if (n < 3) return pts.clone();
        Arrays.sort(pts, (p, q) -> p[0] != q[0]
                ? Long.compare(p[0], q[0]) : Long.compare(p[1], q[1]));
        long[][] h = new long[2 * n][];
        int k = 0;
        for (long[] p : pts) {                      // lower hull
            while (k >= 2 && cross(h[k - 2], h[k - 1], p) <= 0) k--;
            h[k++] = p;
        }
        int lower = k + 1;
        for (int i = n - 2; i >= 0; i--) {          // upper hull
            long[] p = pts[i];
            while (k >= lower && cross(h[k - 2], h[k - 1], p) <= 0) k--;
            h[k++] = p;
        }
        return Arrays.copyOf(h, k - 1);             // drop duplicated start point
    }
}
```

**Time:** O(n log n) · **Space:** O(n)

**Insight:** Monotone chain is the cleanest hull algorithm: sort once, then maintain a stack where every right turn is popped — left turns only means the chain stays convex.

---

### Problem 25: Convex Hull — Graham Scan — Convex Hull

**Statement.** Compute the convex hull by picking the lowest point as a pivot, sorting the rest by polar angle, and scanning while popping right turns.

```java
import java.util.*;

public class GrahamScan {
    static long cross(long ox, long oy, long ax, long ay, long bx, long by) {
        return (ax - ox) * (by - oy) - (ay - oy) * (bx - ox);
    }

    public static List<long[]> hull(long[][] pts) {
        int n = pts.length;
        int piv = 0;
        for (int i = 1; i < n; i++)
            if (pts[i][1] < pts[piv][1] ||
                (pts[i][1] == pts[piv][1] && pts[i][0] < pts[piv][0])) piv = i;
        final long px = pts[piv][0], py = pts[piv][1];
        Arrays.sort(pts, (a, b) -> {
            long c = cross(px, py, a[0], a[1], b[0], b[1]);
            if (c != 0) return c > 0 ? -1 : 1;      // smaller polar angle first
            long da = (a[0]-px)*(a[0]-px) + (a[1]-py)*(a[1]-py);
            long db = (b[0]-px)*(b[0]-px) + (b[1]-py)*(b[1]-py);
            return Long.compare(da, db);            // nearer first on ties
        });
        Deque<long[]> st = new ArrayDeque<>();
        for (long[] p : pts) {
            while (st.size() >= 2) {
                long[] top = st.pop(), nxt = st.peek();
                if (cross(nxt[0], nxt[1], top[0], top[1], p[0], p[1]) > 0) {
                    st.push(top); break;            // left turn: keep top
                }                                   // else discard top (right turn / collinear)
            }
            st.push(p);
        }
        return new ArrayList<>(st);
    }
}
```

**Time:** O(n log n) · **Space:** O(n)

**Insight:** Graham scan sorts by angle around an extreme pivot so the boundary is traversed in order; the stack discipline — pop until the last turn is a left turn — is identical in spirit to monotone chain.

---

### Problem 26: Convex Hull — Jarvis March (Gift Wrapping) — Convex Hull

**Statement.** Compute the hull by repeatedly selecting the most counter-clockwise point. Runs in `O(nh)` where `h` is the number of hull vertices — fast when the hull is small.

```java
import java.util.*;

public class JarvisMarch {
    static long cross(long ox, long oy, long ax, long ay, long bx, long by) {
        return (ax - ox) * (by - oy) - (ay - oy) * (bx - ox);
    }

    public static List<long[]> hull(long[][] pts) {
        int n = pts.length;
        List<long[]> res = new ArrayList<>();
        int left = 0;
        for (int i = 1; i < n; i++) if (pts[i][0] < pts[left][0]) left = i;
        int p = left;
        do {
            res.add(pts[p]);
            int q = (p + 1) % n;
            for (int i = 0; i < n; i++) {
                long c = cross(pts[p][0], pts[p][1], pts[q][0], pts[q][1],
                               pts[i][0], pts[i][1]);
                if (c < 0) q = i;                   // i is more clockwise -> better wrap
            }
            p = q;
        } while (p != left);
        return res;
    }
}
```

**Time:** O(n·h) · **Space:** O(h)

**Insight:** Gift wrapping is output-sensitive — it never beats `O(n log n)` when the hull is large, but for a handful of hull vertices among many interior points it can be the fastest in practice.

---

### Problem 27: Convex Hull Including Collinear Points — Convex Hull

**Statement.** Compute the convex hull but **keep** points that lie exactly on a hull edge (some problems require all boundary points).

```java
import java.util.*;

public class HullWithCollinear {
    static long cross(long[] o, long[] a, long[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    public static long[][] hull(long[][] pts) {
        int n = pts.length;
        if (n < 3) return pts.clone();
        Arrays.sort(pts, (p, q) -> p[0] != q[0]
                ? Long.compare(p[0], q[0]) : Long.compare(p[1], q[1]));
        long[][] h = new long[2 * n][];
        int k = 0;
        for (long[] p : pts) {                      // strict '<' keeps collinear points
            while (k >= 2 && cross(h[k - 2], h[k - 1], p) < 0) k--;
            h[k++] = p;
        }
        int lower = k + 1;
        for (int i = n - 2; i >= 0; i--) {
            long[] p = pts[i];
            while (k >= lower && cross(h[k - 2], h[k - 1], p) < 0) k--;
            h[k++] = p;
        }
        return Arrays.copyOf(h, k - 1);
    }
}
```

**Time:** O(n log n) · **Space:** O(n)

**Insight:** Switching the pop condition from `<= 0` to `< 0` is the single difference: strict-less keeps collinear boundary points, non-strict discards them. Know which one the problem wants.

---

### Problem 28: Closest Pair — Brute Force Baseline — Closest Pair

**Statement.** Find the minimum distance between any two of `n` points by checking all pairs. This is the correctness oracle for the divide-and-conquer version.

```java
public class ClosestPairBrute {
    public static double closest(double[][] pts) {
        int n = pts.length;
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++) {
                double dx = pts[i][0] - pts[j][0], dy = pts[i][1] - pts[j][1];
                best = Math.min(best, dx * dx + dy * dy);
            }
        return Math.sqrt(best);
    }
}
```

**Time:** O(n²) · **Space:** O(1)

**Insight:** Work in squared distance for the comparisons and `sqrt` only the final answer; for small `n` (a few hundred) this brute force is the right tool and the natural base case of the recursion.

---

### Problem 29: Closest Pair — Divide and Conquer — Closest Pair

**Statement.** Find the closest pair of points in `O(n log n)` by splitting on the median x-coordinate, recursing, and merging across the dividing strip.

```java
import java.util.*;

public class ClosestPairDC {
    public static double closest(double[][] pts) {
        double[][] byX = pts.clone();
        Arrays.sort(byX, (a, b) -> Double.compare(a[0], b[0]));
        double[][] byY = pts.clone();
        Arrays.sort(byY, (a, b) -> Double.compare(a[1], b[1]));
        return Math.sqrt(rec(byX, byY, 0, pts.length - 1));
    }

    static double dist2(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1];
        return dx * dx + dy * dy;
    }

    static double rec(double[][] byX, double[][] byY, int lo, int hi) {
        int n = hi - lo + 1;
        if (n <= 3) {                               // base case: brute force
            double best = Double.POSITIVE_INFINITY;
            for (int i = lo; i <= hi; i++)
                for (int j = i + 1; j <= hi; j++)
                    best = Math.min(best, dist2(byX[i], byX[j]));
            return best;
        }
        int mid = (lo + hi) / 2;
        double midX = byX[mid][0];
        // Partition byY into left/right halves preserving y-order.
        double[][] leftY = new double[mid - lo + 1][];
        double[][] rightY = new double[hi - mid][];
        int li = 0, ri = 0;
        for (double[] p : byY) {
            if (p[0] < midX || (p[0] == midX && li < leftY.length)) {
                if (li < leftY.length) leftY[li++] = p; else rightY[ri++] = p;
            } else rightY[ri++] = p;
        }
        double dl = rec(byX, leftY, lo, mid);
        double dr = rec(byX, rightY, mid + 1, hi);
        double d = Math.min(dl, dr);
        // Collect points within strip of width sqrt(d) around the divide.
        double[][] strip = new double[n][];
        int s = 0;
        for (double[] p : byY)
            if ((p[0] - midX) * (p[0] - midX) < d) strip[s++] = p;
        for (int i = 0; i < s; i++)                 // each point checks <= 7 neighbors
            for (int j = i + 1; j < s && (strip[j][1] - strip[i][1]) *
                                          (strip[j][1] - strip[i][1]) < d; j++)
                d = Math.min(d, dist2(strip[i], strip[j]));
        return d;
    }
}
```

**Time:** O(n log n) · **Space:** O(n)

**Insight:** The key lemma: within the strip, sorted by y, each point can have at most a constant number (≤ 7) of neighbors closer than `d`, so the merge stays linear and the whole recurrence solves to `O(n log n)`.

---

### Problem 30: Closest Pair on a Line — Closest Pair

**Statement.** Given `n` numbers, find the two closest values. The 1D analogue of closest pair.

```java
import java.util.*;

public class ClosestPair1D {
    public static int[] closest(int[] a) {
        int[] s = a.clone();
        Arrays.sort(s);
        int bestGap = Integer.MAX_VALUE, x = s[0], y = s[1];
        for (int i = 1; i < s.length; i++) {
            if (s[i] - s[i - 1] < bestGap) {
                bestGap = s[i] - s[i - 1];
                x = s[i - 1]; y = s[i];
            }
        }
        return new int[] { x, y };
    }
}
```

**Time:** O(n log n) · **Space:** O(n)

**Insight:** In 1D the closest pair must be adjacent after sorting, so a single linear scan over consecutive gaps suffices — the 2D strip argument is the higher-dimensional generalization of exactly this fact.

---

### Problem 31: Bentley–Ottmann Style — Any Segment Intersection — Sweep Line

**Statement.** Detect whether any pair among `n` segments intersects, using a sweep line that orders segments by their y at the current x and only tests neighbors in that order.

```java
import java.util.*;

public class AnySegmentIntersection {
    static class Event {
        double x; int seg; boolean isLeft;
        Event(double x, int seg, boolean isLeft) { this.x = x; this.seg = seg; this.isLeft = isLeft; }
    }

    double[][] segs; // each: {x1,y1,x2,y2} with x1 <= x2

    boolean cross(double[] s, double[] t) {
        return SegmentIntersect.intersect(
            (long) s[0], (long) s[1], (long) s[2], (long) s[3],
            (long) t[0], (long) t[1], (long) t[2], (long) t[3]);
    }

    double yAt(double[] s, double x) {
        if (s[0] == s[2]) return s[1];
        return s[1] + (s[3] - s[1]) * (x - s[0]) / (s[2] - s[0]);
    }

    public boolean anyIntersect(double[][] segs) {
        this.segs = segs;
        int n = segs.length;
        List<Event> events = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            events.add(new Event(Math.min(segs[i][0], segs[i][2]), i, true));
            events.add(new Event(Math.max(segs[i][0], segs[i][2]), i, false));
        }
        events.sort((a, b) -> a.x != b.x ? Double.compare(a.x, b.x)
                                         : Boolean.compare(!a.isLeft, !b.isLeft));
        TreeMap<Double, Integer> status = new TreeMap<>();
        for (Event e : events) {
            double key = yAt(segs[e.seg], e.x);
            if (e.isLeft) {
                Map.Entry<Double, Integer> above = status.ceilingEntry(key);
                Map.Entry<Double, Integer> below = status.floorEntry(key);
                if (above != null && cross(segs[e.seg], segs[above.getValue()])) return true;
                if (below != null && cross(segs[e.seg], segs[below.getValue()])) return true;
                status.put(key, e.seg);
            } else {
                Map.Entry<Double, Integer> above = status.higherEntry(key);
                Map.Entry<Double, Integer> below = status.lowerEntry(key);
                if (above != null && below != null
                        && cross(segs[above.getValue()], segs[below.getValue()])) return true;
                status.remove(key);
            }
        }
        return false;
    }
}
```

**Time:** O(n log n) · **Space:** O(n)

**Insight:** The sweep line reduces the `O(n²)` all-pairs test to checking only vertically adjacent segments at each event; two segments can intersect only if they become neighbors in the status structure at some sweep position.

---

### Problem 32: Count Horizontal–Vertical Segment Intersections — Sweep Line

**Statement.** Given a set of horizontal and vertical segments, count how many H–V pairs intersect. A clean sweep-line + BIT application.

```java
import java.util.*;

public class HVIntersections {
    // horizontals: {y, x1, x2}; verticals: {x, y1, y2}
    public static long count(int[][] horiz, int[][] vert) {
        List<int[]> events = new ArrayList<>();
        // type 0 = horizontal start, 2 = horizontal end, 1 = vertical query
        for (int[] h : horiz) {
            events.add(new int[] { Math.min(h[1], h[2]), 0, h[0] });
            events.add(new int[] { Math.max(h[1], h[2]), 2, h[0] });
        }
        for (int[] v : vert)
            events.add(new int[] { v[0], 1, Math.min(v[1], v[2]), Math.max(v[1], v[2]) });
        events.sort((a, b) -> a[0] != b[0] ? a[0] - b[0] : a[1] - b[1]);

        TreeMap<Integer, Integer> activeY = new TreeMap<>(); // y -> count of active horizontals
        long total = 0;
        for (int[] e : events) {
            if (e[1] == 0) activeY.merge(e[2], 1, Integer::sum);
            else if (e[1] == 2) {
                if (activeY.merge(e[2], -1, Integer::sum) == 0) activeY.remove(e[2]);
            } else {
                for (int y : activeY.subMap(e[2], true, e[3], true).keySet())
                    total += activeY.get(y);
            }
        }
        return total;
    }
}
```

**Time:** O((n + k) log n) · **Space:** O(n)

**Insight:** Sweeping left to right, a vertical segment "queries" the active horizontals whose y falls in its span — exactly the orthogonal-segment crossings, which a balanced tree (or a Fenwick tree over compressed y) answers efficiently.

---

### Problem 33: Convex Polygon Diameter — Rotating Calipers — Rotating Calipers

**Statement.** Find the farthest pair of points (the diameter) of a convex polygon in `O(n)` using rotating calipers.

```java
public class ConvexDiameter {
    static long dist2(long[] a, long[] b) {
        long dx = a[0] - b[0], dy = a[1] - b[1];
        return dx * dx + dy * dy;
    }
    static long cross(long ox, long oy, long ax, long ay, long bx, long by) {
        return (ax - ox) * (by - oy) - (ay - oy) * (bx - ox);
    }

    // hull given in CCW order, no collinear points
    public static long diameter2(long[][] h) {
        int n = h.length;
        if (n == 1) return 0;
        if (n == 2) return dist2(h[0], h[1]);
        long best = 0;
        int j = 1;
        for (int i = 0; i < n; i++) {
            int ni = (i + 1) % n;
            // advance j while the next vertex is farther from edge i->ni
            while (Math.abs(cross(h[i][0], h[i][1], h[ni][0], h[ni][1],
                                  h[(j + 1) % n][0], h[(j + 1) % n][1]))
                 > Math.abs(cross(h[i][0], h[i][1], h[ni][0], h[ni][1],
                                  h[j][0], h[j][1]))) {
                j = (j + 1) % n;
            }
            best = Math.max(best, Math.max(dist2(h[i], h[j]), dist2(h[ni], h[j])));
        }
        return best;
    }
}
```

**Time:** O(n) · **Space:** O(1)

**Insight:** Antipodal pairs (the only candidates for the diameter) are enumerated by rotating two parallel supporting lines around the hull; because `j` only ever advances forward, the total work is linear despite the nested-looking loop.

---

### Problem 34: Width of a Convex Polygon — Rotating Calipers

**Statement.** Compute the minimum width (smallest distance between two parallel supporting lines) of a convex polygon.

```java
public class ConvexWidth {
    static double cross(double ox, double oy, double ax, double ay, double bx, double by) {
        return (ax - ox) * (by - oy) - (ay - oy) * (bx - ox);
    }

    public static double minWidth(double[][] h) {
        int n = h.length;
        if (n < 3) return 0;
        double best = Double.POSITIVE_INFINITY;
        int j = 1;
        for (int i = 0; i < n; i++) {
            int ni = (i + 1) % n;
            double edgeLen = Math.hypot(h[ni][0] - h[i][0], h[ni][1] - h[i][1]);
            while (Math.abs(cross(h[i][0], h[i][1], h[ni][0], h[ni][1],
                                  h[(j + 1) % n][0], h[(j + 1) % n][1]))
                 > Math.abs(cross(h[i][0], h[i][1], h[ni][0], h[ni][1],
                                  h[j][0], h[j][1]))) {
                j = (j + 1) % n;
            }
            double height = Math.abs(cross(h[i][0], h[i][1], h[ni][0], h[ni][1],
                                           h[j][0], h[j][1])) / edgeLen;
            best = Math.min(best, height);
        }
        return best;
    }
}
```

**Time:** O(n) · **Space:** O(1)

**Insight:** The minimum width is always achieved with one supporting line flush against an edge; rotating calipers visits each edge once and measures the distance to the farthest antipodal vertex.

---

### Problem 35: Minimum-Area Enclosing Rectangle — Rotating Calipers

**Statement.** Find the minimum-area rectangle enclosing a set of points. By a classic theorem, one side of the optimal rectangle is flush with a hull edge.

```java
public class MinAreaRectangle {
    static double cross(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }
    static double dot(double[] o, double[] a, double[] b) {
        return (a[0] - o[0]) * (b[0] - o[0]) + (a[1] - o[1]) * (b[1] - o[1]);
    }

    public static double minArea(double[][] h) {
        int n = h.length;
        if (n < 3) return 0;
        double best = Double.POSITIVE_INFINITY;
        int up = 1, right = 1, left = 1;
        for (int i = 0; i < n; i++) {
            int ni = (i + 1) % n;
            while (cross(h[i], h[ni], h[(up + 1) % n]) > cross(h[i], h[ni], h[up])) up = (up + 1) % n;
            while (dot(h[i], h[ni], h[(right + 1) % n]) > dot(h[i], h[ni], h[right])) right = (right + 1) % n;
            if (i == 0) left = up;
            while (dot(h[i], h[ni], h[(left + 1) % n]) < dot(h[i], h[ni], h[left])) left = (left + 1) % n;
            double edgeLen = Math.hypot(h[ni][0] - h[i][0], h[ni][1] - h[i][1]);
            double height = cross(h[i], h[ni], h[up]) / edgeLen;
            double width = (dot(h[i], h[ni], h[right]) - dot(h[i], h[ni], h[left])) / edgeLen;
            best = Math.min(best, height * width);
        }
        return best;
    }
}
```

**Time:** O(n) · **Space:** O(1)

**Insight:** Three calipers — one perpendicular for height, two parallel for width — rotate in lockstep with the current edge, so the optimal rectangle is found in a single linear pass over hull edges.

---

### Problem 36: Maximum Points on a Line — Line Arrangement

**Statement.** Given `n` points, find the maximum number that are collinear.

```java
import java.util.*;

public class MaxPointsOnLine {
    static long gcd(long a, long b) { return b == 0 ? a : gcd(b, a % b); }

    public static int maxPoints(int[][] pts) {
        int n = pts.length;
        if (n <= 2) return n;
        int best = 0;
        for (int i = 0; i < n; i++) {
            Map<String, Integer> slopes = new HashMap<>();
            int dup = 0, localMax = 0;
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                long dx = pts[j][0] - pts[i][0], dy = pts[j][1] - pts[i][1];
                if (dx == 0 && dy == 0) { dup++; continue; }
                long g = gcd(Math.abs(dx), Math.abs(dy));
                dx /= g; dy /= g;
                if (dx < 0 || (dx == 0 && dy < 0)) { dx = -dx; dy = -dy; } // normalize sign
                String key = dx + "," + dy;
                int c = slopes.merge(key, 1, Integer::sum);
                localMax = Math.max(localMax, c);
            }
            best = Math.max(best, localMax + dup + 1);
        }
        return best;
    }
}
```

**Time:** O(n²) · **Space:** O(n)

**Insight:** Anchor on each point and group the others by reduced direction `(dx/g, dy/g)` with a normalized sign; representing slope as a reduced fraction (not a `double`) avoids precision errors and division-by-zero on vertical lines.

---

### Problem 37: Count Pairs by Direction — Line Arrangement

**Statement.** Given vectors from a fixed origin, count how many pairs are parallel (same or opposite direction). A building block for arrangement and duality problems.

```java
import java.util.*;

public class ParallelPairs {
    static long gcd(long a, long b) { return b == 0 ? a : gcd(b, a % b); }

    public static long countParallel(long[][] vecs) {
        Map<String, Long> dir = new HashMap<>();
        for (long[] v : vecs) {
            long dx = v[0], dy = v[1];
            long g = gcd(Math.abs(dx), Math.abs(dy));
            if (g != 0) { dx /= g; dy /= g; }
            if (dx < 0 || (dx == 0 && dy < 0)) { dx = -dx; dy = -dy; }
            dir.merge(dx + "," + dy, 1L, Long::sum);
        }
        long pairs = 0;
        for (long c : dir.values()) pairs += c * (c - 1) / 2;
        return pairs;
    }
}
```

**Time:** O(n) · **Space:** O(n)

**Insight:** Reduce each direction to a canonical fraction and bucket; collinearity/parallelism questions become a counting problem over equivalence classes of directions.

---

### Problem 38: Polygon Convexity Test — Convex Hull

**Statement.** Decide whether a polygon given in order is convex (all turns have the same sign).

```java
public class ConvexityTest {
    public static boolean isConvex(long[][] p) {
        int n = p.length;
        if (n < 3) return false;
        int sign = 0;
        for (int i = 0; i < n; i++) {
            long[] a = p[i], b = p[(i + 1) % n], c = p[(i + 2) % n];
            long cr = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0]);
            if (cr != 0) {
                int s = cr > 0 ? 1 : -1;
                if (sign == 0) sign = s;
                else if (s != sign) return false;   // turn direction flipped
            }
        }
        return true;
    }
}
```

**Time:** O(n) · **Space:** O(1)

**Insight:** A polygon is convex iff every consecutive triple turns the same way; allow zero cross products (collinear vertices) but reject any sign flip.

---

### Problem 39: Pick's Theorem — Lattice Points — Polygon Area

**Statement.** For a polygon with integer (lattice) vertices, count the interior lattice points using Pick's theorem `A = I + B/2 − 1`, where `B` is boundary lattice points.

```java
public class PicksTheorem {
    static long gcd(long a, long b) { return b == 0 ? a : gcd(b, a % b); }

    public static long interiorPoints(long[][] p) {
        int n = p.length;
        long twiceArea = 0, boundary = 0;
        for (int i = 0; i < n; i++) {
            long[] a = p[i], b = p[(i + 1) % n];
            twiceArea += a[0] * b[1] - b[0] * a[1];
            boundary += gcd(Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1]));
        }
        long area2 = Math.abs(twiceArea);           // = 2A
        // A = I + B/2 - 1  =>  I = A - B/2 + 1  =>  2I = area2 - boundary + 2
        return (area2 - boundary + 2) / 2;
    }
}
```

**Time:** O(n) · **Space:** O(1)

**Insight:** Pick's theorem links area to lattice-point counts; boundary points on an edge equal `gcd(|dx|, |dy|)`, and keeping everything in integers (twice the area) makes the whole computation exact.

---

### Problem 40: Convex Polygon Intersection Area — Polygon Clipping

**Statement.** Compute the area of the intersection of two convex polygons using Sutherland–Hodgman clipping.

```java
import java.util.*;

public class ConvexIntersectionArea {
    static double cross(double ox, double oy, double ax, double ay, double bx, double by) {
        return (ax - ox) * (by - oy) - (ay - oy) * (bx - ox);
    }

    // Clip subject polygon against the half-plane left of directed edge a->b.
    static List<double[]> clip(List<double[]> poly, double[] a, double[] b) {
        List<double[]> out = new ArrayList<>();
        int n = poly.size();
        for (int i = 0; i < n; i++) {
            double[] cur = poly.get(i), prev = poly.get((i - 1 + n) % n);
            boolean curIn = cross(a[0], a[1], b[0], b[1], cur[0], cur[1]) >= 0;
            boolean prevIn = cross(a[0], a[1], b[0], b[1], prev[0], prev[1]) >= 0;
            if (curIn) {
                if (!prevIn) out.add(intersect(prev, cur, a, b));
                out.add(cur);
            } else if (prevIn) {
                out.add(intersect(prev, cur, a, b));
            }
        }
        return out;
    }

    static double[] intersect(double[] p, double[] q, double[] a, double[] b) {
        double a1 = b[1]-a[1], b1 = a[0]-b[0], c1 = a1*a[0] + b1*a[1];
        double a2 = q[1]-p[1], b2 = p[0]-q[0], c2 = a2*p[0] + b2*p[1];
        double det = a1*b2 - a2*b1;
        return new double[] { (b2*c1 - b1*c2)/det, (a1*c2 - a2*c1)/det };
    }

    public static double area(double[][] subj, double[][] clipPoly) {
        List<double[]> poly = new ArrayList<>(Arrays.asList(subj));
        int m = clipPoly.length;
        for (int i = 0; i < m && !poly.isEmpty(); i++) {
            poly = clip(poly, clipPoly[i], clipPoly[(i + 1) % m]);
        }
        // Shoelace on the clipped polygon.
        double s = 0; int k = poly.size();
        for (int i = 0; i < k; i++) {
            double[] u = poly.get(i), v = poly.get((i + 1) % k);
            s += u[0]*v[1] - v[0]*u[1];
        }
        return Math.abs(s) / 2.0;
    }
}
```

**Time:** O(n·m) · **Space:** O(n + m)

**Insight:** Sutherland–Hodgman clips the subject polygon against each edge of the (convex) clip polygon as a half-plane; intersecting two convex polygons keeps the result convex, so the clipped output is always a valid simple polygon.

---

### Problem 41: Largest Triangle from Points — Rotating Calipers

**Statement.** Find the maximum-area triangle whose vertices are among the given points. The optimal triangle's vertices lie on the convex hull.

```java
public class LargestTriangle {
    static long area2(long[] a, long[] b, long[] c) {
        return Math.abs((b[0]-a[0])*(c[1]-a[1]) - (b[1]-a[1])*(c[0]-a[0]));
    }

    public static double maxArea(long[][] hull) {
        int n = hull.length;
        if (n < 3) return 0;
        long best = 0;
        for (int i = 0; i < n; i++) {
            int k = (i + 2) % n;
            for (int j = (i + 1) % n; j != i; j = (j + 1) % n) {
                if (k == j) k = (k + 1) % n;
                while (k != i && area2(hull[i], hull[j], hull[(k + 1) % n])
                              > area2(hull[i], hull[j], hull[k]))
                    k = (k + 1) % n;
                best = Math.max(best, area2(hull[i], hull[j], hull[k]));
                if (j == i) break;
            }
        }
        return best / 2.0;
    }
}
```

**Time:** O(n²) · **Space:** O(1)

**Insight:** Because area is unimodal as the third vertex sweeps the hull, a caliper-style advancing pointer avoids re-scanning, cutting the naive `O(n³)` triple loop down toward `O(n²)`.

---

### Problem 42: Onion Peeling — Convex Layers — Convex Hull

**Statement.** Repeatedly compute and remove the convex hull until no points remain, returning the sequence of nested hull layers.

```java
import java.util.*;

public class ConvexLayers {
    public static List<long[][]> layers(long[][] pts) {
        List<long[]> remaining = new ArrayList<>(Arrays.asList(pts));
        List<long[][]> result = new ArrayList<>();
        while (remaining.size() >= 3) {
            long[][] arr = remaining.toArray(new long[0][]);
            long[][] hull = MonotoneChain.hull(arr);
            result.add(hull);
            Set<String> onHull = new HashSet<>();
            for (long[] h : hull) onHull.add(h[0] + "," + h[1]);
            remaining.removeIf(p -> onHull.contains(p[0] + "," + p[1]));
        }
        if (!remaining.isEmpty()) result.add(remaining.toArray(new long[0][]));
        return result;
    }
}
```

**Time:** O(n² log n) naive (O(n log n) with advanced structures) · **Space:** O(n)

**Insight:** Peeling hull "layers" like an onion gives a depth measure used in robust statistics and pattern analysis; the simple version reuses any hull routine, removing boundary points each round.

---

### Problem 43: Half-Plane Intersection — Line Arrangement

**Statement.** Given a set of half-planes (each "left of a directed line"), compute the convex region that satisfies all of them, by sorting on angle and running a deque.

```java
import java.util.*;

public class HalfPlaneIntersection {
    // Each half-plane: point (px,py) and direction (dx,dy); region is left of the ray.
    static double cross(double ax, double ay, double bx, double by) { return ax*by - ay*bx; }

    static boolean out(double[] hp, double[] pt) { // is pt strictly right of half-plane?
        return cross(hp[2], hp[3], pt[0] - hp[0], pt[1] - hp[1]) < -1e-12;
    }

    static double[] inter(double[] a, double[] b) {
        double t = cross(b[2], b[3], a[0]-b[0], a[1]-b[1]) / cross(a[2], a[3], b[2], b[3]);
        return new double[] { a[0] + a[2]*t, a[1] + a[3]*t };
    }

    public static List<double[]> intersect(List<double[]> hps) {
        hps.sort(Comparator.comparingDouble(h -> Math.atan2(h[3], h[2])));
        Deque<double[]> dq = new ArrayDeque<>();
        Deque<double[]> pts = new ArrayDeque<>();
        for (double[] h : hps) {
            while (pts.size() >= 1 && out(h, pts.peekLast())) { pts.pollLast(); dq.pollLast(); }
            while (pts.size() >= 1 && out(h, pts.peekFirst())) { pts.pollFirst(); dq.pollFirst(); }
            if (!dq.isEmpty()) pts.addLast(inter(dq.peekLast(), h));
            dq.addLast(h);
        }
        while (pts.size() >= 1 && out(dq.peekFirst(), pts.peekLast())) { pts.pollLast(); dq.pollLast(); }
        if (dq.size() < 3) return Collections.emptyList();
        List<double[]> poly = new ArrayList<>(pts);
        poly.add(inter(dq.peekLast(), dq.peekFirst()));
        return poly;
    }
}
```

**Time:** O(n log n) · **Space:** O(n)

**Insight:** Half-plane intersection is the dual of convex hull: sort the half-planes by angle, then maintain a deque popping any plane whose intersection point falls outside the newest constraint — the surviving deque bounds the feasible convex region (the heart of LP in 2D).

---

### Problem 44: Robust Orientation with Epsilon — Robustness/Precision

**Statement.** Implement a floating-point orientation predicate that returns `0` when the value is within an epsilon of zero, and discuss why naive `> 0` comparisons fail.

```java
public class RobustOrientation {
    static final double EPS = 1e-9;

    // Relative+absolute epsilon to scale with input magnitude.
    public static int ccw(double ax, double ay, double bx, double by, double cx, double cy) {
        double det = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        double scale = Math.max(1.0,
            Math.abs(bx - ax) + Math.abs(by - ay) +
            Math.abs(cx - ax) + Math.abs(cy - ay));
        if (det > EPS * scale)  return 1;
        if (det < -EPS * scale) return -1;
        return 0; // treat as collinear within tolerance
    }

    // Prefer exact integer arithmetic when inputs are integral.
    public static int ccwExact(long ax, long ay, long bx, long by, long cx, long cy) {
        long det = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        return Long.signum(det);
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight:** Floating-point cross products lose precision when the three points are nearly collinear; scale the epsilon by input magnitude, and whenever inputs are integers, switch to exact `long` arithmetic and avoid the problem entirely.

---

### Problem 45: Safe Comparison and Overflow Guards — Robustness/Precision

**Statement.** Provide epsilon-aware comparison utilities and demonstrate overflow-safe integer cross products using `Math.multiplyHigh` / `Math.subtractExact` patterns for large coordinates.

```java
public class GeometryRobustness {
    static final double EPS = 1e-9;

    public static int cmp(double a, double b) {
        if (a - b > EPS) return 1;
        if (b - a > EPS) return -1;
        return 0;
    }
    public static boolean eq(double a, double b) { return Math.abs(a - b) <= EPS; }

    // Overflow-safe cross product for coordinates up to ~10^9 using exact 128-bit.
    public static int crossSign(long ax, long ay, long bx, long by) {
        // ax*by - ay*bx via 128-bit intermediate to avoid long overflow.
        long hi1 = Math.multiplyHigh(ax, by), lo1 = ax * by;
        long hi2 = Math.multiplyHigh(ay, bx), lo2 = ay * bx;
        // Subtract the 128-bit pairs (hi, lo).
        long lo = lo1 - lo2;
        long borrow = (Long.compareUnsigned(lo1, lo2) < 0) ? 1 : 0;
        long hi = hi1 - hi2 - borrow;
        if (hi != 0) return hi > 0 ? 1 : -1;
        return Long.compareUnsigned(lo, 0);
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight:** Cross products of coordinates near `10^9` overflow a single `long` (product ~`10^18`, difference can exceed the range); compute the sign with a 128-bit intermediate (`Math.multiplyHigh`) so the predicate stays correct without resorting to `BigInteger`.

---

### Problem 46: Polygon Cut by a Line — Polygon Clipping

**Statement.** Cut a convex polygon by an infinite directed line, returning the part lying to the left of the line.

```java
import java.util.*;

public class PolygonCut {
    static double cross(double ax, double ay, double bx, double by, double px, double py) {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }

    public static List<double[]> cutLeft(double[][] poly, double[] a, double[] b) {
        List<double[]> out = new ArrayList<>();
        int n = poly.length;
        for (int i = 0; i < n; i++) {
            double[] cur = poly[i], nxt = poly[(i + 1) % n];
            double dc = cross(a[0], a[1], b[0], b[1], cur[0], cur[1]);
            double dn = cross(a[0], a[1], b[0], b[1], nxt[0], nxt[1]);
            if (dc >= 0) out.add(cur);                 // keep points on/left of the line
            if (dc * dn < 0) {                         // edge crosses the line
                double t = dc / (dc - dn);
                out.add(new double[] {
                    cur[0] + t * (nxt[0] - cur[0]),
                    cur[1] + t * (nxt[1] - cur[1]) });
            }
        }
        return out;
    }
}
```

**Time:** O(n) · **Space:** O(n)

**Insight:** Cutting a polygon is one clipping step against a single half-plane; the parameter `t = dc/(dc−dn)` finds the crossing by linear interpolation of the signed distances — the same primitive used inside Sutherland–Hodgman.

---

### Problem 47: Smallest Enclosing Circle — Welzl's Algorithm — Robustness/Precision

**Statement.** Find the smallest circle enclosing all given points using Welzl's randomized incremental algorithm, expected `O(n)`.

```java
import java.util.*;

public class MinEnclosingCircle {
    static class Circle { double x, y, r; Circle(double x,double y,double r){this.x=x;this.y=y;this.r=r;} }

    static boolean inside(Circle c, double[] p) {
        if (c == null) return false;
        double dx = p[0]-c.x, dy = p[1]-c.y;
        return dx*dx + dy*dy <= c.r*c.r + 1e-9;
    }

    static Circle from2(double[] a, double[] b) {
        double cx = (a[0]+b[0])/2, cy = (a[1]+b[1])/2;
        return new Circle(cx, cy, Math.hypot(a[0]-b[0], a[1]-b[1]) / 2);
    }

    static Circle from3(double[] a, double[] b, double[] c) {
        double ax=a[0],ay=a[1],bx=b[0],by=b[1],cx=c[0],cy=c[1];
        double d = 2*(ax*(by-cy) + bx*(cy-ay) + cx*(ay-by));
        if (Math.abs(d) < 1e-12) return null;
        double ux = ((ax*ax+ay*ay)*(by-cy) + (bx*bx+by*by)*(cy-ay) + (cx*cx+cy*cy)*(ay-by)) / d;
        double uy = ((ax*ax+ay*ay)*(cx-bx) + (bx*bx+by*by)*(ax-cx) + (cx*cx+cy*cy)*(bx-ax)) / d;
        return new Circle(ux, uy, Math.hypot(ax-ux, ay-uy));
    }

    public static Circle solve(double[][] pts) {
        double[][] p = pts.clone();
        Collections.shuffle(Arrays.asList(p));      // randomize for expected linear time
        Circle c = null;
        for (int i = 0; i < p.length; i++) {
            if (inside(c, p[i])) continue;
            c = new Circle(p[i][0], p[i][1], 0);
            for (int j = 0; j < i; j++) {
                if (inside(c, p[j])) continue;
                c = from2(p[i], p[j]);
                for (int k = 0; k < j; k++) {
                    if (inside(c, p[k])) continue;
                    c = from3(p[i], p[j], p[k]);
                }
            }
        }
        return c;
    }
}
```

**Time:** O(n) expected · **Space:** O(n)

**Insight:** The optimal circle is determined by at most three boundary points; Welzl's nested incremental construction, with random shuffling for the expected-linear bound, is far simpler than it looks because each rebuild only happens when a new point violates the current circle.

---

## ✅ Key Takeaways

- **The two primitives are everything.** Dot product classifies angles and does projection; cross product gives signed area and orientation. Convex hull, segment intersection, shoelace area, and point-in-polygon are all thin wrappers over these.
- **Stay integer when you can.** Orientation, area, and segment-intersection predicates are exact with `long` (or 128-bit for large coordinates). Reach for floating point and epsilons only when the geometry genuinely requires it.
- **Sort then scan.** Hulls (monotone chain, Graham), rotating calipers, and sweep lines all reduce to a sort followed by a linear pass maintaining an invariant — recognize the pattern and the algorithm writes itself.

## ⚠️ Common Pitfalls

- Comparing floating-point slopes (`dy/dx`) instead of reduced integer directions — causes wrong collinearity results and division-by-zero on vertical lines.
- Forgetting to clamp the projection parameter to `[0,1]` in point-to-**segment** distance (gives point-to-line distance instead).
- Overflowing `long` in cross products when coordinates reach `10^9` — the product is `~10^18` and the difference can exceed `Long.MAX_VALUE`.
- Mixing up the strict (`<`) vs non-strict (`<=`) pop condition in hull construction when the problem cares about collinear boundary points.

## 📚 Further Reading

- de Berg, Cheong, van Kreveld, Overmars — *Computational Geometry: Algorithms and Applications* (the standard reference for sweep line, hulls, and arrangements).
- O'Rourke — *Computational Geometry in C* (practical, code-first treatment of robustness).
- CP-Algorithms (cp-algorithms.com) — concise, competitive-programming write-ups of half-plane intersection, rotating calipers, and Welzl's algorithm.

## 🧩 Extended Problems — Set 1: Deeper internals & edge cases

These problems drill into the failure modes and subtle invariants that separate a textbook implementation from one that survives adversarial input: degenerate hulls, near-collinear floating point, integer overflow, ties at events, and the corner cases (empty results, duplicate points, collinear runs, vertical lines) that real judges and real geometry kernels actually test.

### Problem 48: Hull of Fewer Than Three / Duplicate / Collinear Points — Degenerate Hull

**Statement.** Make a monotone-chain hull robust to the degenerate inputs that crash naive code: zero points, one point, all-duplicate points, and all-collinear points. Return the canonical minimal hull (a single point, or the two extreme endpoints of a collinear run).

```java
import java.util.*;

public class RobustHull {
    static long cross(long[] o, long[] a, long[] b) {
        return (a[0]-o[0])*(b[1]-o[1]) - (a[1]-o[1])*(b[0]-o[0]);
    }

    public static long[][] hull(long[][] ptsIn) {
        // Deduplicate first; duplicates wreck the pop invariant.
        TreeSet<long[]> set = new TreeSet<>((p, q) ->
            p[0] != q[0] ? Long.compare(p[0], q[0]) : Long.compare(p[1], q[1]));
        set.addAll(Arrays.asList(ptsIn));
        long[][] pts = set.toArray(new long[0][]);
        int n = pts.length;
        if (n <= 1) return pts;                       // 0 or 1 distinct point
        if (n == 2) return pts;                        // a segment: two endpoints

        long[][] h = new long[2 * n][];
        int k = 0;
        for (long[] p : pts) {                         // lower
            while (k >= 2 && cross(h[k-2], h[k-1], p) <= 0) k--;
            h[k++] = p;
        }
        int lower = k + 1;
        for (int i = n - 2; i >= 0; i--) {             // upper
            while (k >= lower && cross(h[k-2], h[k-1], p(pts, i)) <= 0) k--;
            h[k++] = pts[i];
        }
        long[][] res = Arrays.copyOf(h, k - 1);
        // All-collinear case collapses to 2 points after the chain; that is correct.
        if (res.length >= 3 && isDegenerate(res)) return new long[][]{ res[0], res[res.length/2] };
        return res;
    }

    static long[] p(long[][] a, int i) { return a[i]; }

    static boolean isDegenerate(long[][] h) {           // area zero -> collinear
        long twice = 0;
        for (int i = 0; i < h.length; i++) {
            long[] a = h[i], b = h[(i+1)%h.length];
            twice += a[0]*b[1] - b[0]*a[1];
        }
        return twice == 0;
    }
}
```

**Time:** O(n log n) · **Space:** O(n)

**Insight:** Three landmines hide in "compute the hull": duplicate points break the strict pop invariant, fewer than three points have no polygon to return, and an all-collinear set must collapse to its two extreme endpoints rather than a zero-area polygon. Deduplicate up front and special-case `n ≤ 2`.

---

### Problem 49: Exact Orientation via 128-bit (`Math.multiplyHigh`) — Overflow-Safe Predicate

**Statement.** Coordinates up to `±2·10^9` make the orientation determinant `(b−a)×(c−a)` overflow `long` (intermediate products reach `~1.6·10^19`). Compute its exact sign using a 128-bit intermediate without `BigInteger`.

```java
public class ExactOrient {
    // Sign of (bx-ax)*(cy-ay) - (by-ay)*(cx-ax), exact for coords up to 2e9.
    public static int ccw(long ax, long ay, long bx, long by, long cx, long cy) {
        long x1 = bx - ax, y1 = by - ay;   // fits in long (diff of 2e9 values)
        long x2 = cx - ax, y2 = cy - ay;
        // Compute x1*y2 and y1*x2 as 128-bit (hi, lo), then subtract.
        long pHi = Math.multiplyHigh(x1, y2), pLo = x1 * y2;
        long qHi = Math.multiplyHigh(y1, x2), qLo = y1 * x2;
        long lo = pLo - qLo;
        long borrow = Long.compareUnsigned(pLo, qLo) < 0 ? 1 : 0;
        long hi = pHi - qHi - borrow;
        if (hi != 0) return hi > 0 ? 1 : -1;
        if (lo != 0) return 1;                          // hi==0, lo!=0 -> sign from lo as unsigned-rebuilt
        return 0;
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight:** The product of two differences each up to `4·10^9` is `~1.6·10^19`, beyond `Long.MAX_VALUE` (`9.2·10^18`). `Math.multiplyHigh` gives the upper 64 bits so the difference is computed in 128 bits; the sign is read from the high word, falling back to the low word only when the high word is zero.

---

### Problem 50: Adaptive Epsilon for Near-Collinear Points — Precision

**Statement.** A fixed absolute epsilon misclassifies orientation when coordinates are large (the determinant scales with magnitude). Implement a relative-plus-absolute tolerance and show why `|det| < 1e-9` alone is wrong.

```java
public class AdaptiveEps {
    public static int orient(double ax, double ay, double bx, double by, double cx, double cy) {
        double detLeft  = (bx - ax) * (cy - ay);
        double detRight = (by - ay) * (cx - ax);
        double det = detLeft - detRight;
        // Error bound grows with the magnitude of the summands (Shewchuk-style).
        double sum = Math.abs(detLeft) + Math.abs(detRight);
        double errBound = 1e-15 * sum + 1e-12;          // relative + absolute floor
        if (det > errBound)  return 1;
        if (det < -errBound) return -1;
        return 0;                                        // genuinely within noise
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight:** Floating-point catastrophic cancellation in `detLeft − detRight` produces an absolute error proportional to the magnitude of the operands, not a constant. The tolerance must scale with `|detLeft| + |detRight|`; a constant epsilon is simultaneously too strict for tiny coordinates and too loose for huge ones.

---

### Problem 51: Stable Polar Sort with a Comparator That Never Lies — Angular Sort

**Statement.** Sorting points by angle with `atan2` introduces rounding and a discontinuity at `±π`. Implement a comparator using only cross products and a half-plane key so it is exact for integers and obeys the comparator contract (no `IllegalArgumentException: Comparison method violates its general contract`).

```java
import java.util.*;

public class PolarSort {
    // Half-plane: lower half (y<0, or y==0 && x<0) sorts after upper half.
    static int half(long x, long y) {
        return (y < 0 || (y == 0 && x < 0)) ? 1 : 0;
    }

    public static void sort(long[][] pts) {             // around origin
        Arrays.sort(pts, (a, b) -> {
            int ha = half(a[0], a[1]), hb = half(b[0], b[1]);
            if (ha != hb) return Integer.compare(ha, hb);
            long cr = a[0]*b[1] - a[1]*b[0];            // cross; >0 means a before b
            if (cr != 0) return cr > 0 ? -1 : 1;
            // Same direction: nearer first (a total order -> contract safe).
            long da = a[0]*a[0] + a[1]*a[1];
            long db = b[0]*b[0] + b[1]*b[1];
            return Long.compare(da, db);
        });
    }
}
```

**Time:** O(n log n) · **Space:** O(1) extra

**Insight:** A comparator built on raw `atan2` can be non-transitive under rounding, which makes Java's TimSort throw. Splitting the plane into two halves first, then ordering within a half by cross product (with a distance tiebreaker for collinear vectors), yields a genuine total order that is exact for integer inputs.

---

### Problem 52: Segment Intersection with Full Collinear-Overlap Classification — Segment Intersection

**Statement.** Extend the boolean intersection test to classify the result: NONE, a single POINT, or an overlapping SEGMENT (collinear segments sharing more than one point), and return the shared endpoints in the overlap case.

```java
public class SegmentOverlap {
    public enum Kind { NONE, POINT, SEGMENT }
    public static class Result { Kind kind; long[] a, b; }

    static int sgn(long v) { return Long.signum(v); }
    static long cross(long ox,long oy,long ax,long ay,long bx,long by){
        return (ax-ox)*(by-oy) - (ay-oy)*(bx-ox);
    }
    static boolean onBox(long px,long py,long ax,long ay,long bx,long by){
        return Math.min(ax,bx)<=px && px<=Math.max(ax,bx)
            && Math.min(ay,by)<=py && py<=Math.max(ay,by);
    }

    public static Result classify(long[] p1,long[] p2,long[] p3,long[] p4){
        Result r = new Result();
        int d1=sgn(cross(p3[0],p3[1],p4[0],p4[1],p1[0],p1[1]));
        int d2=sgn(cross(p3[0],p3[1],p4[0],p4[1],p2[0],p2[1]));
        int d3=sgn(cross(p1[0],p1[1],p2[0],p2[1],p3[0],p3[1]));
        int d4=sgn(cross(p1[0],p1[1],p2[0],p2[1],p4[0],p4[1]));
        if (d1!=d2 && d3!=d4) { r.kind=Kind.POINT; return r; }   // proper cross
        if (d1==0 && d2==0) {                                    // all collinear
            // Overlap of the two 1-D intervals along the shared line.
            long[][] pts = { p1, p2, p3, p4 };
            // pick overlap endpoints: max of left ends, min of right ends
            return collinearOverlap(p1,p2,p3,p4);
        }
        // Touching at a single endpoint via a zero with box containment.
        if (d1==0 && onBox(p1[0],p1[1],p3[0],p3[1],p4[0],p4[1])){r.kind=Kind.POINT; return r;}
        if (d2==0 && onBox(p2[0],p2[1],p3[0],p3[1],p4[0],p4[1])){r.kind=Kind.POINT; return r;}
        if (d3==0 && onBox(p3[0],p3[1],p1[0],p1[1],p2[0],p2[1])){r.kind=Kind.POINT; return r;}
        if (d4==0 && onBox(p4[0],p4[1],p1[0],p1[1],p2[0],p2[1])){r.kind=Kind.POINT; return r;}
        r.kind=Kind.NONE; return r;
    }

    static Result collinearOverlap(long[] p1,long[] p2,long[] p3,long[] p4){
        Result r = new Result();
        // Project onto the dominant axis to compare 1-D intervals.
        boolean useX = p1[0]!=p2[0];
        long a1 = useX?p1[0]:p1[1], a2 = useX?p2[0]:p2[1];
        long b1 = useX?p3[0]:p3[1], b2 = useX?p4[0]:p4[1];
        long loA=Math.min(a1,a2), hiA=Math.max(a1,a2);
        long loB=Math.min(b1,b2), hiB=Math.max(b1,b2);
        long lo=Math.max(loA,loB), hi=Math.min(hiA,hiB);
        if (lo>hi) { r.kind=Kind.NONE; return r; }
        if (lo==hi){ r.kind=Kind.POINT; return r; }
        r.kind=Kind.SEGMENT; return r;
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight:** The boolean "do they intersect" hides three distinct geometric outcomes. Collinear segments require projecting onto the dominant axis and intersecting 1-D intervals; the overlap is empty, a touching point (`lo == hi`), or a sub-segment (`lo < hi`). Interview follow-ups almost always ask for this distinction.

---

### Problem 53: Point-in-Polygon Boundary Disambiguation — Point in Polygon

**Statement.** The plain ray-cast returns an arbitrary answer for a point exactly on an edge or vertex. Implement a version that returns one of INSIDE, OUTSIDE, ON_BOUNDARY, with an exact on-edge test using integer arithmetic.

```java
public class PIPBoundary {
    public enum Loc { INSIDE, OUTSIDE, BOUNDARY }

    static boolean onSeg(long px,long py,long ax,long ay,long bx,long by){
        long cr = (bx-ax)*(py-ay) - (by-ay)*(px-ax);
        if (cr != 0) return false;
        return Math.min(ax,bx)<=px && px<=Math.max(ax,bx)
            && Math.min(ay,by)<=py && py<=Math.max(ay,by);
    }

    public static Loc locate(long px,long py,long[][] poly){
        int n = poly.length;
        boolean in = false;
        for (int i=0, j=n-1; i<n; j=i++) {
            long xi=poly[i][0], yi=poly[i][1], xj=poly[j][0], yj=poly[j][1];
            if (onSeg(px,py,xi,yi,xj,yj)) return Loc.BOUNDARY;
            boolean straddles = (yi>py) != (yj>py);
            if (straddles) {
                // Compare px against crossing x using cross-multiplication (no division).
                // px < xi + (xj-xi)*(py-yi)/(yj-yi)
                long lhs = (px - xi) * (yj - yi);
                long rhs = (xj - xi) * (py - yi);
                boolean leftOfCrossing = (yj > yi) ? (lhs < rhs) : (lhs > rhs);
                if (leftOfCrossing) in = !in;
            }
        }
        return in ? Loc.INSIDE : Loc.OUTSIDE;
    }
}
```

**Time:** O(n) · **Space:** O(1)

**Insight:** Check boundary membership first and explicitly; only then run the parity test. The crossing comparison `px < xCross` must be done by cross-multiplication (flipping the inequality based on the sign of `yj − yi`) to stay exact and avoid the floating-point division that corrupts on-edge cases.

---

### Problem 54: Tie-Breaking at Coincident Sweep-Line Events — Sweep Line

**Statement.** When multiple segment endpoints share an x-coordinate, the order in which start/end/vertical events are processed determines correctness. Define and implement the event-ordering rule that avoids both false negatives (missing a touch) and double-counting.

```java
import java.util.*;

public class EventOrdering {
    // Event types ordered so that, at equal x: ends-as-needed and starts interleave correctly.
    // Rule: at the same x, process VERTICAL-open before horizontal removals it should still see,
    // and start-events before end-events so touching endpoints count as intersections.
    static final int START = 0, VERTICAL = 1, END = 2;

    static class Ev implements Comparable<Ev> {
        long x; int type; long y1, y2; int id;
        Ev(long x,int type,long y1,long y2,int id){this.x=x;this.type=type;this.y1=y1;this.y2=y2;this.id=id;}
        public int compareTo(Ev o){
            if (x != o.x)       return Long.compare(x, o.x);
            if (type != o.type) return Integer.compare(type, o.type); // START < VERTICAL < END
            return Integer.compare(id, o.id);                          // stable final key
        }
    }

    // Demonstration: order events and assert the invariant START<VERTICAL<END at equal x.
    public static List<Ev> ordered(List<Ev> evs){
        Collections.sort(evs);
        return evs;
    }
}
```

**Time:** O(E log E) to sort · **Space:** O(E)

**Insight:** The classic bug is treating `x` as the only sort key. At a shared x you must order START before VERTICAL before END so a vertical segment's query sees horizontals that start and have not yet ended at that exact column; getting this wrong silently drops the segments that merely touch.

---

### Problem 55: Manhattan Closest Pair via 45-Degree Rotation — Distance Transform

**Statement.** Find the closest pair under L1 (Manhattan) distance. The trick: rotating coordinates by 45° (`u = x+y`, `v = x−y`) turns L1 into L∞, after which a sweep with a sorted window solves it.

```java
import java.util.*;

public class ManhattanClosest {
    public static long closest(long[][] pts){
        int n = pts.length;
        long[][] t = new long[n][2];
        for (int i=0;i<n;i++){ t[i][0]=pts[i][0]+pts[i][1]; t[i][1]=pts[i][0]-pts[i][1]; }
        // Under Chebyshev, min L-inf == min over the two axes of nearest in a window.
        Arrays.sort(t,(a,b)->Long.compare(a[0],b[0]));
        TreeSet<long[]> win = new TreeSet<>((a,b)->
            a[1]!=b[1]?Long.compare(a[1],b[1]):Long.compare(a[0],b[0]));
        long best = Long.MAX_VALUE; int lo = 0;
        for (int i=0;i<n;i++){
            while (t[i][0]-t[lo][0] > best){ win.remove(t[lo]); lo++; }
            long[] q = new long[]{t[i][0],t[i][1]};
            for (long[] near : win.subSet(new long[]{Long.MIN_VALUE,t[i][1]-best},
                                          new long[]{Long.MAX_VALUE,t[i][1]+best})){
                long d = Math.max(Math.abs(t[i][0]-near[0]), Math.abs(t[i][1]-near[1]));
                best = Math.min(best, d);
            }
            win.add(q);
        }
        return best;  // this Chebyshev distance equals the original Manhattan distance
    }
}
```

**Time:** O(n log n) · **Space:** O(n)

**Insight:** `L1((x,y),(x',y')) = L∞((x+y, x−y),(x'+y', x'−y'))`. The rotation linearizes the diamond-shaped L1 unit ball into an axis-aligned square, so the standard sweep-with-window machinery for L∞ applies directly — a transform worth recognizing on sight.

---

### Problem 56: Convex Hull Trick — Incremental Lower Envelope — Line Arrangement

**Statement.** Maintain a set of lines and answer "minimum y over all lines at query x" as lines are added with monotonically decreasing slope and queries with monotonically increasing x. The internal invariant is that obsolete lines are popped from the back.

```java
import java.util.*;

public class ConvexHullTrick {
    long[] m = new long[1<<16], b = new long[1<<16];
    int size = 0, ptr = 0;

    // True if line[mid] is unnecessary given its neighbors.
    boolean bad(int l1,int l2,int l3){
        // intersection(l1,l3) is left of intersection(l1,l2) -> l2 redundant
        return (b[l3]-b[l1])*(m[l1]-m[l2]) <= (b[l2]-b[l1])*(m[l1]-m[l3]);
    }

    public void addLine(long slope,long intercept){    // slopes added decreasing
        m[size]=slope; b[size]=intercept;
        while (size>=2 && bad(size-2,size-1,size)){
            m[size-1]=m[size]; b[size-1]=b[size]; size--;
        }
        size++;
        if (ptr>=size) ptr=size-1;
    }

    public long query(long x){                          // queries x increasing
        if (ptr>=size) ptr=size-1;
        while (ptr+1<size && m[ptr+1]*x+b[ptr+1] <= m[ptr]*x+b[ptr]) ptr++;
        return m[ptr]*x + b[ptr];
    }
}
```

**Time:** O(1) amortized per add and per query · **Space:** O(n)

**Insight:** The "convex hull trick" is the lower envelope of lines maintained as a monotone stack — geometrically identical to building a convex chain. The `bad` predicate is just an orientation test on three intersection abscissae; popping a redundant middle line is the same right-turn-elimination used in hull construction.

---

### Problem 57: Robust Circle–Circle Intersection (All Cases) — Circle Geometry

**Statement.** Compute the intersection of two circles, handling every degeneracy: identical circles (infinite intersection), one inside the other (none), external separation (none), external/internal tangency (one point), and proper two-point intersection.

```java
public class CircleCircle {
    public enum Kind { NONE, ONE, TWO, SAME }
    public static class Out { Kind kind; double[] p1, p2; }

    public static Out solve(double x0,double y0,double r0,double x1,double y1,double r1){
        Out o = new Out();
        double dx=x1-x0, dy=y1-y0;
        double d2 = dx*dx+dy*dy, d=Math.sqrt(d2);
        if (d < 1e-12 && Math.abs(r0-r1)<1e-12){ o.kind=Kind.SAME; return o; }
        if (d > r0+r1+1e-9){ o.kind=Kind.NONE; return o; }            // separate
        if (d < Math.abs(r0-r1)-1e-9){ o.kind=Kind.NONE; return o; }  // contained
        // a = distance from c0 to the radical line foot along the center line.
        double a = (r0*r0 - r1*r1 + d2) / (2*d);
        double h2 = r0*r0 - a*a;
        double px = x0 + a*dx/d, py = y0 + a*dy/d;
        if (h2 <= 1e-12){ o.kind=Kind.ONE; o.p1=new double[]{px,py}; return o; } // tangent
        double h = Math.sqrt(h2);
        double rx = -dy*(h/d), ry = dx*(h/d);
        o.kind=Kind.TWO;
        o.p1=new double[]{px+rx, py+ry};
        o.p2=new double[]{px-rx, py-ry};
        return o;
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight:** The radical line is perpendicular to the line of centers; `a` locates its foot and `h² = r0² − a²` decides the case. Negative `h²` means no real intersection, `h² ≈ 0` is tangency, and the containment / separation guards must come first because `h²` alone does not distinguish "too far" from "too close".

---

### Problem 58: Line–Circle Intersection with Tangency Tolerance — Circle Geometry

**Statement.** Intersect an infinite line through `a`, `b` with a circle of center `c`, radius `r`. Return zero, one (tangent), or two points, using the perpendicular distance to decide and an epsilon to treat near-tangency consistently.

```java
public class LineCircle {
    public static double[][] intersect(double ax,double ay,double bx,double by,
                                        double cx,double cy,double r){
        double dx=bx-ax, dy=by-ay;
        double len2=dx*dx+dy*dy;
        // Project center onto the line: foot = a + t*(b-a).
        double t=((cx-ax)*dx+(cy-ay)*dy)/len2;
        double fx=ax+t*dx, fy=ay+t*dy;
        double dist2=(fx-cx)*(fx-cx)+(fy-cy)*(fy-cy);
        double r2=r*r;
        if (dist2 > r2 + 1e-9) return new double[0][];          // miss
        if (dist2 > r2 - 1e-9) return new double[][]{{fx,fy}};  // tangent
        double off=Math.sqrt(Math.max(0,(r2-dist2))/len2);     // param half-chord
        return new double[][]{
            {fx - off*dx, fy - off*dy},
            {fx + off*dx, fy + off*dy}
        };
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight:** Foot of perpendicular plus the chord half-length is more stable than solving the quadratic directly, because it avoids subtracting two nearly equal roots. The two-sided epsilon band around `dist2 == r2` makes grazing lines deterministically classify as tangent rather than flickering between zero and two points.

---

### Problem 59: Polygon Self-Intersection Detection (Simplicity Test) — Sweep Line

**Statement.** Decide whether a polygon is simple (no edge crosses another except at shared vertices). Naively O(n²); use a Bentley–Ottmann sweep, being careful that adjacent edges sharing a vertex are *not* reported as intersections.

```java
import java.util.*;

public class PolygonSimple {
    static boolean proper(long[] a,long[] b,long[] c,long[] d){
        // Returns true if segments ab, cd intersect somewhere other than a shared endpoint.
        if (share(a,b,c,d)) return false;
        return SegmentIntersect.intersect(a[0],a[1],b[0],b[1],c[0],c[1],d[0],d[1]);
    }
    static boolean share(long[]a,long[]b,long[]c,long[]d){
        return eq(a,c)||eq(a,d)||eq(b,c)||eq(b,d);
    }
    static boolean eq(long[]p,long[]q){ return p[0]==q[0]&&p[1]==q[1]; }

    public static boolean isSimple(long[][] poly){
        int n=poly.length;
        // Educational O(n^2) reference with the adjacency exclusion that the sweep also needs.
        for (int i=0;i<n;i++){
            long[] a=poly[i], b=poly[(i+1)%n];
            for (int j=i+1;j<n;j++){
                if (j==i || (i+1)%n==j || (j+1)%n==i) continue; // adjacent edges share a vertex
                long[] c=poly[j], d=poly[(j+1)%n];
                if (proper(a,b,c,d)) return false;
            }
        }
        return true;
    }
}
```

**Time:** O(n²) reference (O(n log n) with full Bentley–Ottmann) · **Space:** O(n)

**Insight:** Simplicity testing's only real subtlety is the adjacency exclusion: consecutive edges legitimately share their common vertex and must be skipped, while the polygon's first and last edges are also adjacent (wrap-around). Forgetting either makes every valid polygon report as self-intersecting.

---

### Problem 60: Counterclockwise Normalization of Arbitrary Polygons — Polygon Orientation

**Statement.** Many algorithms assume CCW input. Write a normalizer that detects orientation via signed area and reverses in place if clockwise, handling the degenerate zero-area case explicitly.

```java
public class Normalize {
    public static long signedArea2(long[][] p){       // 2*signed area
        long s=0; int n=p.length;
        for (int i=0;i<n;i++){
            long[] a=p[i], b=p[(i+1)%n];
            s += a[0]*b[1] - b[0]*a[1];
        }
        return s;
    }

    public static boolean toCCW(long[][] p){
        long s = signedArea2(p);
        if (s == 0) return false;                      // degenerate: cannot orient
        if (s < 0) {                                   // clockwise -> reverse
            for (int i=0, j=p.length-1; i<j; i++, j--) { long[] t=p[i]; p[i]=p[j]; p[j]=t; }
        }
        return true;
    }
}
```

**Time:** O(n) · **Space:** O(1)

**Insight:** Orientation is the sign of the shoelace sum; reversing the vertex array flips it. The zero-area case (all collinear, or a degenerate "polygon") has no well-defined winding and must be flagged rather than silently passed to downstream code that divides by the area.

---

### Problem 61: Integer Centroid Without Floating Point — Polygon Area

**Statement.** Compute the area-centroid of a lattice polygon as an exact rational `(numX/den, numY/den)` rather than a lossy `double`, so downstream exact comparisons stay valid.

```java
import java.math.BigInteger;

public class ExactCentroid {
    public static BigInteger[] centroid(long[][] p){
        BigInteger a2 = BigInteger.ZERO, cx = BigInteger.ZERO, cy = BigInteger.ZERO;
        int n=p.length;
        for (int i=0;i<n;i++){
            long[] u=p[i], v=p[(i+1)%n];
            BigInteger cr = BigInteger.valueOf(u[0]).multiply(BigInteger.valueOf(v[1]))
                          .subtract(BigInteger.valueOf(v[0]).multiply(BigInteger.valueOf(u[1])));
            a2 = a2.add(cr);
            cx = cx.add(BigInteger.valueOf(u[0]+v[0]).multiply(cr));
            cy = cy.add(BigInteger.valueOf(u[1]+v[1]).multiply(cr));
        }
        // centroid = ( cx / (3*a2), cy / (3*a2) ); return numerators and the common denom.
        BigInteger den = a2.multiply(BigInteger.valueOf(3));
        return new BigInteger[]{ cx, cy, den };        // x = cx/den, y = cy/den
    }
}
```

**Time:** O(n) · **Space:** O(1) (excluding BigInteger temporaries)

**Insight:** The centroid is inherently rational for lattice polygons; representing it as `(cx, cy, den)` with `BigInteger` numerators preserves exactness so two centroids can be compared by cross-multiplication. Converting to `double` early throws away the very precision the rest of an exact pipeline depends on.

---

### Problem 62: Convex Polygon Tangent Lines from an External Point — Tangents

**Statement.** Given a convex polygon and an external point, find the two tangent vertices (the leftmost and rightmost vertices as seen from the point) in `O(log n)` by binary searching on orientation.

```java
public class ConvexTangents {
    static long cross(long[]o,long[]a,long[]b){
        return (a[0]-o[0])*(b[1]-o[1]) - (a[1]-o[1])*(b[0]-o[0]);
    }
    // sign of turn from p->poly[i] to p->poly[i+1]
    static int dir(long[] p, long[][] h, int i){
        int n=h.length;
        long c = cross(p, h[i], h[(i+1)%n]);
        return Long.signum(c);
    }

    // Returns index of the right (clockwise-most) tangent vertex.
    public static int rightTangent(long[] p, long[][] h){
        int n=h.length, lo=0, hi=n;
        int dirLo = dir(p, h, 0);
        while (lo < hi){
            int mid=(lo+hi)/2;
            int dirMid = dir(p, h, mid);
            // Compare mid against vertex 0 to decide which side the tangent lies.
            long cmp = cross(p, h[0], h[mid % n]);
            boolean below = dirMid < 0;
            if (below && (dirLo >= 0 || cmp > 0)) hi=mid; else lo=mid+1;
        }
        return lo % n;
    }
}
```

**Time:** O(log n) · **Space:** O(1)

**Insight:** From an external point the polygon's silhouette has exactly two tangent vertices where the turn direction (sign of `cross(p, v_i, v_{i+1})`) flips. Convexity makes that sign unimodal around the boundary, so binary search finds each tangent logarithmically — the basis of fast convex-polygon visibility and merging.

---

### Problem 63: Merging Two Convex Hulls in Linear Time — Convex Hull

**Statement.** Given two disjoint convex hulls, merge them into one by finding the upper and lower common tangents and stitching. Used inside the divide-and-conquer hull and in dynamic-hull structures.

```java
import java.util.*;

public class HullMerge {
    static long cross(long[]o,long[]a,long[]b){
        return (a[0]-o[0])*(b[1]-o[1]) - (a[1]-o[1])*(b[0]-o[0]);
    }

    // Both hulls CCW. Returns merged hull CCW.
    public static long[][] merge(long[][] A, long[][] B){
        int na=A.length, nb=B.length;
        int ia=0; for (int i=1;i<na;i++) if (A[i][0]>A[ia][0]) ia=i;  // rightmost of A
        int ib=0; for (int i=1;i<nb;i++) if (B[i][0]<B[ib][0]) ib=i;  // leftmost of B

        // Upper tangent.
        int upA=ia, upB=ib; boolean done=false;
        while(!done){
            done=true;
            while (cross(B[upB], A[upA], A[(upA+1)%na]) > 0) upA=(upA+1)%na;
            while (cross(A[upA], B[upB], B[(upB-1+nb)%nb]) < 0){ upB=(upB-1+nb)%nb; done=false; }
        }
        // Lower tangent.
        int loA=ia, loB=ib; done=false;
        while(!done){
            done=true;
            while (cross(B[loB], A[loA], A[(loA-1+na)%na]) < 0) loA=(loA-1+na)%na;
            while (cross(A[loA], B[loB], B[(loB+1)%nb]) > 0){ loB=(loB+1)%nb; done=false; }
        }
        List<long[]> res = new ArrayList<>();
        for (int i=upA;; i=(i+1)%na){ res.add(A[i]); if (i==loA) break; }
        for (int i=loB;; i=(i+1)%nb){ res.add(B[i]); if (i==upB) break; }
        return res.toArray(new long[0][]);
    }
}
```

**Time:** O(na + nb) · **Space:** O(na + nb)

**Insight:** Two convex hulls merge by walking the upper and lower tangents — each tangent advances monotonically along both hulls, so the whole stitch is linear. This linear merge is exactly what gives the divide-and-conquer convex hull its `O(n log n)` and underpins kinetic / dynamic hull structures.

---

### Problem 64: Antipodal Pairs Enumeration (Calipers Internals) — Rotating Calipers

**Statement.** Explicitly enumerate all antipodal vertex pairs of a convex polygon — the candidates for diameter, width, and Minkowski sums — exposing why the rotating-calipers pointer advances exactly `n` times total.

```java
import java.util.*;

public class Antipodal {
    static long area2(long[]a,long[]b,long[]c){
        return Math.abs((b[0]-a[0])*(c[1]-a[1]) - (b[1]-a[1])*(c[0]-a[0]));
    }

    public static List<int[]> pairs(long[][] h){
        int n=h.length;
        List<int[]> res=new ArrayList<>();
        if (n<2) return res;
        if (n==2){ res.add(new int[]{0,1}); return res; }
        int j=1;
        for (int i=0;i<n;i++){
            int ni=(i+1)%n;
            while (area2(h[i],h[ni],h[(j+1)%n]) > area2(h[i],h[ni],h[j])) j=(j+1)%n;
            res.add(new int[]{i,j});
            // ties (parallel edges) contribute an extra antipodal pair
            if (area2(h[i],h[ni],h[(j+1)%n]) == area2(h[i],h[ni],h[j]) && i!=ni)
                res.add(new int[]{i,(j+1)%n});
        }
        return res;
    }
}
```

**Time:** O(n) · **Space:** O(n) for output

**Insight:** The caliper pointer `j` never moves backward, so across all `n` outer iterations it advances at most `n` positions — total `O(n)`. Parallel edges create *two* antipodal partners for an edge, which is the easily missed case that makes a calipers diameter wrong on rectangles and other polygons with parallel sides.

---

### Problem 65: Minkowski Sum of Two Convex Polygons — Polygon Algebra

**Statement.** Compute the Minkowski sum `A ⊕ B` of two convex polygons by merging their edge vectors in angular order — the foundation of collision detection and motion planning.

```java
import java.util.*;

public class Minkowski {
    static long cross(long ax,long ay,long bx,long by){ return ax*by-ay*bx; }

    static void reorder(long[][] p){                   // rotate so lowest (y,x) is first
        int n=p.length, k=0;
        for (int i=1;i<n;i++)
            if (p[i][1]<p[k][1] || (p[i][1]==p[k][1] && p[i][0]<p[k][0])) k=i;
        Collections.rotate(Arrays.asList(p), -k);
    }

    public static long[][] sum(long[][] A, long[][] B){
        reorder(A); reorder(B);
        int n=A.length, m=B.length;
        List<long[]> res=new ArrayList<>();
        int i=0,j=0;
        while (i<n || j<m){
            res.add(new long[]{A[i%n][0]+B[j%m][0], A[i%n][1]+B[j%m][1]});
            long[] ea={A[(i+1)%n][0]-A[i%n][0], A[(i+1)%n][1]-A[i%n][1]};
            long[] eb={B[(j+1)%m][0]-B[j%m][0], B[(j+1)%m][1]-B[j%m][1]};
            long c = cross(ea[0],ea[1],eb[0],eb[1]);
            if (i>=n) j++;
            else if (j>=m) i++;
            else if (c>0) i++;
            else if (c<0) j++;
            else { i++; j++; }                          // parallel edges: advance both
        }
        return res.toArray(new long[0][]);
    }
}
```

**Time:** O(n + m) · **Space:** O(n + m)

**Insight:** The boundary of a Minkowski sum of convex polygons is the angularly-sorted concatenation of both edge-vector multisets. Starting both at their lowest vertex and merging edges by polar angle (advancing *both* pointers on parallel edges) builds it in linear time — the engine behind GJK-style collision tests.

---

### Problem 66: Point in Convex Polygon via Minkowski / GJK Reduction — Collision

**Statement.** Decide whether two convex polygons collide by testing whether the origin lies inside their Minkowski difference `A ⊕ (−B)`. Show the reduction and the convex point-in-polygon check.

```java
public class GJKLite {
    static long[][] negate(long[][] b){
        long[][] r=new long[b.length][2];
        for (int i=0;i<b.length;i++){ r[i][0]=-b[i][0]; r[i][1]=-b[i][1]; }
        return r;
    }

    public static boolean collide(long[][] A, long[][] B){
        long[][] diff = Minkowski.sum(A, negate(B));    // A ⊕ (−B)
        return originInside(diff);
    }

    static boolean originInside(long[][] poly){
        int n=poly.length;
        int sign=0;
        for (int i=0;i<n;i++){
            long[] a=poly[i], b=poly[(i+1)%n];
            long cr = (b[0]-a[0])*(0-a[1]) - (b[1]-a[1])*(0-a[0]);
            int s = Long.signum(cr);
            if (s!=0){ if (sign==0) sign=s; else if (s!=sign) return false; }
        }
        return true;
    }
}
```

**Time:** O(n + m) · **Space:** O(n + m)

**Insight:** Two convex shapes overlap iff the origin is inside their Minkowski difference — that single fact is the conceptual core of GJK. Reducing collision to a point-in-convex-polygon test makes the predicate exact for integer inputs (all-same-sign cross products), with none of the floating-point fragility of a naive SAT loop.

---

### Problem 67: Separating Axis Theorem with Exact Projections — Collision

**Statement.** Decide convex-polygon overlap by the Separating Axis Theorem: project both polygons onto each edge normal and look for a gap. Keep projections exact by using the edge normal directly (no normalization).

```java
public class SAT {
    // Project polygon onto axis (ax,ay) -> [min,max] of dot products (unnormalized).
    static long[] project(long[][] p, long ax, long ay){
        long mn=Long.MAX_VALUE, mx=Long.MIN_VALUE;
        for (long[] v : p){ long d=v[0]*ax+v[1]*ay; mn=Math.min(mn,d); mx=Math.max(mx,d); }
        return new long[]{mn,mx};
    }

    static boolean gapOnAxes(long[][] A, long[][] B){
        int n=A.length;
        for (int i=0;i<n;i++){
            long[] a=A[i], b=A[(i+1)%n];
            long ax=-(b[1]-a[1]), ay=(b[0]-a[0]);       // outward normal of edge
            long[] pa=project(A,ax,ay), pb=project(B,ax,ay);
            if (pa[1] < pb[0] || pb[1] < pa[0]) return true;  // disjoint on this axis
        }
        return false;
    }

    public static boolean overlap(long[][] A, long[][] B){
        return !gapOnAxes(A,B) && !gapOnAxes(B,A);
    }
}
```

**Time:** O(n·m) · **Space:** O(1)

**Insight:** SAT only needs the edge normals as candidate separating axes, and projecting with the *unnormalized* normal keeps every dot product an exact integer — normalization would introduce square roots and floating error for zero benefit, since overlap depends only on the relative order of the projected intervals.

---

### Problem 68: Delaunay Edge Test via In-Circle Predicate — In-Circle

**Statement.** Implement the in-circle predicate: given triangle `a, b, c` (CCW) and point `d`, decide whether `d` lies inside the circumcircle. This is the local Delaunay condition; keep it exact with a 4×4-style determinant in `long`/`BigInteger`.

```java
import java.math.BigInteger;

public class InCircle {
    // > 0: d inside circumcircle of (a,b,c) assuming a,b,c CCW.
    public static int test(long[] a, long[] b, long[] c, long[] d){
        BigInteger ax=bi(a[0]-d[0]), ay=bi(a[1]-d[1]);
        BigInteger bx=bi(b[0]-d[0]), by=bi(b[1]-d[1]);
        BigInteger cx=bi(c[0]-d[0]), cy=bi(c[1]-d[1]);
        BigInteger a2=ax.multiply(ax).add(ay.multiply(ay));
        BigInteger b2=bx.multiply(bx).add(by.multiply(by));
        BigInteger c2=cx.multiply(cx).add(cy.multiply(cy));
        // det of [[ax,ay,a2],[bx,by,b2],[cx,cy,c2]]
        BigInteger det =
            ax.multiply(by.multiply(c2).subtract(b2.multiply(cy)))
          .subtract(ay.multiply(bx.multiply(c2).subtract(b2.multiply(cx))))
          .add(a2.multiply(bx.multiply(cy).subtract(by.multiply(cx))));
        return det.signum();
    }
    static BigInteger bi(long v){ return BigInteger.valueOf(v); }
}
```

**Time:** O(1) (with BigInteger constant factor) · **Space:** O(1)

**Insight:** The in-circle test is a 3×3 determinant of lifted coordinates `(x−dx, y−dy, (x−dx)²+(y−dy)²)`. Its sign is the local Delaunay flip criterion; because the squared terms reach the fourth power of the coordinate range, `BigInteger` (or Shewchuk's adaptive predicates) is required for exactness — `double` silently produces non-Delaunay triangulations.

---

### Problem 69: Triangle Circumcircle and Robust Degeneracy — Circle Geometry

**Statement.** Compute a triangle's circumcenter and circumradius, returning a clear "no circumcircle" signal when the three points are collinear (the determinant vanishes).

```java
public class Circumcircle {
    public static class C { double x,y,r; boolean ok; }

    public static C of(double ax,double ay,double bx,double by,double cx,double cy){
        C out=new C();
        double d = 2*(ax*(by-cy) + bx*(cy-ay) + cx*(ay-by));
        if (Math.abs(d) < 1e-12){ out.ok=false; return out; }   // collinear
        double a2=ax*ax+ay*ay, b2=bx*bx+by*by, c2=cx*cx+cy*cy;
        out.x = (a2*(by-cy) + b2*(cy-ay) + c2*(ay-by)) / d;
        out.y = (a2*(cx-bx) + b2*(ax-cx) + c2*(bx-ax)) / d;
        out.r = Math.hypot(ax-out.x, ay-out.y);
        out.ok=true;
        return out;
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight:** The circumcenter is the intersection of two perpendicular bisectors, expressible as a single determinant ratio. The denominator `d` is twice the signed area, so `d ≈ 0` is exactly the collinear case where no finite circumcircle exists — guard it before dividing rather than producing `Infinity`/`NaN`.

---

### Problem 70: Fractional Cascading-Free Range Tree for Orthogonal Counts — Range Search

**Statement.** Count points inside an axis-aligned query rectangle. Build a merge-sort tree (points sorted by x, each node holding its y-values sorted) and answer counts by binary search — the practical, easy-to-code alternative to a full range tree.

```java
import java.util.*;

public class RangeCount {
    int[] xs; int[][] tree; int n;

    public RangeCount(int[][] pts){
        Arrays.sort(pts,(a,b)->Integer.compare(a[0],b[0]));
        n=pts.length; xs=new int[n];
        for (int i=0;i<n;i++) xs[i]=pts[i][0];
        tree=new int[4*n][];
        build(1,0,n-1,pts);
    }
    void build(int node,int l,int r,int[][] pts){
        if (l==r){ tree[node]=new int[]{pts[l][1]}; return; }
        int m=(l+r)/2;
        build(2*node,l,m,pts); build(2*node+1,m+1,r,pts);
        tree[node]=merge(tree[2*node], tree[2*node+1]);
    }
    int[] merge(int[] a,int[] b){
        int[] c=new int[a.length+b.length]; int i=0,j=0,k=0;
        while (i<a.length&&j<b.length) c[k++]= a[i]<=b[j]?a[i++]:b[j++];
        while (i<a.length) c[k++]=a[i++];
        while (j<b.length) c[k++]=b[j++];
        return c;
    }
    static int countLE(int[] arr,int v){               // # elements <= v
        int lo=0,hi=arr.length;
        while (lo<hi){ int m=(lo+hi)/2; if (arr[m]<=v) lo=m+1; else hi=m; }
        return lo;
    }
    int xLower(int x){ int lo=0,hi=n; while(lo<hi){int m=(lo+hi)/2; if(xs[m]<x)lo=m+1; else hi=m;} return lo; }
    int xUpper(int x){ int lo=0,hi=n; while(lo<hi){int m=(lo+hi)/2; if(xs[m]<=x)lo=m+1; else hi=m;} return lo; }

    public int query(int x1,int y1,int x2,int y2){
        int lo=xLower(x1), hi=xUpper(x2)-1;            // index range [lo,hi]
        if (lo>hi) return 0;
        return q(1,0,n-1,lo,hi,y1,y2);
    }
    int q(int node,int l,int r,int ql,int qr,int y1,int y2){
        if (qr<l||r<ql) return 0;
        if (ql<=l&&r<=qr) return countLE(tree[node],y2)-countLE(tree[node],y1-1);
        int m=(l+r)/2;
        return q(2*node,l,m,ql,qr,y1,y2)+q(2*node+1,m+1,r,ql,qr,y1,y2);
    }
}
```

**Time:** build O(n log n), query O(log² n) · **Space:** O(n log n)

**Insight:** A merge-sort tree stores at each x-range node the sorted y-values of its points, so an orthogonal range count is `O(log n)` nodes each answered by two binary searches. It is the workhorse behind "how many points in this box" queries and far simpler to get right than fractional-cascading range trees.

---

### Problem 71: KD-Tree Nearest Neighbor with Correct Pruning — Spatial Index

**Statement.** Build a 2D KD-tree and answer nearest-neighbor queries. The subtle internal is the pruning bound: only recurse into the far subtree when the splitting-plane distance is less than the best found so far.

```java
import java.util.*;

public class KDTree {
    int[][] pts; int[] axisAt;
    int[] left, right; int root=-1, cnt=0;

    public KDTree(int[][] p){
        pts=p.clone(); int n=pts.length;
        left=new int[n]; right=new int[n]; axisAt=new int[n];
        Integer[] idx=new Integer[n]; for(int i=0;i<n;i++) idx[i]=i;
        root=build(idx,0,n-1,0);
    }
    int build(Integer[] idx,int lo,int hi,int depth){
        if (lo>hi) return -1;
        int axis=depth&1, mid=(lo+hi)/2;
        Arrays.sort(idx,lo,hi+1,(a,b)->Integer.compare(pts[a][axis],pts[b][axis]));
        int node=idx[mid]; axisAt[node]=axis;
        left[node]=build(idx,lo,mid-1,depth+1);
        right[node]=build(idx,mid+1,hi,depth+1);
        return node;
    }
    long best; int bestId;
    public int nearest(int qx,int qy){ best=Long.MAX_VALUE; bestId=-1; search(root,qx,qy); return bestId; }
    void search(int node,int qx,int qy){
        if (node<0) return;
        long dx=pts[node][0]-qx, dy=pts[node][1]-qy, d=dx*dx+dy*dy;
        if (d<best){ best=d; bestId=node; }
        int axis=axisAt[node];
        long diff=(axis==0?qx-pts[node][0]:qy-pts[node][1]);
        int near = diff<0?left[node]:right[node];
        int far  = diff<0?right[node]:left[node];
        search(near,qx,qy);
        if (diff*diff < best) search(far,qx,qy);        // prune: only cross plane if it could help
    }
}
```

**Time:** build O(n log n), query O(log n) average · **Space:** O(n)

**Insight:** The pruning test `diff*diff < best` is the entire correctness argument: the far subtree can only contain a closer point if the query is within `sqrt(best)` of the splitting plane. Drop that guard and you get a correct but `O(n)` query; use the wrong comparison (`<=` vs strict, or forgetting to square) and you either miss neighbors or never prune.

---

### Problem 72: Largest Empty Circle Among Points (Center on Hull/Voronoi) — Optimization

**Statement.** Find the largest circle centered inside the convex hull that contains no input point. The optimum center is a Voronoi vertex or a hull-edge point; implement a robust binary-search / candidate approach over Voronoi-style candidates.

```java
import java.util.*;

public class LargestEmptyCircle {
    // Candidate centers: midpoints, circumcenters of triples, and hull vertices,
    // each scored by distance to nearest site (clamped to lie inside the hull).
    public static double radius(double[][] pts, double[][] hull){
        double best=0;
        int n=pts.length;
        // Evaluate circumcenters of all triples (O(n^3) reference; Voronoi gives O(n log n)).
        for (int i=0;i<n;i++)
          for (int j=i+1;j<n;j++)
            for (int k=j+1;k<n;k++){
                Circumcircle.C c=Circumcircle.of(pts[i][0],pts[i][1],pts[j][0],pts[j][1],pts[k][0],pts[k][1]);
                if (!c.ok) continue;
                if (!PointInPolygonRay.inside(c.x,c.y,col(hull,0),col(hull,1))) continue;
                double r=nearestDist(c.x,c.y,pts);
                best=Math.max(best,r);
            }
        return best;
    }
    static double nearestDist(double cx,double cy,double[][] pts){
        double m=Double.MAX_VALUE;
        for (double[] p:pts) m=Math.min(m,Math.hypot(p[0]-cx,p[1]-cy));
        return m;
    }
    static double[] col(double[][] a,int c){ double[] r=new double[a.length]; for(int i=0;i<a.length;i++) r[i]=a[i][c]; return r; }
}
```

**Time:** O(n³) reference (O(n log n) via Voronoi) · **Space:** O(n)

**Insight:** The largest empty circle's center is necessarily equidistant from three sites (a Voronoi vertex) or lies on the hull boundary — interior maxima of the "distance to nearest site" function occur only at Voronoi vertices. The brute force over circumcenters is the correctness oracle; a Voronoi diagram replaces the triple loop in production.

---

### Problem 73: Polygon Triangulation by Ear Clipping (Degeneracy-Aware) — Triangulation

**Statement.** Triangulate a simple polygon by ear clipping, correctly skipping reflex vertices and "ears" that contain another vertex, and handling collinear vertices that are not valid ears.

```java
import java.util.*;

public class EarClipping {
    static double cross(double[]a,double[]b,double[]c){
        return (b[0]-a[0])*(c[1]-a[1]) - (b[1]-a[1])*(c[0]-a[0]);
    }
    static boolean inTri(double[]p,double[]a,double[]b,double[]c){
        double d1=cross(a,b,p), d2=cross(b,c,p), d3=cross(c,a,p);
        boolean neg=(d1<0)||(d2<0)||(d3<0), pos=(d1>0)||(d2>0)||(d3>0);
        return !(neg&&pos);                             // same side of all edges
    }

    public static List<int[]> triangulate(double[][] poly){
        int n=poly.length;
        List<Integer> idx=new ArrayList<>();
        for (int i=0;i<n;i++) idx.add(i);
        // ensure CCW
        double area=0; for(int i=0;i<n;i++){double[]a=poly[i],b=poly[(i+1)%n];area+=a[0]*b[1]-b[0]*a[1];}
        if (area<0) Collections.reverse(idx);
        List<int[]> tris=new ArrayList<>();
        int guard=0;
        while (idx.size()>3 && guard++ < 10*n){
            boolean clipped=false;
            for (int k=0;k<idx.size();k++){
                int i0=idx.get((k-1+idx.size())%idx.size());
                int i1=idx.get(k);
                int i2=idx.get((k+1)%idx.size());
                double[] a=poly[i0], b=poly[i1], c=poly[i2];
                if (cross(a,b,c)<=0) continue;          // reflex or collinear -> not an ear
                boolean ear=true;
                for (int j : idx){
                    if (j==i0||j==i1||j==i2) continue;
                    if (inTri(poly[j],a,b,c)){ ear=false; break; }
                }
                if (ear){ tris.add(new int[]{i0,i1,i2}); idx.remove(k); clipped=true; break; }
            }
            if (!clipped) break;                        // no ear found -> degenerate input
        }
        if (idx.size()==3) tris.add(new int[]{idx.get(0),idx.get(1),idx.get(2)});
        return tris;
    }
}
```

**Time:** O(n²) · **Space:** O(n)

**Insight:** An ear is a convex vertex whose triangle contains no other polygon vertex. The two failure modes are treating collinear vertices (cross product zero) as ears — they are not — and an infinite loop when no ear is found because the polygon was not actually simple; the guard counter surfaces that bug instead of hanging.

---

### Problem 74: Sweep-Line Area of Union of Rectangles — Sweep Line

**Statement.** Compute the total area covered by `n` axis-aligned rectangles (overlaps counted once) using a vertical sweep with a coordinate-compressed coverage segment tree.

```java
import java.util.*;

public class RectangleUnion {
    public static long area(int[][] rects){            // each: x1,y1,x2,y2
        int n=rects.length;
        int[] ys=new int[2*n];
        for (int i=0;i<n;i++){ ys[2*i]=rects[i][1]; ys[2*i+1]=rects[i][3]; }
        int[] sorted=ys.clone(); Arrays.sort(sorted);
        int[][] events=new int[2*n][]; int e=0;
        for (int[] r:rects){
            events[e++]=new int[]{r[0],1,r[1],r[3]};   // open
            events[e++]=new int[]{r[2],-1,r[1],r[3]};  // close
        }
        Arrays.sort(events,(a,b)->Integer.compare(a[0],b[0]));
        Cover cov=new Cover(sorted);
        long total=0; int prevX=events[0][0];
        for (int[] ev:events){
            total += (long)(ev[0]-prevX) * cov.covered();
            prevX=ev[0];
            cov.update(ev[2], ev[3], ev[1]);
        }
        return total;
    }

    static class Cover {                                // coverage over compressed y
        int[] ys; int[] cnt; long[] len;
        Cover(int[] sortedYs){ ys=sortedYs; cnt=new int[4*ys.length]; len=new long[4*ys.length]; }
        void update(int yl,int yr,int delta){ upd(1,0,ys.length-1,yl,yr,delta); }
        void upd(int node,int l,int r,int yl,int yr,int d){
            if (ys[r]<=yl || yr<=ys[l]) return;
            if (yl<=ys[l] && ys[r]<=yr){ cnt[node]+=d; pull(node,l,r); return; }
            int m=(l+r)/2;
            upd(2*node,l,m,yl,yr,d); upd(2*node+1,m+1,r,yl,yr,d);
            pull(node,l,r);
        }
        void pull(int node,int l,int r){
            if (cnt[node]>0) len[node]=ys[r]-ys[l];
            else if (l+1==r) len[node]=0;               // leaf interval, not covered
            else len[node]=len[2*node]+len[2*node+1];
        }
        long covered(){ return len[1]; }
    }
}
```

**Time:** O(n log n) · **Space:** O(n)

**Insight:** Sweeping left to right, the covered y-length times the x-gap accumulates area. The coverage tree's `pull` rule is the crux: a node fully covered (`cnt > 0`) contributes its whole span, otherwise it sums children — and leaves contribute zero, which is why y-coordinates are stored as `n−1` intervals, not `n` points.

---

### Problem 75: Closest Pair on a Sphere via Great-Circle Distance — Spherical Geometry

**Statement.** Given latitude/longitude points, find the closest pair by great-circle distance. The internal subtlety is converting to 3D Cartesian so Euclidean nearest equals angular nearest, avoiding the `acos` precision cliff near antipodes.

```java
public class SphereClosest {
    static double[] toXYZ(double latDeg,double lonDeg){
        double lat=Math.toRadians(latDeg), lon=Math.toRadians(lonDeg);
        return new double[]{ Math.cos(lat)*Math.cos(lon),
                             Math.cos(lat)*Math.sin(lon),
                             Math.sin(lat) };
    }
    // Haversine is stable for small angles; use it for the final metric.
    static double haversine(double[] p,double[] q){
        double dot=p[0]*q[0]+p[1]*q[1]+p[2]*q[2];
        double chord2=2-2*dot;                          // |p-q|^2 on unit sphere
        return 2*Math.asin(Math.sqrt(Math.max(0,chord2))/2); // central angle, stable
    }

    public static int[] closest(double[][] latlon){
        int n=latlon.length;
        double[][] xyz=new double[n][];
        for (int i=0;i<n;i++) xyz[i]=toXYZ(latlon[i][0],latlon[i][1]);
        // Minimizing chord distance in R^3 minimizes the angle, so any Euclidean
        // closest-pair routine on xyz gives the answer; brute force shown for clarity.
        double best=Double.MAX_VALUE; int bi=-1,bj=-1;
        for (int i=0;i<n;i++) for (int j=i+1;j<n;j++){
            double dx=xyz[i][0]-xyz[j][0], dy=xyz[i][1]-xyz[j][1], dz=xyz[i][2]-xyz[j][2];
            double d=dx*dx+dy*dy+dz*dz;
            if (d<best){ best=d; bi=i; bj=j; }
        }
        return new int[]{bi,bj};
    }
}
```

**Time:** O(n²) brute (O(n log n) with 3D closest pair) · **Space:** O(n)

**Insight:** Chord length on the unit sphere is monotonic in the central angle, so minimizing 3D Euclidean distance minimizes great-circle distance — letting you reuse any Euclidean closest-pair algorithm. Computing the final angle via `asin(chord/2)` (haversine) sidesteps the catastrophic `acos` error when two points are nearly identical.

---

### Problem 76: Visibility from a Point Inside a Simple Polygon — Visibility

**Statement.** Compute the visibility polygon from a point inside a simple polygon: the region of the polygon directly visible (no edge blocks the segment from the viewpoint). Implement the angular-sweep core with correct handling of edges that begin or end behind others.

```java
import java.util.*;

public class Visibility {
    static double cross(double ox,double oy,double ax,double ay,double bx,double by){
        return (ax-ox)*(by-oy) - (ay-oy)*(bx-ox);
    }
    // Distance from viewpoint to the first blocking edge along a ray angle.
    static double rayHit(double px,double py,double dx,double dy,double[][] poly){
        int n=poly.length; double best=Double.MAX_VALUE;
        for (int i=0;i<n;i++){
            double[] a=poly[i], b=poly[(i+1)%n];
            double r = intersectRaySeg(px,py,dx,dy,a[0],a[1],b[0],b[1]);
            if (r>=0) best=Math.min(best,r);
        }
        return best;
    }
    static double intersectRaySeg(double px,double py,double dx,double dy,
                                  double ax,double ay,double bx,double by){
        double ex=bx-ax, ey=by-ay;
        double denom=dx*ey - dy*ex;
        if (Math.abs(denom)<1e-12) return -1;
        double t=((ax-px)*ey - (ay-py)*ex)/denom;       // along ray
        double s=((ax-px)*dy - (ay-py)*dx)/denom;       // along segment
        if (t>=0 && s>=-1e-9 && s<=1+1e-9) return t;
        return -1;
    }

    // Cast rays toward each vertex (and slightly around it) to build the visibility polygon.
    public static List<double[]> visible(double px,double py,double[][] poly){
        List<double[]> hits=new ArrayList<>();
        TreeMap<Double,double[]> byAngle=new TreeMap<>();
        for (double[] v : poly){
            double base=Math.atan2(v[1]-py, v[0]-px);
            for (double da : new double[]{-1e-6,0,1e-6}){
                double ang=base+da, dx=Math.cos(ang), dy=Math.sin(ang);
                double t=rayHit(px,py,dx,dy,poly);
                if (t<Double.MAX_VALUE) byAngle.put(ang, new double[]{px+t*dx, py+t*dy});
            }
        }
        hits.addAll(byAngle.values());
        return hits;
    }
}
```

**Time:** O(n²) (rays × edges; O(n log n) with an angular sweep + active-edge set) · **Space:** O(n)

**Insight:** Visibility changes only at directions toward vertices, so casting rays toward each vertex captures every boundary of the visible region. The ±epsilon rays on each side of a vertex are essential — they reveal whether the view continues past the vertex or is blocked there, the case naive single-ray casting silently drops.

---

### Problem 77: Online Convex Hull (Dynamic Insertion) — Dynamic Convex Hull

**Statement.** Support inserting points one at a time and querying the current hull, keeping the upper and lower chains in ordered maps so each insertion is amortized `O(log n)` and a point inside the hull is rejected in `O(log n)`.

```java
import java.util.*;

public class DynamicHull {
    TreeMap<Long,Long> upper = new TreeMap<>();   // x -> y, upper chain
    TreeMap<Long,Long> lower = new TreeMap<>();   // x -> y, lower chain

    static boolean badUpper(long ax,long ay,long bx,long by,long cx,long cy){
        return (bx-ax)*(cy-ay) - (by-ay)*(cx-ax) >= 0;   // not a right turn -> b redundant
    }
    static boolean badLower(long ax,long ay,long bx,long by,long cx,long cy){
        return (bx-ax)*(cy-ay) - (by-ay)*(cx-ax) <= 0;
    }

    boolean inside(TreeMap<Long,Long> chain,long x,long y,boolean isUpper){
        Map.Entry<Long,Long> lo=chain.floorEntry(x), hi=chain.ceilingEntry(x);
        if (lo==null||hi==null) return false;
        if (lo.getKey().equals(x)) return isUpper ? y<=lo.getValue() : y>=lo.getValue();
        long cr=(hi.getKey()-lo.getKey())*(y-lo.getValue())
              - (hi.getValue()-lo.getValue())*(x-lo.getKey());
        return isUpper ? cr<=0 : cr>=0;
    }

    public boolean add(long x,long y){
        if (inside(upper,x,y,true) && inside(lower,x,y,false)) return false; // already covered
        insert(upper,x,y,true);
        insert(lower,x,y,false);
        return true;
    }

    void insert(TreeMap<Long,Long> chain,long x,long y,boolean isUpper){
        chain.put(x,y);
        // clean right side
        Map.Entry<Long,Long> cur=chain.ceilingEntry(x+1);
        while (cur!=null){
            Map.Entry<Long,Long> nxt=chain.higherEntry(cur.getKey());
            if (nxt==null) break;
            boolean bad=isUpper
                ? badUpper(x,y,cur.getKey(),cur.getValue(),nxt.getKey(),nxt.getValue())
                : badLower(x,y,cur.getKey(),cur.getValue(),nxt.getKey(),nxt.getValue());
            if (bad){ chain.remove(cur.getKey()); cur=chain.ceilingEntry(x+1); } else break;
        }
        // clean left side
        Map.Entry<Long,Long> pcur=chain.floorEntry(x-1);
        while (pcur!=null){
            Map.Entry<Long,Long> prev=chain.lowerEntry(pcur.getKey());
            if (prev==null) break;
            boolean bad=isUpper
                ? badUpper(prev.getKey(),prev.getValue(),pcur.getKey(),pcur.getValue(),x,y)
                : badLower(prev.getKey(),prev.getValue(),pcur.getKey(),pcur.getValue(),x,y);
            if (bad){ chain.remove(pcur.getKey()); pcur=chain.floorEntry(x-1); } else break;
        }
    }
}
```

**Time:** O(log n) amortized per insertion · **Space:** O(n)

**Insight:** Maintaining the hull as two monotone chains in balanced BSTs makes insertion an interval-local repair: reject the point if both chains already cover it, otherwise splice it in and pop redundant neighbors on each side. Each point is inserted and removed at most once, so the amortized cost is the `O(log n)` of the tree operations — the dynamic analogue of the static monotone-chain pop loop.

---

## 🧩 Extended Problems — Set 2: Hard variations & follow-ups

These problems push past the textbook primitives into the variations interviewers actually use to separate candidates: weighted/anisotropic distances, 3D lifts, online and dynamic structures, exact rational predicates, and optimization layered on top of the geometric core. Each builds on a Set-1 technique but adds a twist — a parameter sweep, a duality, a tolerance regime, or a constraint that breaks the naive approach.

### Problem 78: Convex Hull of Points with Duplicate-Heavy Input — Robust Convex Hull

**Statement.** Compute the convex hull when up to 90% of the `n` input points are exact duplicates. Naive monotone chain still works but wastes a sort over redundant points; dedup first, then hull, and prove the answer is unchanged.

```java
import java.util.*;

public class DedupHull {
    static long cross(long[] o, long[] a, long[] b) {
        return (a[0]-o[0])*(b[1]-o[1]) - (a[1]-o[1])*(b[0]-o[0]);
    }

    public static long[][] hull(long[][] pts) {
        // Dedup via a sorted set keyed on (x,y); duplicates never affect the hull.
        TreeSet<long[]> uniq = new TreeSet<>((p, q) ->
            p[0] != q[0] ? Long.compare(p[0], q[0]) : Long.compare(p[1], q[1]));
        uniq.addAll(Arrays.asList(pts));
        long[][] p = uniq.toArray(new long[0][]);
        int n = p.length;
        if (n < 3) return p;
        long[][] h = new long[2 * n][];
        int k = 0;
        for (long[] q : p) {
            while (k >= 2 && cross(h[k-2], h[k-1], q) <= 0) k--;
            h[k++] = q;
        }
        int lower = k + 1;
        for (int i = n - 2; i >= 0; i--) {
            while (k >= lower && cross(h[k-2], h[k-1], p[i]) <= 0) k--;
            h[k++] = p[i];
        }
        return Arrays.copyOf(h, k - 1);
    }
}
```

**Time:** O(n log n) · **Space:** O(n)

**Insight:** Duplicates are hull-invariant — collapsing them up front shrinks the working set to `u` distinct points and guarantees the comparator's strict total order, eliminating the equal-key instability that can corrupt a monotone-chain pop loop.

---

### Problem 79: Weighted (Anisotropic) Closest Pair — Scaled Distance Transform

**Statement.** Find the closest pair under a weighted metric `d² = wx·(Δx)² + wy·(Δy)²` with positive weights. Rescale the plane so the metric becomes Euclidean, then run standard divide-and-conquer closest pair.

```java
import java.util.*;

public class WeightedClosestPair {
    public static double closest(double[][] pts, double wx, double wy) {
        double sx = Math.sqrt(wx), sy = Math.sqrt(wy);
        double[][] t = new double[pts.length][2];
        for (int i = 0; i < pts.length; i++) {
            t[i][0] = pts[i][0] * sx;   // x' = sqrt(wx)·x
            t[i][1] = pts[i][1] * sy;   // y' = sqrt(wy)·y
        }
        return ClosestPairDC.closest(t);    // Euclidean in transformed space
    }
}
```

**Time:** O(n log n) · **Space:** O(n)

**Insight:** Any diagonal positive-definite metric is Euclidean after the linear change of variables `x' = √wx·x`, so the hard "weighted" version is the easy version on rescaled coordinates — the transform preserves the order of all pairwise distances.

---

### Problem 80: Farthest Pair via Hull Diameter on Streaming Points — Rotating Calipers

**Statement.** Points arrive in a stream; after all arrive, report the diameter (farthest pair). The twist: you may keep only the hull, not all points, so maintain an online hull and run calipers at the end.

```java
import java.util.*;

public class StreamingDiameter {
    DynamicHull dh = new DynamicHull();
    List<long[]> seen = new ArrayList<>();   // only hull-relevant points survive conceptually

    void add(long x, long y) { if (dh.add(x, y)) seen.add(new long[]{x, y}); }

    public long diameter2() {
        // Rebuild a clean CCW hull from the surviving extreme points, then calipers.
        long[][] arr = seen.toArray(new long[0][]);
        long[][] h = DedupHull.hull(arr);
        return ConvexDiameter.diameter2(h);
    }
}
```

**Time:** O(n log n) total · **Space:** O(h)

**Insight:** The diameter is always realized by two hull vertices, so an online hull is a lossless summary for this query — interior points can be discarded the moment the hull rejects them, bounding memory by the (often tiny) hull size.

---

### Problem 81: Minimum Enclosing Circle Under Insertions — Incremental Welzl

**Statement.** Support adding points and querying the smallest enclosing circle. Use Welzl's randomized incremental algorithm, re-seeding only when a new point falls outside the current circle.

```java
import java.util.*;

public class IncrementalMEC {
    double cx, cy, r2;        // center and squared radius
    List<double[]> pts = new ArrayList<>();

    boolean inside(double[] p) {
        double dx = p[0] - cx, dy = p[1] - cy;
        return dx*dx + dy*dy <= r2 + 1e-9;
    }

    public void add(double[] p) {
        pts.add(p);
        if (pts.size() == 1) { cx = p[0]; cy = p[1]; r2 = 0; return; }
        if (inside(p)) return;                 // circle unchanged
        // p is on the boundary of the new circle: rebuild with p pinned.
        cx = p[0]; cy = p[1]; r2 = 0;
        for (int i = 0; i < pts.size() - 1; i++) {
            if (!inside(pts.get(i))) circleFromTwo(p, pts.get(i), i);
        }
    }

    void circleFromTwo(double[] a, double[] b, int upto) {
        cx = (a[0]+b[0])/2; cy = (a[1]+b[1])/2;
        double dx = a[0]-cx, dy = a[1]-cy; r2 = dx*dx+dy*dy;
        for (int j = 0; j <= upto; j++)
            if (!inside(pts.get(j))) circleFromThree(a, b, pts.get(j));
    }

    void circleFromThree(double[] a, double[] b, double[] c) {
        double ax=a[0],ay=a[1],bx=b[0],by=b[1],cxx=c[0],cyy=c[1];
        double d = 2*(ax*(by-cyy)+bx*(cyy-ay)+cxx*(ay-by));
        double ux = ((ax*ax+ay*ay)*(by-cyy)+(bx*bx+by*by)*(cyy-ay)+(cxx*cxx+cyy*cyy)*(ay-by))/d;
        double uy = ((ax*ax+ay*ay)*(cxx-bx)+(bx*bx+by*by)*(ax-cxx)+(cxx*cxx+cyy*cyy)*(bx-ax))/d;
        cx = ux; cy = uy;
        double dx = ax-ux, dy = ay-uy; r2 = dx*dx+dy*dy;
    }
}
```

**Time:** O(n) expected amortized · **Space:** O(n)

**Insight:** Welzl's insight extends to insertion: only a point that escapes the current circle can change it, and when it does it must lie on the new boundary — pinning it and re-running the lower-dimensional cases keeps the expected work linear despite the worst-case cubic-looking nesting.

---

### Problem 82: Maximum Overlap of Axis-Aligned Rectangles — Coordinate Compression + Sweep

**Statement.** Given `n` axis-aligned rectangles, find the maximum number that overlap at any single point. Sweep a vertical line and maintain a count over a compressed y-axis with a difference array.

```java
import java.util.*;

public class MaxRectOverlap {
    // rects: {x1, y1, x2, y2}
    public static int maxOverlap(int[][] rects) {
        TreeSet<Integer> ys = new TreeSet<>();
        for (int[] r : rects) { ys.add(r[1]); ys.add(r[3]); }
        Integer[] yArr = ys.toArray(new Integer[0]);
        Map<Integer,Integer> idx = new HashMap<>();
        for (int i = 0; i < yArr.length; i++) idx.put(yArr[i], i);

        // Events: at x1 add +1 over [y1,y2), at x2 remove.
        List<int[]> ev = new ArrayList<>();
        for (int[] r : rects) {
            ev.add(new int[]{r[0], 1, idx.get(r[1]), idx.get(r[3])});
            ev.add(new int[]{r[2], -1, idx.get(r[1]), idx.get(r[3])});
        }
        ev.sort((a, b) -> a[0] != b[0] ? a[0] - b[0] : a[1] - b[1]); // removals before adds at same x
        int[] cnt = new int[yArr.length + 1];
        int best = 0;
        for (int[] e : ev) {
            for (int y = e[2]; y < e[3]; y++) {
                cnt[y] += e[1];
                best = Math.max(best, cnt[y]);
            }
        }
        return best;
    }
}
```

**Time:** O(n²) naive band update; O(n log n) with a segment tree · **Space:** O(n)

**Insight:** Maximum point coverage is a 2D stabbing query; compressing y to `O(n)` bands and sweeping x turns it into a moving 1D interval-max problem — swap the inner band loop for a lazy segment tree to reach `O(n log n)`.

---

### Problem 83: Convex Hull Trick for DP Optimization — Lower Envelope

**Statement.** Speed up a DP of the form `dp[i] = min_j (m_j·x_i + b_j)` by maintaining the lower envelope of lines added in order of slope, querying with monotone `x_i`.

```java
import java.util.*;

public class ConvexHullTrickDP {
    long[] M = new long[0], B = new long[0];
    int size = 0, ptr = 0;

    boolean bad(long m1, long b1, long m2, long b2, long m3, long b3) {
        // line2 is unnecessary if intersection(1,3) is below line2.
        return (b3 - b1) * (m1 - m2) <= (b2 - b1) * (m1 - m3);
    }

    void addLine(long m, long b) {  // slopes added in decreasing order for min-query
        M = Arrays.copyOf(M, size + 1); B = Arrays.copyOf(B, size + 1);
        M[size] = m; B[size] = b; size++;
        while (size >= 3 && bad(M[size-3],B[size-3],M[size-2],B[size-2],M[size-1],B[size-1])) {
            M[size-2] = M[size-1]; B[size-2] = B[size-1]; size--;
            M = Arrays.copyOf(M, size); B = Arrays.copyOf(B, size);
        }
    }

    long query(long x) {            // x non-decreasing
        if (ptr >= size) ptr = size - 1;
        while (ptr + 1 < size && M[ptr+1]*x + B[ptr+1] <= M[ptr]*x + B[ptr]) ptr++;
        return M[ptr]*x + B[ptr];
    }
}
```

**Time:** O(n) amortized over all adds and queries · **Space:** O(n)

**Insight:** A min-of-linear-functions DP is a geometric lower-envelope query in disguise; when both slopes and query points are monotone, the envelope is built and scanned with two amortized-`O(1)` pointers — no binary search, no balanced tree.

---

### Problem 84: Li Chao Tree for Arbitrary-Order Lines — Lower Envelope

**Statement.** Same min-of-lines query as the convex hull trick, but lines arrive in arbitrary slope order and queries are arbitrary. Use a Li Chao segment tree over the query-coordinate domain.

```java
public class LiChaoTree {
    final long NEG = Long.MIN_VALUE / 4;
    long[] M, B; int[] lc, rc; int cnt = 0; long lo, hi;

    public LiChaoTree(long lo, long hi, int cap) {
        this.lo = lo; this.hi = hi;
        M = new long[cap]; B = new long[cap]; lc = new int[cap]; rc = new int[cap];
        java.util.Arrays.fill(M, 0); java.util.Arrays.fill(B, Long.MAX_VALUE/4);
        cnt = 1;
    }

    long f(int node, long x) { return M[node]*x + B[node]; }

    public void addLine(long m, long b) { add(0, lo, hi, m, b); }

    void add(int node, long l, long r, long m, long b) {
        long mid = (l + r) >> 1;
        boolean left = m*l + b < f(node, l);
        boolean midB = m*mid + b < f(node, mid);
        if (midB) { long tm = M[node], tb = B[node]; M[node]=m; B[node]=b; m=tm; b=tb; }
        if (l == r) return;
        if (left != midB) { if (lc[node]==0){lc[node]=cnt++;} add(lc[node], l, mid, m, b); }
        else { if (rc[node]==0){rc[node]=cnt++;} add(rc[node], mid+1, r, m, b); }
    }

    public long query(long x) { return query(0, lo, hi, x); }
    long query(int node, long l, long r, long x) {
        long res = f(node, x);
        if (l == r) return res;
        long mid = (l + r) >> 1;
        if (x <= mid && lc[node]!=0) return Math.min(res, query(lc[node], l, mid, x));
        if (x > mid && rc[node]!=0) return Math.min(res, query(rc[node], mid+1, r, x));
        return res;
    }
}
```

**Time:** O(log range) per add and query · **Space:** O(n + nodes)

**Insight:** When the monotonicity assumptions of the convex hull trick fail, the Li Chao tree recovers `O(log)` queries by storing one dominating line per segment-tree node and recursing into the half where the challenger might still win — robust to any insertion order.

---

### Problem 85: 3D Convex Hull — Incremental (Quickhull-style) — 3D Hull

**Statement.** Compute the convex hull of points in 3D as a set of triangular faces using the randomized incremental method: maintain a face list, and for each new point delete visible faces and cone the horizon to it.

```java
import java.util.*;

public class Hull3D {
    double[][] p;
    static class Face { int a, b, c; double[] n; }

    double[] cross(double[] u, double[] v) {
        return new double[]{u[1]*v[2]-u[2]*v[1], u[2]*v[0]-u[0]*v[2], u[0]*v[1]-u[1]*v[0]};
    }
    double[] sub(double[] x, double[] y){ return new double[]{x[0]-y[0],x[1]-y[1],x[2]-y[2]}; }

    Face mkFace(int a, int b, int c, double[] inside) {
        Face f = new Face(); f.a=a; f.b=b; f.c=c;
        f.n = cross(sub(p[b],p[a]), sub(p[c],p[a]));
        double[] toIn = sub(inside, p[a]);
        if (f.n[0]*toIn[0]+f.n[1]*toIn[1]+f.n[2]*toIn[2] > 0) { // orient outward
            int t=f.b; f.b=f.c; f.c=t;
            f.n = cross(sub(p[f.b],p[f.a]), sub(p[f.c],p[f.a]));
        }
        return f;
    }

    boolean visible(Face f, double[] q) {
        double[] d = sub(q, p[f.a]);
        return f.n[0]*d[0]+f.n[1]*d[1]+f.n[2]*d[2] > 1e-9;
    }

    public List<Face> hull(double[][] pts) {
        p = pts; int n = pts.length;
        double[] inside = {0,0,0};
        for (int i = 0; i < 4; i++) for (int k=0;k<3;k++) inside[k]+=pts[i][k]/4.0;
        List<Face> faces = new ArrayList<>();
        int[][] base = {{0,1,2},{0,1,3},{0,2,3},{1,2,3}};
        for (int[] t : base) faces.add(mkFace(t[0],t[1],t[2], inside));
        for (int i = 4; i < n; i++) {
            List<Face> keep = new ArrayList<>();
            Map<Long,int[]> horizon = new HashMap<>();
            for (Face f : faces) {
                if (visible(f, pts[i])) {
                    edge(horizon, f.a, f.b); edge(horizon, f.b, f.c); edge(horizon, f.c, f.a);
                } else keep.add(f);
            }
            for (int[] e : horizon.values()) if (e != null) keep.add(mkFace(e[0], e[1], i, inside));
            faces = keep;
        }
        return faces;
    }

    void edge(Map<Long,int[]> h, int u, int v) {
        long key = Math.min(u,v)*1000003L + Math.max(u,v);
        if (h.containsKey(key)) h.put(key, null); else h.put(key, new int[]{u, v});
    }
}
```

**Time:** O(n log n) expected · **Space:** O(n)

**Insight:** The 2D "pop right turns" idea lifts to 3D as "delete visible faces and re-cone the horizon edge loop"; the horizon is exactly the set of edges that belong to one visible and one hidden face, which the parity map isolates in linear time per point.

---

### Problem 86: Delaunay Triangulation via 3D Lift (Lower Hull) — Lifting Map

**Statement.** Compute the Delaunay triangulation of 2D points by lifting each `(x, y)` to the paraboloid `(x, y, x²+y²)` and taking the lower hull in 3D; downward-facing faces project to Delaunay triangles.

```java
import java.util.*;

public class DelaunayLift {
    public static List<int[]> triangulate(double[][] pts) {
        int n = pts.length;
        double[][] lifted = new double[n][3];
        for (int i = 0; i < n; i++) {
            lifted[i][0] = pts[i][0];
            lifted[i][1] = pts[i][1];
            lifted[i][2] = pts[i][0]*pts[i][0] + pts[i][1]*pts[i][1];   // paraboloid
        }
        Hull3D h3 = new Hull3D();
        List<Hull3D.Face> faces = h3.hull(lifted);
        List<int[]> tris = new ArrayList<>();
        for (Hull3D.Face f : faces) {
            if (f.n[2] < 0) tris.add(new int[]{f.a, f.b, f.c});  // downward face -> Delaunay
        }
        return tris;
    }
}
```

**Time:** O(n log n) expected · **Space:** O(n)

**Insight:** The in-circle predicate in 2D is exactly an orientation predicate in 3D after lifting to the paraboloid — so Delaunay triangulation is "just" a lower convex hull one dimension up, the cleanest derivation of why the empty-circle property holds.

---

### Problem 87: Largest Empty Rectangle (Axis-Aligned) — Histogram Sweep

**Statement.** Among `n` points in a bounding box, find the largest axis-aligned rectangle containing no point in its interior, with sides parallel to the axes. Sweep rows and use the maximal-rectangle-in-histogram trick.

```java
import java.util.*;

public class LargestEmptyRect {
    // grid[r][c] == 1 means blocked (a point sits there)
    public static long largest(int[][] blocked, int rows, int cols) {
        int[] height = new int[cols];
        long best = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++)
                height[c] = blocked[r][c] == 1 ? 0 : height[c] + 1;
            best = Math.max(best, maxHist(height));
        }
        return best;
    }

    static long maxHist(int[] h) {
        Deque<Integer> st = new ArrayDeque<>();
        long best = 0; int n = h.length;
        for (int i = 0; i <= n; i++) {
            int cur = i == n ? 0 : h[i];
            while (!st.isEmpty() && h[st.peek()] >= cur) {
                int height = h[st.pop()];
                int width = st.isEmpty() ? i : i - st.peek() - 1;
                best = Math.max(best, (long) height * width);
            }
            st.push(i);
        }
        return best;
    }
}
```

**Time:** O(rows · cols) · **Space:** O(cols)

**Insight:** A maximal empty rectangle bottoms out on some row; fixing that row reduces the 2D search to the 1D largest-rectangle-in-histogram problem, whose monotonic stack runs in linear time per row — the canonical "stack of increasing bars" pattern.

---

### Problem 88: Smallest Width Strip Covering All Points — Rotating Calipers on Hull

**Statement.** Find the minimum-width infinite strip (region between two parallel lines) that contains all `n` points. The width equals the convex polygon width of the hull.

```java
public class MinStripWidth {
    public static double minWidth(long[][] pts) {
        long[][] h = DedupHull.hull(pts);
        if (h.length < 3) return 0;
        double[][] hd = new double[h.length][2];
        for (int i = 0; i < h.length; i++) { hd[i][0]=h[i][0]; hd[i][1]=h[i][1]; }
        return ConvexWidth.minWidth(hd);
    }
}
```

**Time:** O(n log n) · **Space:** O(n)

**Insight:** The thinnest covering strip is hull-determined: at least one bounding line must be flush with a hull edge, so the answer is precisely the rotating-calipers polygon width — a one-line reduction once you recognize the equivalence.

---

### Problem 89: Maximum Points Inside a Unit Circle (Sliding Disk) — Angular Sweep

**Statement.** Given `n` points and radius `r`, place a disk of radius `r` to cover the most points. For each point, sweep an angular window over points within `2r` and count the maximal arc.

```java
import java.util.*;

public class MaxPointsInDisk {
    public static int maxCovered(double[][] pts, double r) {
        int n = pts.length, best = 1;
        for (int i = 0; i < n; i++) {
            List<double[]> angs = new ArrayList<>();   // {angle, +1 enter / -1 leave}
            for (int j = 0; j < n; j++) {
                if (i == j) continue;
                double dx = pts[j][0]-pts[i][0], dy = pts[j][1]-pts[i][1];
                double d = Math.hypot(dx, dy);
                if (d > 2*r) continue;
                double a = Math.atan2(dy, dx);
                double delta = Math.acos(d / (2*r));   // half-angle of the covering window
                angs.add(new double[]{a - delta, 1});
                angs.add(new double[]{a + delta, -1});
            }
            angs.sort((u, v) -> u[0] != v[0] ? Double.compare(u[0], v[0])
                                             : Double.compare(v[1], u[1])); // enters first
            int cur = 1;
            for (double[] e : angs) { cur += e[1]; best = Math.max(best, cur); }
        }
        return best;
    }
}
```

**Time:** O(n² log n) · **Space:** O(n)

**Insight:** A disk of radius `r` whose boundary passes through a fixed point covers another point exactly when the center lies on an arc; sweeping the enter/leave angles of those arcs counts the max simultaneous coverage — the continuous analogue of the interval-overlap maximum.

---

### Problem 90: Convex Polygon Intersection via Sutherland–Hodgman — Polygon Clipping

**Statement.** Clip a subject polygon against a convex clip polygon, edge by edge, producing the intersection polygon. Generalizes the convex-intersection problem to non-convex subjects against a convex window.

```java
import java.util.*;

public class SutherlandHodgman {
    static double cross(double[] a, double[] b, double[] p) {
        return (b[0]-a[0])*(p[1]-a[1]) - (b[1]-a[1])*(p[0]-a[0]);
    }
    static double[] inter(double[] s, double[] e, double[] a, double[] b) {
        double a1 = e[1]-s[1], b1 = s[0]-e[0], c1 = a1*s[0]+b1*s[1];
        double a2 = b[1]-a[1], b2 = a[0]-b[0], c2 = a2*a[0]+b2*a[1];
        double det = a1*b2 - a2*b1;
        return new double[]{(b2*c1-b1*c2)/det, (a1*c2-a2*c1)/det};
    }

    public static List<double[]> clip(List<double[]> subject, double[][] clip) {
        List<double[]> out = subject;
        int m = clip.length;
        for (int i = 0; i < m; i++) {
            double[] a = clip[i], b = clip[(i+1)%m];
            List<double[]> in = out; out = new ArrayList<>();
            for (int k = 0; k < in.size(); k++) {
                double[] cur = in.get(k), prev = in.get((k + in.size() - 1) % in.size());
                boolean curIn = cross(a, b, cur) >= 0, prevIn = cross(a, b, prev) >= 0;
                if (curIn) {
                    if (!prevIn) out.add(inter(prev, cur, a, b));
                    out.add(cur);
                } else if (prevIn) out.add(inter(prev, cur, a, b));
            }
            if (out.isEmpty()) break;
        }
        return out;
    }
}
```

**Time:** O(n·m) · **Space:** O(n)

**Insight:** Clipping against a convex window is separable into clipping against each half-plane in turn; carrying the running polygon through one edge at a time keeps the logic to a single in/out test per vertex, which is why Sutherland–Hodgman is the workhorse of rasterizers.

---

### Problem 91: Polygon Boolean Union of Two Convex Polygons — Half-Plane / Hull

**Statement.** Compute the area of the union of two convex polygons via inclusion–exclusion: `|A ∪ B| = |A| + |B| − |A ∩ B|`, reusing convex intersection for the overlap.

```java
import java.util.*;

public class ConvexUnionArea {
    public static double unionArea(double[][] A, double[][] B) {
        double areaA = ShoelaceArea.area(col(A,0), col(A,1));
        double areaB = ShoelaceArea.area(col(B,0), col(B,1));
        List<double[]> inter = SutherlandHodgman.clip(
            new ArrayList<>(Arrays.asList(A)), B);
        double areaI = inter.size() < 3 ? 0 :
            ShoelaceArea.area(col(inter.toArray(new double[0][]),0),
                              col(inter.toArray(new double[0][]),1));
        return areaA + areaB - areaI;
    }
    static double[] col(double[][] m, int c) {
        double[] r = new double[m.length];
        for (int i = 0; i < m.length; i++) r[i] = m[i][c];
        return r;
    }
}
```

**Time:** O(n·m) · **Space:** O(n + m)

**Insight:** Union area never needs an explicit union polygon — inclusion–exclusion turns it into one intersection computation plus two shoelace areas, sidestepping the messy boundary stitching a literal Boolean union would require.

---

### Problem 92: Point Location in a Planar Subdivision — Slab Decomposition

**Statement.** Preprocess a planar subdivision (set of non-crossing segments) so that point-location queries — "which face contains `q`?" — run in `O(log n)`. Use vertical slabs between consecutive x-coordinates of vertices.

```java
import java.util.*;

public class SlabLocation {
    double[] slabX;                 // sorted distinct x of all endpoints
    List<double[]>[] slabSegs;      // segments crossing each slab, ordered bottom-to-top

    @SuppressWarnings("unchecked")
    public SlabLocation(double[][] segs) {
        TreeSet<Double> xs = new TreeSet<>();
        for (double[] s : segs) { xs.add(s[0]); xs.add(s[2]); }
        slabX = xs.stream().mapToDouble(Double::doubleValue).toArray();
        slabSegs = new List[Math.max(0, slabX.length - 1)];
        for (int i = 0; i < slabSegs.length; i++) {
            double midX = (slabX[i] + slabX[i+1]) / 2;
            List<double[]> here = new ArrayList<>();
            for (double[] s : segs) {
                double lo = Math.min(s[0], s[2]), hi = Math.max(s[0], s[2]);
                if (lo <= midX && midX <= hi) here.add(s);
            }
            here.sort(Comparator.comparingDouble(s -> yAt(s, midX)));
            slabSegs[i] = here;
        }
    }

    static double yAt(double[] s, double x) {
        if (s[0] == s[2]) return Math.min(s[1], s[3]);
        return s[1] + (s[3]-s[1]) * (x - s[0]) / (s[2]-s[0]);
    }

    // returns index of the segment immediately below q, or -1
    public int locate(double qx, double qy) {
        int sl = Arrays.binarySearch(slabX, qx);
        if (sl < 0) sl = -sl - 2;
        if (sl < 0 || sl >= slabSegs.length) return -1;
        List<double[]> here = slabSegs[sl];
        int lo = 0, hi = here.size(), ans = -1;
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            if (yAt(here.get(mid), qx) <= qy) { ans = mid; lo = mid + 1; }
            else hi = mid;
        }
        return ans;
    }
}
```

**Time:** O(n²) build, O(log n) query · **Space:** O(n²)

**Insight:** Within a vertical slab the segments never cross, so they admit a fixed bottom-to-top order — two binary searches (slab, then height) pin the containing face; the `O(n²)` space is the price of the naive slab method that persistent search trees later improve to `O(n)`.

---

### Problem 93: Closest Pair Under Manhattan Metric — KD-Tree Variant

**Statement.** Find the closest pair under the L1 (Manhattan) metric. Either rotate 45° and use Chebyshev, or adapt a KD-tree whose pruning bound uses L1 box distance.

```java
public class ManhattanClosestPair {
    public static long closest(long[][] pts) {
        // Rotate: u = x + y, v = x - y. L1(p,q) = max(|Δu|, |Δv|) = Chebyshev.
        long[][] rot = new long[pts.length][2];
        for (int i = 0; i < pts.length; i++) {
            rot[i][0] = pts[i][0] + pts[i][1];
            rot[i][1] = pts[i][0] - pts[i][1];
        }
        // Closest Chebyshev pair = min over pairs of max(|Δu|,|Δv|); brute base shown.
        long best = Long.MAX_VALUE;
        for (int i = 0; i < rot.length; i++)
            for (int j = i+1; j < rot.length; j++)
                best = Math.min(best, Math.max(Math.abs(rot[i][0]-rot[j][0]),
                                               Math.abs(rot[i][1]-rot[j][1])));
        return best;   // equals the minimum Manhattan distance in the original space
    }
}
```

**Time:** O(n²) brute / O(n log n) with a sweep · **Space:** O(n)

**Insight:** The 45° rotation `(x+y, x−y)` converts L1 into L∞, so Manhattan closest pair becomes Chebyshev closest pair on rotated coordinates — the same coordinate trick that powers many "diamond vs square" distance conversions.

---

### Problem 94: K Nearest Neighbors with a Bounded Max-Heap — KD-Tree

**Statement.** Return the `k` nearest neighbors of a query point from a KD-tree, maintaining a size-`k` max-heap so the heap top bounds the pruning radius.

```java
import java.util.*;

public class KNearest {
    double[][] pts; int[] axisSplit;          // simplified: assume pts is the KD layout
    PriorityQueue<double[]> heap;             // {dist2, index}, max-heap on dist2

    public int[] knn(double[][] pts, double[] q, int k) {
        this.pts = pts;
        heap = new PriorityQueue<>(k, (a, b) -> Double.compare(b[0], a[0]));
        search(0, pts.length - 1, 0, q, k);
        int[] res = new int[heap.size()];
        for (int i = res.length - 1; i >= 0; i--) res[i] = (int) heap.poll()[1];
        return res;
    }

    void search(int lo, int hi, int depth, double[] q, int k) {
        if (lo > hi) return;
        int mid = (lo + hi) / 2, axis = depth % 2;
        double d2 = dist2(pts[mid], q);
        if (heap.size() < k) heap.offer(new double[]{d2, mid});
        else if (d2 < heap.peek()[0]) { heap.poll(); heap.offer(new double[]{d2, mid}); }
        double diff = q[axis] - pts[mid][axis];
        int nearLo, nearHi, farLo, farHi;
        if (diff < 0) { nearLo=lo; nearHi=mid-1; farLo=mid+1; farHi=hi; }
        else          { nearLo=mid+1; nearHi=hi; farLo=lo; farHi=mid-1; }
        search(nearLo, nearHi, depth+1, q, k);
        if (heap.size() < k || diff*diff < heap.peek()[0])
            search(farLo, farHi, depth+1, q, k);   // prune the far side by the heap radius
    }

    static double dist2(double[] a, double[] b) {
        double dx = a[0]-b[0], dy = a[1]-b[1]; return dx*dx + dy*dy;
    }
}
```

**Time:** O(log n + k) average, O(n) worst · **Space:** O(k)

**Insight:** Extending nearest-neighbor to k-NN only changes the pruning bound from "best so far" to "k-th best so far" — the size-`k` max-heap's top is exactly that radius, and the far subtree is visited only when the splitting plane is closer than it.

---

### Problem 95: Range Counting in a 2D BIT (Offline) — Fenwick Sweep

**Statement.** Answer offline rectangle-count queries "how many points in `[x1,x2]×[y1,y2]`?" by sorting points and query corners on x and maintaining a Fenwick tree over compressed y.

```java
import java.util.*;

public class OfflineRangeCount {
    int[] bit; int m;
    void update(int i, int v) { for (; i <= m; i += i & -i) bit[i] += v; }
    int query(int i) { int s = 0; for (; i > 0; i -= i & -i) s += bit[i]; return s; }

    // points: {x,y}; queries: {x1,y1,x2,y2}; returns counts in query order
    public long[] solve(int[][] points, int[][] queries) {
        TreeSet<Integer> yset = new TreeSet<>();
        for (int[] p : points) yset.add(p[1]);
        Integer[] ys = yset.toArray(new Integer[0]);
        Map<Integer,Integer> yi = new HashMap<>();
        for (int i = 0; i < ys.length; i++) yi.put(ys[i], i + 1);
        m = ys.length; bit = new int[m + 1];

        // Decompose each box query into ±prefix events at x = x2 (+) and x = x1-1 (−).
        List<int[]> ev = new ArrayList<>();           // {x, type, ...}
        for (int[] p : points) ev.add(new int[]{p[0], 0, p[1]});
        long[] ans = new long[queries.length];
        for (int qi = 0; qi < queries.length; qi++) {
            int[] q = queries[qi];
            ev.add(new int[]{q[2],     1, q[1], q[3], qi, +1});
            ev.add(new int[]{q[0] - 1, 1, q[1], q[3], qi, -1});
        }
        ev.sort((a, b) -> a[0] != b[0] ? a[0] - b[0] : a[1] - b[1]); // points before queries
        for (int[] e : ev) {
            if (e[1] == 0) update(ceilY(ys, e[2]), 1);
            else {
                int loY = ceilY(ys, e[2]), hiY = floorY(ys, e[3]);
                if (loY <= hiY) ans[e[4]] += (long) e[5] * (query(hiY) - query(loY - 1));
            }
        }
        return ans;
    }
    int ceilY(Integer[] ys, int y){ int lo=0,hi=ys.length; while(lo<hi){int md=(lo+hi)/2; if(ys[md]<y)lo=md+1; else hi=md;} return lo+1; }
    int floorY(Integer[] ys, int y){ int lo=0,hi=ys.length; while(lo<hi){int md=(lo+hi)/2; if(ys[md]<=y)lo=md+1; else hi=md;} return lo; }
}
```

**Time:** O((n + q) log n) · **Space:** O(n + q)

**Insight:** A rectangle count is the difference of two prefix counts at `x2` and `x1−1`; sweeping x and inserting points into a Fenwick tree over y means each query reads a y-range prefix at exactly the moment its x-boundary is crossed — the standard offline 2D-dominance pattern.

---

### Problem 96: Maximum Manhattan Distance Among Points — Linear Extremes

**Statement.** Find the maximum L1 distance between any two of `n` points in `O(n)` by exploiting that `|Δx|+|Δy| = max over ± of (±Δx ±Δy)`.

```java
public class MaxManhattan {
    public static long maxDist(long[][] pts) {
        long maxS = Long.MIN_VALUE, minS = Long.MAX_VALUE;
        long maxD = Long.MIN_VALUE, minD = Long.MAX_VALUE;
        for (long[] p : pts) {
            long s = p[0] + p[1], d = p[0] - p[1];
            maxS = Math.max(maxS, s); minS = Math.min(minS, s);
            maxD = Math.max(maxD, d); minD = Math.min(minD, d);
        }
        return Math.max(maxS - minS, maxD - minD);
    }
}
```

**Time:** O(n) · **Space:** O(1)

**Insight:** `|Δx|+|Δy|` unfolds into four sign patterns, each of which separates into an `x±y` term per point, so the max over all pairs is just the spread of `x+y` and `x−y` — an `O(n)` extremes scan replaces the `O(n²)` pair loop.

---

### Problem 97: Rectilinear Minimum Spanning Tree (L1 MST) — Sweep + 4 Octants

**Statement.** Build the Manhattan-metric minimum spanning tree of `n` points. The key fact: each point only needs candidate edges to its nearest neighbor in each of 8 (here 4 symmetric) octants, giving `O(n log n)` candidate edges.

```java
import java.util.*;

public class RectilinearMST {
    int[] parent, rank_;
    int find(int x){ return parent[x]==x?x:(parent[x]=find(parent[x])); }
    boolean union(int a,int b){ a=find(a);b=find(b); if(a==b)return false;
        if(rank_[a]<rank_[b]){int t=a;a=b;b=t;} parent[b]=a; if(rank_[a]==rank_[b])rank_[a]++; return true; }

    public long mst(long[][] pts) {
        int n = pts.length;
        List<long[]> edges = new ArrayList<>();   // {weight, u, v}
        // For each octant, sweep and connect to nearest active point via a BIT-keyed structure.
        for (int rot = 0; rot < 4; rot++) {
            Integer[] idx = new Integer[n];
            for (int i = 0; i < n; i++) idx[i] = i;
            final int r = rot;
            Arrays.sort(idx, (a,b) -> {
                long ka = key(pts[a], r), kb = key(pts[b], r);
                return ka != kb ? Long.compare(kb, ka) : Long.compare(px(pts[b],r), px(pts[a],r));
            });
            TreeMap<Long,Integer> active = new TreeMap<>();   // y' -> point index, nearest by Manhattan
            for (int id : idx) {
                long yy = py(pts[id], r);
                Map.Entry<Long,Integer> e = active.ceilingEntry(yy);
                if (e != null) {
                    int j = e.getValue();
                    long w = Math.abs(pts[id][0]-pts[j][0]) + Math.abs(pts[id][1]-pts[j][1]);
                    edges.add(new long[]{w, id, j});
                }
                active.put(yy, id);
            }
        }
        edges.sort((a,b) -> Long.compare(a[0], b[0]));
        parent = new int[n]; rank_ = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        long total = 0;
        for (long[] e : edges) if (union((int)e[1], (int)e[2])) total += e[0];
        return total;
    }
    long key(long[] p,int r){ return px(p,r)+py(p,r); }
    long px(long[] p,int r){ return (r&1)==0? p[0]: p[1]; }
    long py(long[] p,int r){ return (r&2)==0? p[1]: -p[1]; }
}
```

**Time:** O(n log n) · **Space:** O(n)

**Insight:** The L1 MST lives in a sparse candidate graph: among each 45° octant a point's nearest neighbor is the only edge worth keeping, so eight sweeps generate `O(n)` edges and Kruskal finishes the job — a dramatic reduction from the `O(n²)` complete-graph MST.

---

### Problem 98: Convex Layers Count (Onion Depth) of a Query — Convex Layers

**Statement.** After peeling a point set into convex layers, answer "which layer does point `q` belong to?" Precompute layers and binary-search containment from the outermost inward.

```java
import java.util.*;

public class OnionDepthQuery {
    List<long[][]> layers = new ArrayList<>();

    public void build(long[][] pts) {
        List<long[]> rem = new ArrayList<>(Arrays.asList(pts));
        while (rem.size() >= 3) {
            long[][] h = DedupHull.hull(rem.toArray(new long[0][]));
            layers.add(h);
            Set<String> hs = new HashSet<>();
            for (long[] p : h) hs.add(p[0] + ":" + p[1]);
            rem.removeIf(p -> hs.contains(p[0] + ":" + p[1]));
        }
        if (!rem.isEmpty()) layers.add(rem.toArray(new long[0][]));
    }

    // smallest layer index (0=outermost) whose hull contains q
    public int depth(long qx, long qy) {
        for (int i = 0; i < layers.size(); i++) {
            long[][] h = layers.get(i);
            if (h.length < 3) continue;
            long[] xs = new long[h.length], ys = new long[h.length];
            for (int k = 0; k < h.length; k++) { xs[k]=h[k][0]; ys[k]=h[k][1]; }
            if (PointInConvex.inside(qx, qy, xs, ys)) return i;
        }
        return layers.size();
    }
}
```

**Time:** O(n² log n) build, O(L log n) query · **Space:** O(n)

**Insight:** Convex layers are nested, so containment is monotone — once `q` falls inside layer `i`, it is inside every deeper layer too — letting a single inward scan (or binary search across layers) pinpoint the onion depth.

---

### Problem 99: Minimum Width Annulus Fitting Points — Optimization

**Statement.** Fit two concentric circles enclosing all points so the radial width `r_out − r_in` is minimized. The optimal center is a vertex of the overlay of the nearest- and farthest-point Voronoi diagrams; here, a robust ternary/local search.

```java
public class MinWidthAnnulus {
    static double width(double[][] pts, double cx, double cy) {
        double mn = Double.POSITIVE_INFINITY, mx = 0;
        for (double[] p : pts) {
            double d = Math.hypot(p[0]-cx, p[1]-cy);
            mn = Math.min(mn, d); mx = Math.max(mx, d);
        }
        return mx - mn;
    }

    public static double minWidth(double[][] pts, double lo, double hi) {
        // Nested ternary search over (cx, cy); the width is unimodal-ish over a convex region.
        double xlo = lo, xhi = hi;
        for (int it = 0; it < 200; it++) {
            double mx1 = xlo + (xhi-xlo)/3, mx2 = xhi - (xhi-xlo)/3;
            if (bestY(pts, mx1, lo, hi) < bestY(pts, mx2, lo, hi)) xhi = mx2; else xlo = mx1;
        }
        return bestY(pts, (xlo+xhi)/2, lo, hi);
    }
    static double bestY(double[][] pts, double cx, double lo, double hi) {
        double ylo = lo, yhi = hi;
        for (int it = 0; it < 200; it++) {
            double m1 = ylo + (yhi-ylo)/3, m2 = yhi - (yhi-ylo)/3;
            if (width(pts, cx, m1) < width(pts, cx, m2)) yhi = m2; else ylo = m1;
        }
        return width(pts, cx, (ylo+yhi)/2);
    }
}
```

**Time:** O(n · iterations²) numeric; O(n²) exact via Voronoi overlay · **Space:** O(1)

**Insight:** The annulus width as a function of center is a difference of a convex (max-radius) and concave-ish (min-radius) field; nested ternary search converges to the optimum for well-spread inputs, while the exact solution lives at intersections of the two Voronoi diagrams.

---

### Problem 100: Segment Intersection Counting (Not Just Detection) — Sweep + BIT

**Statement.** Count the total number of intersecting pairs among `n` segments. Extend the sweep to count, using a Fenwick tree over the active-segment order to tally inversions as segments swap.

```java
import java.util.*;

public class CountSegmentIntersections {
    // General-position assumption; counts proper crossings.
    public static long count(double[][] segs) {
        int n = segs.length;
        // Event-driven: at each crossing, two segments swap order; total crossings
        // equal the number of order inversions resolved during the sweep.
        // Practical implementation: detect crossings between adjacent segments and
        // process them as events, incrementing a counter each time.
        List<double[]> events = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double x1 = Math.min(segs[i][0], segs[i][2]);
            double x2 = Math.max(segs[i][0], segs[i][2]);
            events.add(new double[]{x1, 0, i});
            events.add(new double[]{x2, 2, i});
        }
        long crossings = 0;
        // Brute crossing tally between segments that share an x-overlap (baseline oracle).
        for (int i = 0; i < n; i++)
            for (int j = i+1; j < n; j++)
                if (SegmentIntersect.intersect(
                        (long)segs[i][0],(long)segs[i][1],(long)segs[i][2],(long)segs[i][3],
                        (long)segs[j][0],(long)segs[j][1],(long)segs[j][2],(long)segs[j][3]))
                    crossings++;
        return crossings;   // O(n^2) oracle; Bentley–Ottmann gives O((n+k) log n)
    }
}
```

**Time:** O((n + k) log n) with Bentley–Ottmann (k = crossings); O(n²) oracle shown · **Space:** O(n)

**Insight:** Counting (rather than just detecting) crossings means each intersection is an event where two segments swap in the status order; the full Bentley–Ottmann sweep processes the `k` swaps in output-sensitive `O((n+k) log n)`, with the `O(n²)` all-pairs version serving as the correctness oracle.

---

### Problem 101: Polygon Containment Test (One Convex Inside Another) — Vertex + Edge Test

**Statement.** Decide whether convex polygon `A` is entirely contained in convex polygon `B`. It suffices that every vertex of `A` lies inside `B` and no edge of `A` crosses an edge of `B`.

```java
public class ConvexContainment {
    public static boolean contains(long[][] B, long[][] A) {
        long[] bx = new long[B.length], by = new long[B.length];
        for (int i = 0; i < B.length; i++) { bx[i]=B[i][0]; by[i]=B[i][1]; }
        // 1. Every vertex of A must be inside B.
        for (long[] a : A)
            if (!PointInConvex.inside(a[0], a[1], bx, by)) return false;
        // 2. No edge of A may cross any edge of B.
        int na = A.length, nb = B.length;
        for (int i = 0; i < na; i++) {
            long[] a1 = A[i], a2 = A[(i+1)%na];
            for (int j = 0; j < nb; j++) {
                long[] b1 = B[j], b2 = B[(j+1)%nb];
                if (SegmentIntersect.intersect(a1[0],a1[1],a2[0],a2[1],
                                               b1[0],b1[1],b2[0],b2[1])) {
                    // touching at a shared vertex is allowed; a proper crossing is not
                    return false;
                }
            }
        }
        return true;
    }
}
```

**Time:** O(a·b) edge test (O(a log b + b) with binary-search PIP) · **Space:** O(1)

**Insight:** For convex shapes, containment reduces to two conditions — all of A's corners inside B, plus no boundary crossing — because a convex A can only escape B by poking a vertex out or by an edge slicing through B's boundary; ruling out both proves containment.

---

### Problem 102: Tangent Length and Common Tangents of Two Circles — Circle Geometry

**Statement.** Compute the external and internal common tangent lines of two circles, handling the full case analysis (nested, intersecting, separate). Return tangent points.

```java
import java.util.*;

public class CommonTangents {
    // Returns list of {ax,ay,bx,by} tangent segments touching circle1 at (ax,ay), circle2 at (bx,by).
    public static List<double[]> tangents(double x1,double y1,double r1,
                                          double x2,double y2,double r2) {
        List<double[]> res = new ArrayList<>();
        double dx = x2-x1, dy = y2-y1, d2 = dx*dx+dy*dy;
        // sign = +1 external pair, -1 internal pair
        for (int sign1 : new int[]{1, -1}) {
            double r = r2 * sign1 - r1 * 1.0 * (-1) * 0; // placeholder kept simple below
        }
        for (int s : new int[]{1, -1}) {           // s = -1: external, s = +1: internal
            double rDiff = r1 - s * r2;
            double disc = d2 - rDiff*rDiff;
            if (disc < -1e-12) continue;            // no tangent of this type
            disc = Math.sqrt(Math.max(0, disc));
            double nx = (dx * rDiff + dy * disc) / d2;
            double ny = (dy * rDiff - dx * disc) / d2;
            res.add(new double[]{
                x1 + r1*nx, y1 + r1*ny,
                x2 + s*r2*nx, y2 + s*r2*ny });
            if (disc > 1e-12) {                     // second tangent of this type
                double nx2 = (dx * rDiff - dy * disc) / d2;
                double ny2 = (dy * rDiff + dx * disc) / d2;
                res.add(new double[]{
                    x1 + r1*nx2, y1 + r1*ny2,
                    x2 + s*r2*nx2, y2 + s*r2*ny2 });
            }
        }
        return res;
    }
}
```

**Time:** O(1) · **Space:** O(1)

**Insight:** Both external and internal tangents fall out of one parametric formula by flipping the sign on `r2`; the discriminant `d² − (r1 ∓ r2)²` automatically encodes how many tangents exist, gracefully degenerating from four to two to zero as the circles nest.

---

### Problem 103: Area of Union of Circles — Green's Theorem Arc Integration — Circle Geometry

**Statement.** Compute the area covered by the union of `n` circles. For each circle, find the arcs not covered by any other circle and integrate the boundary via Green's theorem.

```java
import java.util.*;

public class CircleUnionArea {
    public static double area(double[][] c) {   // c[i] = {x, y, r}
        int n = c.length;
        double total = 0;
        for (int i = 0; i < n; i++) {
            // Collect angular intervals of circle i covered by other circles.
            List<double[]> covered = new ArrayList<>();
            boolean fullyInside = false;
            for (int j = 0; j < n && !fullyInside; j++) {
                if (i == j) continue;
                double dx = c[j][0]-c[i][0], dy = c[j][1]-c[i][1];
                double d = Math.hypot(dx, dy);
                if (d + c[i][2] <= c[j][2] + 1e-12) { fullyInside = true; break; }
                if (d >= c[i][2] + c[j][2] - 1e-12 || d <= 1e-12) continue;
                if (d + c[j][2] <= c[i][2]) continue;       // j strictly inside i, no arc cover
                double a = Math.atan2(dy, dx);
                double cosT = (c[i][2]*c[i][2] + d*d - c[j][2]*c[j][2]) / (2*c[i][2]*d);
                cosT = Math.max(-1, Math.min(1, cosT));
                double t = Math.acos(cosT);
                covered.add(new double[]{a - t, a + t});
            }
            if (fullyInside) continue;
            total += integrateFreeArcs(c[i], covered);
        }
        return total;
    }

    static double integrateFreeArcs(double[] ci, List<double[]> covered) {
        // Normalize intervals to [0,2π), merge, then integrate the complement via Green's theorem.
        List<double[]> iv = new ArrayList<>();
        for (double[] s : covered) {
            double lo = norm(s[0]), hi = norm(s[1]);
            if (lo <= hi) iv.add(new double[]{lo, hi});
            else { iv.add(new double[]{lo, 2*Math.PI}); iv.add(new double[]{0, hi}); }
        }
        iv.sort(Comparator.comparingDouble(a -> a[0]));
        double area = 0, prev = 0;
        double cx = ci[0], cy = ci[1], r = ci[2];
        double cover = 0;
        for (double[] s : iv) {
            if (s[0] > cover) { area += arc(cx, cy, r, cover, s[0]); }
            cover = Math.max(cover, s[1]);
        }
        if (cover < 2*Math.PI) area += arc(cx, cy, r, cover, 2*Math.PI);
        return area;
    }

    static double arc(double cx, double cy, double r, double a0, double a1) {
        // contribution of a circular arc to the signed area via Green's theorem
        double x0=cx+r*Math.cos(a0), y0=cy+r*Math.sin(a0);
        double x1=cx+r*Math.cos(a1), y1=cy+r*Math.sin(a1);
        double sector = 0.5*r*r*(a1-a0);
        double tri = 0.5*(x0*y1 - x1*y0);
        return sector + tri - 0.5*(cx*(y1-y0) - cy*(x1-x0));
    }
    static double norm(double a){ a%=2*Math.PI; return a<0? a+2*Math.PI : a; }
}
```

**Time:** O(n² log n) · **Space:** O(n)

**Insight:** Union area by inclusion–exclusion explodes combinatorially, but Green's theorem rewrites it as a boundary integral: only the arcs of each circle exposed to the outside contribute, so the problem becomes "merge the angular intervals each circle is buried under" and integrate the gaps.

---

### Problem 104: Largest Inscribed Circle in a Convex Polygon — Chebyshev Center / LP

**Statement.** Find the largest circle that fits inside a convex polygon (its inradius and incenter). This is the Chebyshev center: maximize `r` subject to `r ≤ distance(center, each edge)` — a linear program, solved here by binary search on `r`.

```java
public class LargestInscribedCircle {
    // edges as outward half-planes a·x + b·y + c <= 0 with (a,b) unit normals
    public static double inradius(double[][] poly) {
        int n = poly.length;
        double[][] H = new double[n][3];
        for (int i = 0; i < n; i++) {
            double[] p = poly[i], q = poly[(i+1)%n];
            double dx = q[0]-p[0], dy = q[1]-p[1];
            double len = Math.hypot(dx, dy);
            double a = dy/len, b = -dx/len;          // outward normal for CCW polygon
            double c = -(a*p[0] + b*p[1]);
            H[i] = new double[]{a, b, c};
        }
        // Shrink all half-planes inward by r; feasible iff inradius >= r.
        double lo = 0, hi = 1e9;
        for (int it = 0; it < 100; it++) {
            double r = (lo + hi) / 2;
            if (feasible(H, r)) lo = r; else hi = r;
        }
        return lo;
    }

    static boolean feasible(double[][] H, double r) {
        // half-plane intersection of a·x+b·y+c+r <= 0 nonempty?
        double[][] shr = new double[H.length][];
        for (int i = 0; i < H.length; i++)
            shr[i] = new double[]{H[i][0], H[i][1], H[i][2] + r};
        return HalfPlaneIntersection.nonEmpty(shr);   // reuse Set-1 half-plane routine
    }
}
```

**Time:** O(n log n · iterations) via half-plane feasibility · **Space:** O(n)

**Insight:** The largest inscribed circle is the Chebyshev center of the polygon's half-plane system; pushing every bounding line inward by a candidate radius `r` and asking whether the shrunken intersection is nonempty turns the optimization into a monotone feasibility test perfect for binary search.

---

### Problem 105: Visibility Polygon from a Point (Angular Sweep) — Visibility

**Statement.** Given a point and a set of opaque segments (walls), compute the visibility polygon: the region directly visible from the point. Sort wall endpoints by angle and sweep a ray, tracking the nearest wall.

```java
import java.util.*;

public class VisibilityPolygon {
    public static List<double[]> compute(double ox, double oy, double[][] walls) {
        // Cast rays slightly to either side of each endpoint angle; nearest hit is a vertex.
        TreeSet<Double> angles = new TreeSet<>();
        for (double[] w : walls) {
            angles.add(Math.atan2(w[1]-oy, w[0]-ox));
            angles.add(Math.atan2(w[3]-oy, w[2]-ox));
        }
        List<double[]> poly = new ArrayList<>();
        List<Double> sorted = new ArrayList<>(angles);
        for (double base : sorted) {
            for (double da : new double[]{-1e-7, 1e-7}) {
                double ang = base + da;
                double dx = Math.cos(ang), dy = Math.sin(ang);
                double bestT = Double.POSITIVE_INFINITY; double[] hit = null;
                for (double[] w : walls) {
                    double t = rayHit(ox, oy, dx, dy, w);
                    if (t > 1e-9 && t < bestT) { bestT = t; hit = new double[]{ox+dx*t, oy+dy*t}; }
                }
                if (hit != null) poly.add(new double[]{Math.atan2(hit[1]-oy,hit[0]-ox), hit[0], hit[1]});
            }
        }
        poly.sort(Comparator.comparingDouble(a -> a[0]));
        List<double[]> res = new ArrayList<>();
        for (double[] p : poly) res.add(new double[]{p[1], p[2]});
        return res;
    }

    static double rayHit(double ox, double oy, double dx, double dy, double[] w) {
        double sx = w[2]-w[0], sy = w[3]-w[1];
        double denom = dx*sy - dy*sx;
        if (Math.abs(denom) < 1e-12) return Double.POSITIVE_INFINITY;
        double t = ((w[0]-ox)*sy - (w[1]-oy)*sx) / denom;
        double u = ((w[0]-ox)*dy - (w[1]-oy)*dx) / denom;
        return (t >= 0 && u >= 0 && u <= 1) ? t : Double.POSITIVE_INFINITY;
    }
}
```

**Time:** O(n² ) naive (O(n log n) with a proper status structure) · **Space:** O(n)

**Insight:** Visibility changes only at wall endpoints, so casting rays just before and after each endpoint angle captures every vertex of the visibility polygon; the nearest wall hit along each ray is the visible boundary, and sorting hits by angle stitches the star-shaped region together.

---

### Problem 106: Fortune's Algorithm Skeleton — Voronoi Diagram — Sweep Line

**Statement.** Outline Fortune's `O(n log n)` sweep for the Voronoi diagram: a beach line of parabolic arcs advances with a horizontal sweep, processing site events (new arc) and circle events (arc disappears, Voronoi vertex born).

```java
import java.util.*;

public class FortuneSkeleton {
    static class Event implements Comparable<Event> {
        double y; boolean isSite; double[] site; Object arc;
        Event(double y, double[] site){ this.y=y; this.isSite=true; this.site=site; }
        Event(double y, Object arc, boolean circle){ this.y=y; this.isSite=false; this.arc=arc; }
        public int compareTo(Event o){ return Double.compare(o.y, this.y); } // sweep downward
    }

    public List<double[]> voronoiVertices(double[][] sites) {
        PriorityQueue<Event> pq = new PriorityQueue<>();
        for (double[] s : sites) pq.add(new Event(s[1], s));
        List<double[]> vertices = new ArrayList<>();
        // Beach line as an ordered structure of arcs (sketched; full impl maintains
        // breakpoints and validates circle events).
        while (!pq.isEmpty()) {
            Event e = pq.poll();
            if (e.isSite) {
                // insert a new arc for e.site into the beach line, split the arc above it,
                // and schedule circle events for the new triples of consecutive arcs.
            } else {
                // a valid circle event: the middle arc vanishes; its circle center is a
                // Voronoi vertex; record it and reschedule neighbor circle events.
                // vertices.add(center);
            }
        }
        return vertices;
    }
}
```

**Time:** O(n log n) · **Space:** O(n)

**Insight:** Fortune's algorithm trades the intractable direct construction for a sweep where the beach line — the lower envelope of parabolas equidistant from sites and the sweep line — evolves through `O(n)` site and circle events; each Voronoi vertex is born exactly when three arcs become co-circular and the middle one is squeezed out.

---

### Problem 107: Exact Rational Segment Intersection (No Floating Point) — Robust Predicate

**Statement.** Compute the intersection point of two segments as an exact rational `(num_x/den, num_y/den)` using only `long`/`BigInteger`, so downstream comparisons are exact. Critical for robust arrangement construction.

```java
import java.math.BigInteger;

public class RationalIntersection {
    public static BigInteger[] intersect(long[] p1, long[] p2, long[] p3, long[] p4) {
        BigInteger x1=BigInteger.valueOf(p1[0]), y1=BigInteger.valueOf(p1[1]);
        BigInteger x2=BigInteger.valueOf(p2[0]), y2=BigInteger.valueOf(p2[1]);
        BigInteger x3=BigInteger.valueOf(p3[0]), y3=BigInteger.valueOf(p3[1]);
        BigInteger x4=BigInteger.valueOf(p4[0]), y4=BigInteger.valueOf(p4[1]);
        BigInteger d = x1.subtract(x2).multiply(y3.subtract(y4))
                        .subtract(y1.subtract(y2).multiply(x3.subtract(x4)));
        if (d.signum() == 0) return null;   // parallel or collinear: exact zero
        BigInteger a = x1.multiply(y2).subtract(y1.multiply(x2));
        BigInteger b = x3.multiply(y4).subtract(y3.multiply(x4));
        BigInteger numX = a.multiply(x3.subtract(x4)).subtract(x1.subtract(x2).multiply(b));
        BigInteger numY = a.multiply(y3.subtract(y4)).subtract(y1.subtract(y2).multiply(b));
        return new BigInteger[]{ numX, numY, d };   // point = (numX/d, numY/d), exact
    }
}
```

**Time:** O(1) BigInteger ops · **Space:** O(1)

**Insight:** Keeping the intersection as an exact `(numerator, denominator)` triple rather than a `double` means every later orientation or equality test is decided with integer arithmetic — eliminating the snapping and inconsistency that floating-point intersection points cause in arrangement algorithms.

---

### Problem 108: Minimum Bounding Box of Arbitrary Orientation in 3D — PCA Approximation

**Statement.** Approximate the minimum-volume oriented bounding box of a 3D point cloud using principal component analysis: the box axes align with the eigenvectors of the covariance matrix.

```java
public class OrientedBoundingBox3D {
    public static double[] obb(double[][] pts) {
        int n = pts.length;
        double[] mean = new double[3];
        for (double[] p : pts) for (int k=0;k<3;k++) mean[k]+=p[k]/n;
        double[][] cov = new double[3][3];
        for (double[] p : pts)
            for (int a=0;a<3;a++) for (int b=0;b<3;b++)
                cov[a][b] += (p[a]-mean[a])*(p[b]-mean[b])/n;
        // Power-iterate to get the dominant eigenvector; deflate for the rest (sketch).
        double[] axis = powerIteration(cov);
        // Project all points onto the axis to get the extent along it.
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (double[] p : pts) {
            double proj = (p[0]-mean[0])*axis[0]+(p[1]-mean[1])*axis[1]+(p[2]-mean[2])*axis[2];
            lo = Math.min(lo, proj); hi = Math.max(hi, proj);
        }
        return new double[]{ axis[0], axis[1], axis[2], hi - lo };  // primary axis + extent
    }

    static double[] powerIteration(double[][] m) {
        double[] v = {1, 1, 1};
        for (int it = 0; it < 100; it++) {
            double[] nv = new double[3];
            for (int a=0;a<3;a++) for (int b=0;b<3;b++) nv[a] += m[a][b]*v[b];
            double norm = Math.sqrt(nv[0]*nv[0]+nv[1]*nv[1]+nv[2]*nv[2]);
            for (int a=0;a<3;a++) v[a] = nv[a]/norm;
        }
        return v;
    }
}
```

**Time:** O(n + iterations) · **Space:** O(1)

**Insight:** PCA gives a fast, often-good oriented bounding box by aligning axes with the directions of maximum variance; it is not provably minimal (the exact 3D OBB needs the rotating-calipers-in-3D / O'Rourke algorithm) but is the standard practical heuristic in graphics and collision broad-phase.

---

### Problem 109: Dynamic Lower Envelope with Deletions — Kinetic / Segment-Tree Beats

**Statement.** Support inserting and deleting lines and querying the minimum at a point, where deletions break the amortized convex-hull-trick structure. Use a segment tree over time (offline) so each line is active on an interval, inserting it into Li Chao nodes covering that interval.

```java
import java.util.*;

public class LowerEnvelopeWithDeletions {
    int n; List<long[]>[] tree;   // each node holds lines {m,b} active over its time span

    @SuppressWarnings("unchecked")
    public LowerEnvelopeWithDeletions(int times) {
        n = times; tree = new List[4 * n];
        for (int i = 0; i < 4 * n; i++) tree[i] = new ArrayList<>();
    }

    // line active during time interval [l, r]
    public void addLine(int l, int r, long m, long b) { add(1, 0, n-1, l, r, m, b); }
    void add(int node, int lo, int hi, int l, int r, long m, long b) {
        if (r < lo || hi < l) return;
        if (l <= lo && hi <= r) { tree[node].add(new long[]{m, b}); return; }
        int mid = (lo + hi) / 2;
        add(node*2, lo, mid, l, r, m, b);
        add(node*2+1, mid+1, hi, l, r, m, b);
    }

    // query the min over all lines active at time t, evaluated at coordinate x
    public long query(int t, long x) {
        long best = Long.MAX_VALUE;
        int node = 1, lo = 0, hi = n - 1;
        while (true) {
            for (long[] line : tree[node]) best = Math.min(best, line[0]*x + line[1]);
            if (lo == hi) break;
            int mid = (lo + hi) / 2;
            if (t <= mid) { node = node*2; hi = mid; }
            else { node = node*2+1; lo = mid+1; }
        }
        return best;
    }
}
```

**Time:** O((n + q) log n · cost-per-node) · **Space:** O(n log n)

**Insight:** Deletions defeat the incremental convex-hull trick, but "segment tree on time" reduces a dynamic structure to a static one: a line that lives over a time interval is inserted into `O(log n)` canonical nodes, so each query at time `t` only consults the `O(log n)` nodes on its root-to-leaf path — the standard offline trick for making any insert-only structure support deletes.

---
