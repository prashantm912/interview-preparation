import java.util.Random;

public class KClosestPoints {
    private final Random rnd = new Random();

    public int[][] kClosest(int[][] points, int k) {
        int lo = 0, hi = points.length - 1;
        while (lo < hi) {
            int p = partition(points, lo, hi);
            if (p == k) break;
            else if (p < k) lo = p + 1;
            else hi = p - 1;
        }
        return java.util.Arrays.copyOfRange(points, 0, k);
    }

    private int partition(int[][] pts, int lo, int hi) {
        swap(pts, lo + rnd.nextInt(hi - lo + 1), hi);
        long pivot = dist(pts[hi]);
        int store = lo;
        for (int i = lo; i < hi; i++)
            if (dist(pts[i]) < pivot) swap(pts, store++, i);
        swap(pts, store, hi);
        return store;
    }

    private long dist(int[] p) { return (long) p[0] * p[0] + (long) p[1] * p[1]; }
    private void swap(int[][] a, int i, int j) { int[] t = a[i]; a[i] = a[j]; a[j] = t; }

    public static void main(String[] args) {
        KClosestPoints s = new KClosestPoints();
        Random r = new Random(37);
        for (int t = 0; t < 3000; t++) {
            int n = 1 + r.nextInt(20);
            int[][] pts = new int[n][2];
            for (int i = 0; i < n; i++) { pts[i][0] = r.nextInt(21) - 10; pts[i][1] = r.nextInt(21) - 10; }
            int k = 1 + r.nextInt(n);
            // expected: the k smallest distances (as a multiset of distances)
            long[] allD = new long[n];
            for (int i = 0; i < n; i++) allD[i] = (long) pts[i][0]*pts[i][0] + (long) pts[i][1]*pts[i][1];
            long[] sortedD = allD.clone();
            java.util.Arrays.sort(sortedD);
            int[][] res = s.kClosest(pts.clone(), k);
            // every returned distance must be <= sortedD[k-1]; and the multiset of k returned distances == first k of sortedD
            long[] gotD = new long[k];
            for (int i = 0; i < k; i++) gotD[i] = (long) res[i][0]*res[i][0] + (long) res[i][1]*res[i][1];
            java.util.Arrays.sort(gotD);
            for (int i = 0; i < k; i++)
                if (gotD[i] != sortedD[i]) throw new AssertionError("mismatch");
        }
        System.out.println("KClosestPoints OK");
    }
}
