import java.util.Arrays;

public class KthSmallestPairDistance {

    public int smallestDistancePair(int[] nums, int k) {
        Arrays.sort(nums);
        int lo = 0, hi = nums[nums.length - 1] - nums[0];
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (countPairsAtMost(nums, mid) >= k) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    private int countPairsAtMost(int[] nums, int d) {
        int count = 0, left = 0;
        for (int right = 0; right < nums.length; right++) {
            while (nums[right] - nums[left] > d) left++;
            count += right - left;
        }
        return count;
    }

    private static int brute(int[] a, int k) {
        java.util.List<Integer> d = new java.util.ArrayList<>();
        for (int i = 0; i < a.length; i++)
            for (int j = i + 1; j < a.length; j++)
                d.add(Math.abs(a[i] - a[j]));
        java.util.Collections.sort(d);
        return d.get(k - 1);
    }

    public static void main(String[] args) {
        KthSmallestPairDistance s = new KthSmallestPairDistance();
        assert s.smallestDistancePair(new int[]{1,3,1}, 1) == 0;
        java.util.Random r = new java.util.Random(19);
        for (int t = 0; t < 2000; t++) {
            int n = 2 + r.nextInt(10);
            int[] arr = new int[n];
            for (int i = 0; i < n; i++) arr[i] = r.nextInt(20);
            int maxK = n * (n - 1) / 2;
            int k = 1 + r.nextInt(maxK);
            int[] copy = arr.clone();
            if (s.smallestDistancePair(arr, k) != brute(copy, k)) throw new AssertionError("mismatch");
        }
        System.out.println("KthSmallestPairDistance OK");
    }
}
