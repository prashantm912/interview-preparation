public class CountRangeSum {

    public int countRangeSum(int[] nums, int lower, int upper) {
        int n = nums.length;
        long[] pre = new long[n + 1];
        for (int i = 0; i < n; i++) pre[i + 1] = pre[i] + nums[i];
        long[] aux = new long[n + 1];
        return sort(pre, aux, 0, n, lower, upper);
    }

    private int sort(long[] p, long[] aux, int lo, int hi, int lower, int upper) {
        if (lo >= hi) return 0;
        int mid = lo + (hi - lo) / 2;
        int count = sort(p, aux, lo, mid, lower, upper)
                  + sort(p, aux, mid + 1, hi, lower, upper);

        int low = mid + 1, high = mid + 1;
        for (int i = lo; i <= mid; i++) {
            while (low <= hi && p[low] - p[i] < lower) low++;
            while (high <= hi && p[high] - p[i] <= upper) high++;
            count += high - low;
        }

        int i = lo, j = mid + 1, k = lo;
        while (i <= mid && j <= hi) aux[k++] = (p[i] <= p[j]) ? p[i++] : p[j++];
        while (i <= mid) aux[k++] = p[i++];
        while (j <= hi)  aux[k++] = p[j++];
        System.arraycopy(aux, lo, p, lo, hi - lo + 1);
        return count;
    }

    private static int brute(int[] a, int lower, int upper) {
        int n = a.length, c = 0;
        for (int i = 0; i < n; i++) {
            long s = 0;
            for (int j = i; j < n; j++) {
                s += a[j];
                if (s >= lower && s <= upper) c++;
            }
        }
        return c;
    }

    public static void main(String[] args) {
        CountRangeSum s = new CountRangeSum();
        assert s.countRangeSum(new int[]{-2,5,-1}, -2, 2) == 3;
        java.util.Random r = new java.util.Random(13);
        for (int t = 0; t < 2000; t++) {
            int n = 1 + r.nextInt(12);
            int[] arr = new int[n];
            for (int i = 0; i < n; i++) arr[i] = r.nextInt(21) - 10;
            int lo = r.nextInt(11) - 5, hi = lo + r.nextInt(8);
            int[] copy = arr.clone();
            if (s.countRangeSum(arr, lo, hi) != brute(copy, lo, hi)) throw new AssertionError("mismatch");
        }
        System.out.println("CountRangeSum OK");
    }
}
