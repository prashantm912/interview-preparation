public class ReversePairs {

    public int reversePairs(int[] nums) {
        int[] aux = new int[nums.length];
        return (int) sort(nums, aux, 0, nums.length - 1);
    }

    private long sort(int[] a, int[] aux, int lo, int hi) {
        if (lo >= hi) return 0;
        int mid = lo + (hi - lo) / 2;
        long count = sort(a, aux, lo, mid) + sort(a, aux, mid + 1, hi);

        int j = mid + 1;
        for (int i = lo; i <= mid; i++) {
            while (j <= hi && (long) a[i] > 2L * a[j]) j++;
            count += (j - (mid + 1));
        }

        merge(a, aux, lo, mid, hi);
        return count;
    }

    private void merge(int[] a, int[] aux, int lo, int mid, int hi) {
        System.arraycopy(a, lo, aux, lo, hi - lo + 1);
        int i = lo, j = mid + 1;
        for (int k = lo; k <= hi; k++) {
            if (i > mid)               a[k] = aux[j++];
            else if (j > hi)           a[k] = aux[i++];
            else if (aux[i] <= aux[j]) a[k] = aux[i++];
            else                       a[k] = aux[j++];
        }
    }

    private static int brute(int[] a) {
        int c = 0;
        for (int i = 0; i < a.length; i++)
            for (int j = i + 1; j < a.length; j++)
                if ((long) a[i] > 2L * a[j]) c++;
        return c;
    }

    public static void main(String[] args) {
        ReversePairs s = new ReversePairs();
        assert s.reversePairs(new int[]{1,3,2,3,1}) == 2;
        assert s.reversePairs(new int[]{2,4,3,5,1}) == 3;
        java.util.Random r = new java.util.Random(7);
        for (int t = 0; t < 2000; t++) {
            int n = 1 + r.nextInt(12);
            int[] arr = new int[n];
            for (int i = 0; i < n; i++) arr[i] = r.nextInt(21) - 10;
            int[] copy = arr.clone();
            if (s.reversePairs(arr) != brute(copy)) throw new AssertionError("mismatch");
        }
        System.out.println("ReversePairs OK");
    }
}
