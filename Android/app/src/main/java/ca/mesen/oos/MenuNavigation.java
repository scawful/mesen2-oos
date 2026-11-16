package ca.mesen.oos;

/** Controller lists stop at their bounds; they never wrap into another screen. */
final class MenuNavigation {
    private MenuNavigation() {}

    static int next(int current, int count, boolean up) {
        if (count <= 0) return -1;
        if (current < 0) return 0;
        return Math.max(0, Math.min(count - 1, current + (up ? -1 : 1)));
    }

    /**
     * Vertical step that treats a horizontal button row as one stop:
     * rows[i] is a row id (views sharing a horizontal parent) or -1.
     * Stays put when only same-row entries remain in that direction.
     */
    static int nextAcrossRows(int[] rows, int current, boolean up) {
        int count = rows == null ? 0 : rows.length;
        int next = next(current, count, up);
        if (current < 0 || current >= count || rows[current] < 0) return next;
        int i = current;
        while (true) {
            int step = next(i, count, up);
            if (step == i) return current;
            if (rows[step] != rows[current]) return step;
            i = step;
        }
    }

    /**
     * Nearest usable neighbour in a horizontal button row, skipping disabled
     * or hidden entries. Returns -1 when there is none on that side.
     */
    static int sideways(boolean[] usable, int current, boolean left) {
        if (usable == null || current < 0 || current >= usable.length) return -1;
        for (int i = current + (left ? -1 : 1); i >= 0 && i < usable.length; i += left ? -1 : 1) {
            if (usable[i]) return i;
        }
        return -1;
    }
}
