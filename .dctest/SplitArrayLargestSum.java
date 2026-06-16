public class SplitArrayLargestSum {

    public int splitArray(int[] nums, int m) {
        long lo = 0, hi = 0;
        for (int x : nums) { lo = Math.max(lo, x); hi += x; }
        while (lo < hi) {
            long mid = lo + (hi - lo) / 2;
            if (partsNeeded(nums, mid) <= m) hi = mid;
            else lo = mid + 1;
        }
        return (int) lo;
    }

    private int partsNeeded(int[] nums, long cap) {
        int parts = 1;
        long cur = 0;
        for (int x : nums) {
            if (cur + x > cap) { parts++; cur = x; }
            else cur += x;
        }
        return parts;
    }

    // brute DP for verification
    private static int bruteDP(int[] a, int m) {
        int n = a.length;
        long[] pre = new long[n + 1];
        for (int i = 0; i < n; i++) pre[i + 1] = pre[i] + a[i];
        long[][] dp = new long[m + 1][n + 1];
        for (long[] row : dp) java.util.Arrays.fill(row, Long.MAX_VALUE);
        dp[0][0] = 0;
        for (int k = 1; k <= m; k++)
            for (int i = 1; i <= n; i++)
                for (int j = 0; j < i; j++) {
                    if (dp[k - 1][j] == Long.MAX_VALUE) continue;
                    long part = pre[i] - pre[j];
                    dp[k][i] = Math.min(dp[k][i], Math.max(dp[k - 1][j], part));
                }
        return (int) dp[m][n];
    }

    public static void main(String[] args) {
        SplitArrayLargestSum s = new SplitArrayLargestSum();
        assert s.splitArray(new int[]{7,2,5,10,8}, 2) == 18;
        java.util.Random r = new java.util.Random(23);
        for (int t = 0; t < 2000; t++) {
            int n = 1 + r.nextInt(8);
            int[] arr = new int[n];
            for (int i = 0; i < n; i++) arr[i] = r.nextInt(15);
            int m = 1 + r.nextInt(n);
            int[] copy = arr.clone();
            if (s.splitArray(arr, m) != bruteDP(copy, m)) throw new AssertionError("mismatch");
        }
        System.out.println("SplitArrayLargestSum OK");
    }
}
