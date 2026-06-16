public class SearchRotatedII {

    public boolean search(int[] nums, int target) {
        int lo = 0, hi = nums.length - 1;
        while (lo <= hi) {
            int mid = lo + (hi - lo) / 2;
            if (nums[mid] == target) return true;
            if (nums[lo] == nums[mid] && nums[mid] == nums[hi]) {
                lo++; hi--;
            } else if (nums[lo] <= nums[mid]) {
                if (nums[lo] <= target && target < nums[mid]) hi = mid - 1;
                else lo = mid + 1;
            } else {
                if (nums[mid] < target && target <= nums[hi]) lo = mid + 1;
                else hi = mid - 1;
            }
        }
        return false;
    }

    public static void main(String[] args) {
        SearchRotatedII s = new SearchRotatedII();
        assert s.search(new int[]{2,5,6,0,0,1,2}, 0);
        assert !s.search(new int[]{2,5,6,0,0,1,2}, 3);
        assert s.search(new int[]{1,0,1,1,1}, 0);
        // brute-force fuzz over rotated duplicate arrays
        java.util.Random r = new java.util.Random(17);
        for (int t = 0; t < 5000; t++) {
            int n = 1 + r.nextInt(10);
            int[] arr = new int[n];
            for (int i = 0; i < n; i++) arr[i] = r.nextInt(5);
            java.util.Arrays.sort(arr);
            int rot = r.nextInt(n);
            int[] rotated = new int[n];
            for (int i = 0; i < n; i++) rotated[i] = arr[(i + rot) % n];
            int target = r.nextInt(6);
            boolean expected = false;
            for (int x : rotated) if (x == target) { expected = true; break; }
            if (s.search(rotated, target) != expected) throw new AssertionError("mismatch");
        }
        System.out.println("SearchRotatedII OK");
    }
}
