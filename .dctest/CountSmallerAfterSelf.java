import java.util.*;

public class CountSmallerAfterSelf {

    public List<Integer> countSmaller(int[] nums) {
        int n = nums.length;
        int[] counts = new int[n];
        int[] idx = new int[n], aux = new int[n];
        for (int i = 0; i < n; i++) idx[i] = i;
        sort(nums, idx, aux, counts, 0, n - 1);
        List<Integer> res = new ArrayList<>(n);
        for (int c : counts) res.add(c);
        return res;
    }

    private void sort(int[] nums, int[] idx, int[] aux, int[] counts, int lo, int hi) {
        if (lo >= hi) return;
        int mid = lo + (hi - lo) / 2;
        sort(nums, idx, aux, counts, lo, mid);
        sort(nums, idx, aux, counts, mid + 1, hi);

        int i = lo, j = mid + 1, k = lo, rightCount = 0;
        while (i <= mid && j <= hi) {
            if (nums[idx[j]] < nums[idx[i]]) {
                rightCount++;
                aux[k++] = idx[j++];
            } else {
                counts[idx[i]] += rightCount;
                aux[k++] = idx[i++];
            }
        }
        while (i <= mid) { counts[idx[i]] += rightCount; aux[k++] = idx[i++]; }
        while (j <= hi)  aux[k++] = idx[j++];
        System.arraycopy(aux, lo, idx, lo, hi - lo + 1);
    }

    private static List<Integer> brute(int[] a) {
        List<Integer> res = new ArrayList<>();
        for (int i = 0; i < a.length; i++) {
            int c = 0;
            for (int j = i + 1; j < a.length; j++) if (a[j] < a[i]) c++;
            res.add(c);
        }
        return res;
    }

    public static void main(String[] args) {
        CountSmallerAfterSelf s = new CountSmallerAfterSelf();
        assert s.countSmaller(new int[]{5,2,6,1}).equals(Arrays.asList(2,1,1,0));
        Random r = new Random(11);
        for (int t = 0; t < 2000; t++) {
            int n = 1 + r.nextInt(12);
            int[] arr = new int[n];
            for (int i = 0; i < n; i++) arr[i] = r.nextInt(11) - 5;
            int[] copy = arr.clone();
            if (!s.countSmaller(arr).equals(brute(copy))) throw new AssertionError("mismatch " + Arrays.toString(copy));
        }
        System.out.println("CountSmallerAfterSelf OK");
    }
}
