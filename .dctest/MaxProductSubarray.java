public class MaxProductSubarray {

    public int maxProduct(int[] nums) {
        int best = nums[0], maxHere = nums[0], minHere = nums[0];
        for (int i = 1; i < nums.length; i++) {
            int x = nums[i];
            if (x < 0) { int t = maxHere; maxHere = minHere; minHere = t; }
            maxHere = Math.max(x, maxHere * x);
            minHere = Math.min(x, minHere * x);
            best = Math.max(best, maxHere);
        }
        return best;
    }

    public int maxProductDC(int[] nums) {
        return solve(nums, 0, nums.length - 1)[0];
    }

    private int[] solve(int[] a, int lo, int hi) {
        if (lo == hi) {
            int v = a[lo];
            return new int[]{v, v, v, v, v, v};
        }
        int mid = lo + (hi - lo) / 2;
        int[] L = solve(a, lo, mid), R = solve(a, mid + 1, hi);

        int best = Math.max(L[0], R[0]);
        int crossMax = Math.max(L[3] * R[1], L[4] * R[2]);
        int crossMin = Math.min(L[3] * R[2], L[4] * R[1]);
        best = Math.max(best, crossMax);

        int total = L[5] * R[5];
        int maxPrefix = Math.max(L[1], L[5] * R[1]);
        int minPrefix = Math.min(L[2], L[5] * R[2]);
        int maxSuffix = Math.max(R[3], R[5] * L[3]);
        int minSuffix = Math.min(R[4], R[5] * L[4]);
        maxPrefix = Math.max(maxPrefix, L[5] * R[2]);
        minPrefix = Math.min(minPrefix, L[5] * R[1]);
        maxSuffix = Math.max(maxSuffix, R[5] * L[4]);
        minSuffix = Math.min(minSuffix, R[5] * L[3]);

        return new int[]{best, maxPrefix, minPrefix, maxSuffix, minSuffix, total};
    }

    private static int brute(int[] a) {
        int best = Integer.MIN_VALUE;
        for (int i = 0; i < a.length; i++) {
            int p = 1;
            for (int j = i; j < a.length; j++) {
                p *= a[j];
                best = Math.max(best, p);
            }
        }
        return best;
    }

    public static void main(String[] args) {
        MaxProductSubarray s = new MaxProductSubarray();
        assert s.maxProduct(new int[]{2,3,-2,4}) == 6;
        assert s.maxProduct(new int[]{-2,0,-1}) == 0;
        java.util.Random r = new java.util.Random(29);
        int dcFails = 0;
        for (int t = 0; t < 5000; t++) {
            int n = 1 + r.nextInt(9);
            int[] arr = new int[n];
            for (int i = 0; i < n; i++) arr[i] = r.nextInt(7) - 3;
            int exp = brute(arr.clone());
            if (s.maxProduct(arr.clone()) != exp) throw new AssertionError("LINEAR mismatch " + java.util.Arrays.toString(arr));
            if (s.maxProductDC(arr.clone()) != exp) {
                dcFails++;
                if (dcFails <= 5) System.out.println("DC mismatch " + java.util.Arrays.toString(arr) + " exp=" + exp + " got=" + s.maxProductDC(arr.clone()));
            }
        }
        System.out.println("MaxProductSubarray linear OK; DC fails=" + dcFails);
    }
}
