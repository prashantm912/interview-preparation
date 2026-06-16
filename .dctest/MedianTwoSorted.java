public class MedianTwoSorted {

    public double findMedianSortedArrays(int[] a, int[] b) {
        if (a.length > b.length) return findMedianSortedArrays(b, a); // search smaller
        int m = a.length, n = b.length, half = (m + n + 1) / 2;
        int lo = 0, hi = m;
        while (lo <= hi) {
            int i = lo + (hi - lo) / 2;       // take i from a
            int j = half - i;                 // take j from b
            int aLeft  = (i == 0) ? Integer.MIN_VALUE : a[i - 1];
            int aRight = (i == m) ? Integer.MAX_VALUE : a[i];
            int bLeft  = (j == 0) ? Integer.MIN_VALUE : b[j - 1];
            int bRight = (j == n) ? Integer.MAX_VALUE : b[j];
            if (aLeft <= bRight && bLeft <= aRight) {           // valid partition
                int maxLeft = Math.max(aLeft, bLeft);
                if (((m + n) & 1) == 1) return maxLeft;         // odd total
                int minRight = Math.min(aRight, bRight);
                return (maxLeft + minRight) / 2.0;              // even total
            } else if (aLeft > bRight) hi = i - 1;              // too many from a
            else lo = i + 1;                                    // too few from a
        }
        throw new IllegalArgumentException("inputs not sorted");
    }

    public static void main(String[] args) {
        MedianTwoSorted s = new MedianTwoSorted();
        assert s.findMedianSortedArrays(new int[]{1,3}, new int[]{2}) == 2.0;
        assert s.findMedianSortedArrays(new int[]{1,2}, new int[]{3,4}) == 2.5;
        assert s.findMedianSortedArrays(new int[]{}, new int[]{1}) == 1.0;
        assert s.findMedianSortedArrays(new int[]{2}, new int[]{}) == 2.0;
        System.out.println("MedianTwoSorted OK");
    }
}
