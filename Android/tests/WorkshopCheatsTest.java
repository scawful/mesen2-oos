package ca.mesen.oos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class WorkshopCheatsTest {
    public static void main(String[] args) throws Exception {
        List<WorkshopCheats.Action> all = WorkshopCheats.catalog();
        check(!all.isEmpty(), "catalog empty");
        Set<String> groups = new HashSet<>(WorkshopCheats.groups());
        check(groups.contains(WorkshopCheats.GROUP_QUICK), "missing Quick");
        check(groups.contains(WorkshopCheats.GROUP_ITEMS), "missing Items");
        check(groups.contains(WorkshopCheats.GROUP_GEAR), "missing Gear");
        check(groups.contains(WorkshopCheats.GROUP_STORY), "missing Story");
        for (WorkshopCheats.Action action : all) {
            check(groups.contains(action.group), "unknown group " + action.group);
            check(action.label != null && !action.label.isEmpty(), "blank label");
            if (!"Max Y-items pack".equals(action.label)
                    && !"All masks".equals(action.label)
                    && !"Full heal".equals(action.label)
                    && !"20 hearts (max+fill)".equals(action.label)) {
                check((action.addr & 0xFF0000) == 0x7E0000 || (action.addr & 0xFF0000) == 0x7F0000,
                        "non-WRAM addr for " + action.label);
            }
        }
        check(!WorkshopCheats.forGroup(WorkshopCheats.GROUP_QUICK).isEmpty(), "quick empty");
        System.out.println("WorkshopCheatsTest OK (" + all.size() + " actions)");
    }

    private static void check(boolean ok, String message) throws Exception {
        if (!ok) {
            throw new Exception(message);
        }
    }
}
