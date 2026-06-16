public class MaxCircularSubarray {

    public int maxSubarraySumCircular(int[] nums) {
        int total = 0;
        int maxSum = nums[0], curMax = 0;
        int minSum = nums[0], curMin = 0;
        for (int x : nums) {
            curMax = Math.max(curMax + x, x);
            maxSum = Math.max(maxSum, curMax);
            curMin = Math.min(curMin + x, x);
            minSum = Math.min(minSum, curMin);
            total += x;
        }
        return (maxSum > 0) ? Math.max(maxSum, total - minSum) : maxSum;
    }

    private static int brute(int[] a) {
        int n = a.length, best = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            int s = 0;
            for (int len = 1; len <= n; len++) {
                s += a[(i + len - 1) % n];
                best = Math.max(best, s);
            }
        }
        return best;
    }

    public static void main(String[] args) {
        MaxCircularSubarray s = new MaxCircularSubarray();
        assert s.maxSubarraySumCircular(new int[]{5,-3,5}) == 10;
        assert s.maxSubarraySumCircular(new int[]{-3,-2,-3}) == -2;
        java.util.Random r = new java.util.Random(31);
        for (int t = 0; t < 5000; t++) {
            int n = 1 + r.nextInt(9);
            int[] arr = new int[n];
            for (int i = 0; i < n; i++) arr[i] = r.nextInt(13) - 6;
            if (s.maxSubarraySumCircular(arr.clone()) != brute(arr.clone()))
                throw new AssertionError("mismatch " + java.util.Arrays.toString(arr));
        }
        System.out.println("MaxCircularSubarray OK");
    }
}
