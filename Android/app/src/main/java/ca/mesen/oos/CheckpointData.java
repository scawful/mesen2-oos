package ca.mesen.oos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Story checkpoints read at runtime from Oracle's Data/debug/checkpoints.json
 * and warps.json, bundled as APK assets by the :app:syncOracleData Gradle task.
 * Same files and same rules as the desktop oracle_cheats panel: each
 * checkpoint's 'after' chain resolves oldest first and its location resolves
 * to a warp plan here. Also collects the byte names the writes carry, which
 * label the Items and progress screen.
 */
public final class CheckpointData {
    static final String CHECKPOINTS_ASSET = "oracle/checkpoints.json";
    static final String WARPS_ASSET = "oracle/warps.json";

    public final List<Progression.Checkpoint> checkpoints;
    public final List<Progression.Checkpoint> entranceWarps;
    public final List<Progression.Checkpoint> areaWarps;
    /** WRAM address -> name from the checkpoint writes ("GameState", "OOSPROG", ...). */
    final Map<Integer, String> names;
    final List<String> skipped;
    final String romTested;
    final String error;

    private CheckpointData(List<Progression.Checkpoint> checkpoints,
                           List<Progression.Checkpoint> entranceWarps,
                           List<Progression.Checkpoint> areaWarps,
                           Map<Integer, String> names,
                           List<String> skipped, String romTested, String error) {
        this.checkpoints = Collections.unmodifiableList(checkpoints);
        this.entranceWarps = Collections.unmodifiableList(entranceWarps);
        this.areaWarps = Collections.unmodifiableList(areaWarps);
        this.names = Collections.unmodifiableMap(names);
        this.skipped = Collections.unmodifiableList(skipped);
        this.romTested = romTested;
        this.error = error;
    }

    static CheckpointData empty(String error) {
        return new CheckpointData(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                new LinkedHashMap<>(), new ArrayList<>(), "", error);
    }

    Progression.Checkpoint find(String id) {
        for (Progression.Checkpoint cp : checkpoints) {
            if (cp.id.equals(id)) return cp;
        }
        return null;
    }

    /** Never throws: a bad file yields an empty set with {@link #error} filled in. */
    static CheckpointData parse(String checkpointsJson, String warpsJson) {
        if (checkpointsJson == null || warpsJson == null) {
            return empty("checkpoints.json or warps.json is not bundled");
        }
        try {
            return build(MiniJson.object(MiniJson.parse(checkpointsJson)),
                    MiniJson.object(MiniJson.parse(warpsJson)));
        } catch (RuntimeException e) {
            return empty(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    static int hex(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        String s = String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT).replace("$", "0x");
        return Integer.parseInt(s.startsWith("0x") ? s.substring(2) : s, 16);
    }

    static final class LocationPlan {
        final Progression.Kind kind;
        final int target;
        final boolean cacheExit;
        final int area;
        final int viaRoom;
        final int primeEntrance;
        final int room;

        LocationPlan(Progression.Kind kind, int target, boolean cacheExit,
                     int area, int viaRoom, int primeEntrance, int room) {
            this.kind = kind;
            this.target = target;
            this.cacheExit = cacheExit;
            this.area = area;
            this.viaRoom = viaRoom;
            this.primeEntrance = primeEntrance;
            this.room = room;
        }
    }

    static boolean present(Object value) {
        return value != null && !String.valueOf(value).isEmpty();
    }

    static Map<Integer, Map<String, Object>> indexAreas(Map<String, Object> warps) {
        Map<Integer, Map<String, Object>> areas = new LinkedHashMap<>();
        for (Object a : MiniJson.array(warps.get("areas"))) {
            Map<String, Object> area = MiniJson.object(a);
            areas.put(hex(area.get("area")), area);
        }
        return areas;
    }

    static boolean hasLocation(Map<String, Object> loc, Map<Integer, Map<String, Object>> areas) {
        return loc.containsKey("entrance") || areas.containsKey(hex(loc.get("area")));
    }

    static LocationPlan resolveLocation(Map<String, Object> loc, List<Object> entrances,
                                        Map<Integer, Map<String, Object>> areas) {
        if (loc.containsKey("entrance")) {
            int eid = hex(loc.get("entrance"));
            Map<String, Object> e = entrance(entrances, eid);
            boolean cache = "cache".equals(e.get("exit"));
            Map<String, Object> ca = cache ? cacheArea(e, areas) : null;
            int area = ca != null
                    ? hex(ca.get("area"))
                    : (present(e.get("area")) ? hex(e.get("area")) : -1);
            int via = ca != null ? hex(ca.get("via_room")) : -1;
            int room = present(e.get("room")) ? hex(e.get("room")) : -1;
            return new LocationPlan(Progression.Kind.ENTRANCE, eid, cache, area, via, -1, room);
        }
        Map<String, Object> a = areas.get(hex(loc.get("area")));
        int target = hex(a.get("area"));
        return new LocationPlan(Progression.Kind.AREA, target, false, target,
                hex(a.get("via_room")), prime(entrances, a), -1);
    }

    private static CheckpointData build(Map<String, Object> cps, Map<String, Object> warps) {
        List<Object> entrances = MiniJson.array(warps.get("entrances"));
        Map<Integer, Map<String, Object>> areas = indexAreas(warps);
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Object c : MiniJson.array(cps.get("checkpoints"))) {
            Map<String, Object> cp = MiniJson.object(c);
            byId.put(MiniJson.string(cp.get("id"), ""), cp);
        }

        List<Progression.Checkpoint> rows = new ArrayList<>();
        Map<Integer, String> names = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        for (Map<String, Object> c : byId.values()) {
            String id = MiniJson.string(c.get("id"), "");
            for (Object w : MiniJson.array(c.get("writes"))) {
                Map<String, Object> write = MiniJson.object(w);
                if (present(write.get("name"))) {
                    int addr = hex(write.get("addr"));
                    if (!names.containsKey(addr)) names.put(addr, MiniJson.string(write.get("name"), ""));
                }
            }
            Map<String, Object> loc = MiniJson.object(c.get("location"));
            if (!hasLocation(loc, areas)) {
                // warps.json has no landing for this area; later checkpoints
                // still apply its writes through their 'after' chain.
                skipped.add(id);
                continue;
            }
            List<Map<String, Object>> chain = resolve(byId, id);
            List<Integer> writes = new ArrayList<>();
            String[] chainIds = new String[chain.size()];
            for (int i = 0; i < chain.size(); i++) {
                Map<String, Object> link = chain.get(i);
                chainIds[i] = MiniJson.string(link.get("id"), "");
                for (Object w : MiniJson.array(link.get("writes"))) {
                    int[] op = writeOp(MiniJson.object(w));
                    writes.add(op[0]);
                    writes.add(op[1]);
                    writes.add(op[2]);
                }
            }
            Map<String, Object> expect = MiniJson.object(c.get("expect"));
            List<Object> modList = MiniJson.array(expect.get("modules"));
            int[] mods = new int[modList.size()];
            for (int i = 0; i < mods.length; i++) mods[i] = hex(modList.get(i));
            int expRoom = expect.containsKey("room") ? hex(expect.get("room")) : -1;
            int expArea = expect.containsKey("area") ? hex(expect.get("area")) : -1;

            LocationPlan plan = resolveLocation(loc, entrances, areas);
            int[] packed = new int[writes.size()];
            for (int i = 0; i < packed.length; i++) packed[i] = writes.get(i);
            Object beat = c.get("beat");
            rows.add(new Progression.Checkpoint(
                    id, MiniJson.string(c.get("label"), id),
                    beat instanceof Number ? ((Number) beat).intValue() : 0,
                    plan.kind, plan.target, plan.cacheExit, plan.area, plan.viaRoom, plan.primeEntrance, plan.room,
                    mods, expRoom, expArea, chainIds, packed));
        }
        List<Progression.Checkpoint> entranceWarps = new ArrayList<>();
        for (Object obj : entrances) {
            Map<String, Object> e = MiniJson.object(obj);
            if (!present(e.get("id"))) continue;
            int eid = hex(e.get("id"));
            String name = MiniJson.string(e.get("label"), MiniJson.string(e.get("name"), ""));
            boolean cache = "cache".equals(e.get("exit"));
            Map<String, Object> ca = cache ? cacheArea(e, areas) : null;
            int area = ca != null
                    ? hex(ca.get("area"))
                    : (present(e.get("area")) ? hex(e.get("area")) : -1);
            int via = ca != null ? hex(ca.get("via_room")) : -1;
            int room = present(e.get("room")) ? hex(e.get("room")) : -1;
            String label = room >= 0
                    ? String.format(java.util.Locale.ROOT, "$%02X · %s ($%04X)", eid, name, room)
                    : String.format(java.util.Locale.ROOT, "$%02X · %s", eid, name);
            entranceWarps.add(new Progression.Checkpoint(
                    String.format(java.util.Locale.ROOT, "entrance:%02x", eid),
                    label, -1, Progression.Kind.ENTRANCE, eid, cache, area, via, -1, room,
                    new int[]{Progression.MOD_DUNGEON, Progression.MOD_OVERWORLD, Progression.MOD_SPECIAL},
                    room, -1, new String[0], new int[0]));
        }

        List<Progression.Checkpoint> areaWarps = new ArrayList<>();
        for (Map<String, Object> a : areas.values()) {
            int aid = hex(a.get("area"));
            String name = MiniJson.string(a.get("name"), String.format(java.util.Locale.ROOT, "Area $%02X", aid));
            String world = MiniJson.string(a.get("world"), "");
            int via = hex(a.get("via_room"));
            int prime = prime(entrances, a);
            String label = world.isEmpty()
                    ? String.format(java.util.Locale.ROOT, "$%02X · %s", aid, name)
                    : String.format(java.util.Locale.ROOT, "$%02X · %s (%s)", aid, name, world);
            areaWarps.add(new Progression.Checkpoint(
                    String.format(java.util.Locale.ROOT, "area:%02x", aid),
                    label, -1, Progression.Kind.AREA, aid, false, aid, via, prime, -1,
                    new int[]{Progression.MOD_OVERWORLD, Progression.MOD_SPECIAL},
                    -1, aid, new String[0], new int[0]));
        }

        Object tested = cps.get("rom_tested");
        String romTested = tested instanceof Map
                ? new java.io.File(MiniJson.string(MiniJson.object(tested).get("path"), "?")).getName()
                : MiniJson.string(tested, "?");
        return new CheckpointData(rows, entranceWarps, areaWarps, names, skipped, romTested, null);
    }

    private static List<Map<String, Object>> resolve(Map<String, Map<String, Object>> byId, String id) {
        List<Map<String, Object>> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, Object> cur = byId.get(id);
        while (cur != null) {
            String curId = MiniJson.string(cur.get("id"), "");
            if (!seen.add(curId)) throw new IllegalArgumentException("checkpoint chain loops at " + curId);
            chain.add(cur);
            Object parent = cur.get("after");
            cur = present(parent) ? byId.get(String.valueOf(parent)) : null;
            if (present(parent) && cur == null) {
                throw new IllegalArgumentException(curId + " is after unknown " + parent);
            }
        }
        Collections.reverse(chain);
        return chain;
    }

    /** {addr, op, operand}: op 0 = value, 1 = set_bits, 2 = clear_bits. */
    static int[] writeOp(Map<String, Object> w) {
        int addr = hex(w.get("addr"));
        // Most writes are in the $7EF000 save block; a few reset live WRAM.
        if (addr < 0x7E0000 || addr > 0x7FFFFF) {
            throw new IllegalArgumentException("write " + w.get("addr") + " is outside WRAM");
        }
        String[] keys = {"value", "set_bits", "clear_bits"};
        for (int op = 0; op < keys.length; op++) {
            if (w.containsKey(keys[op])) return new int[]{addr, op, hex(w.get(keys[op])) & 0xFF};
        }
        throw new IllegalArgumentException("write " + w.get("addr") + " has no value/set_bits/clear_bits");
    }

    private static Map<String, Object> entrance(List<Object> entrances, int eid) {
        for (Object o : entrances) {
            Map<String, Object> e = MiniJson.object(o);
            if (hex(e.get("id")) == eid) return e;
        }
        return new LinkedHashMap<>();
    }

    private static int prime(List<Object> entrances, Map<String, Object> area) {
        int via = hex(area.get("via_room"));
        for (Object o : entrances) {
            Map<String, Object> e = MiniJson.object(o);
            if (present(e.get("room")) && hex(e.get("room")) == via) return hex(e.get("id"));
        }
        int areaId = hex(area.get("area"));
        for (Object o : entrances) {
            Map<String, Object> e = MiniJson.object(o);
            if (present(e.get("area")) && hex(e.get("area")) == areaId && "table".equals(e.get("exit"))) {
                return hex(e.get("id"));
            }
        }
        return (areaId & 0x40) != 0 ? 0x76 : 0x01;
    }

    private static Map<String, Object> cacheArea(Map<String, Object> e, Map<Integer, Map<String, Object>> areas) {
        if (present(e.get("area")) && areas.containsKey(hex(e.get("area")))) {
            return areas.get(hex(e.get("area")));
        }
        boolean abyss = present(e.get("area")) && (hex(e.get("area")) & 0x40) != 0;
        Map<String, Object> fallback = areas.get(abyss ? 0x40 : 0x33);
        if (fallback == null) throw new IllegalArgumentException("warps.json has no cache fallback area");
        return fallback;
    }
}
