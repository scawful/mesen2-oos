package ca.mesen.oos;

final class MenuNavigationTest {
    public static void main(String[] args) {
        for (int size : new int[]{1, 5, 10, 30}) {
            check(MenuNavigation.next(0, size, true) == 0, "up stops at first");
            check(MenuNavigation.next(size-1, size, false) == size-1, "down stops at last");
            check(MenuNavigation.next(-1, size, false) == 0, "missing focus starts at first");
            for (int i=0; i<size-1; i++) {
                check(MenuNavigation.next(i, size, false) == i+1, "down reaches next");
                check(MenuNavigation.next(i+1, size, true) == i, "up reverses down");
            }
        }
        check(MenuNavigation.next(0, 0, false) == -1, "empty menu has no selection");
        boolean[] dialog = {true, false, true}; // Save here, Load (empty slot), Cancel
        check(MenuNavigation.sideways(dialog, 2, true) == 0, "left from Cancel skips disabled Load");
        check(MenuNavigation.sideways(dialog, 0, true) == -1, "left from first button leaves the row");
        check(MenuNavigation.sideways(dialog, 0, false) == 2, "right from Save here skips Load");
        check(MenuNavigation.sideways(dialog, 2, false) == -1, "right from last button stops");
        check(MenuNavigation.sideways(new boolean[]{true, true, true}, 2, true) == 1, "enabled Load is reachable");
        check(MenuNavigation.sideways(dialog, -1, true) == -1, "missing focus has no neighbour");
        int[] cheats = {0, 0, 0, 0, -1, -1, -1}; // 4 group tabs, then 3 rows
        check(MenuNavigation.nextAcrossRows(cheats, 1, false) == 4, "down from a tab reaches first row");
        check(MenuNavigation.nextAcrossRows(cheats, 4, true) == 3, "up from first row lands on the tab row");
        check(MenuNavigation.nextAcrossRows(cheats, 2, true) == 2, "up from tab row stops (top)");
        check(MenuNavigation.nextAcrossRows(cheats, 5, false) == 6, "plain rows still step by one");
        int[] dialog2 = {0, 0, 0}; // slot dialog: one row only
        check(MenuNavigation.nextAcrossRows(dialog2, 2, true) == 2, "dialog row: vertical stays put");
        check(MenuNavigation.nextAcrossRows(cheats, -1, false) == 0, "missing focus starts at first");
        System.out.println("MenuNavigationTest OK");
    }
    static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
