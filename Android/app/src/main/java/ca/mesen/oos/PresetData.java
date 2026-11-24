package ca.mesen.oos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Playtest file presets loaded from assets/oracle/presets.json. Includes the
 * FULL in-place preset (applies directly to WRAM + SRAM without warping) and
 * named milestone presets in game order (e.g. POST_INTRO, ZORA, GORON), each
 * layering its milestone writes over its base_checkpoint chain.
 */
public final class PresetData {
    static final String PRESETS_ASSET = "oracle/presets.json";

    static final class Preset {
        final String id;
        final String name;
        final String label;
        final int beat;
        final boolean inPlace;
        final boolean proposed;
        final String baseCheckpoint;
        /** Packed triples: addr, op (0 value, 1 set_bits, 2 clear_bits), operand. */
        final int[] writes;
        /** Non-null for milestone warp presets; null for in-place presets like FULL. */
        final Progression.Checkpoint checkpoint;

        Preset(String id, String name, String label, int beat, boolean inPlace, boolean proposed,
               String baseCheckpoint, int[] writes, Progression.Checkpoint checkpoint) {
            this.id = id;
            this.name = name;
            this.label = label;
            this.beat = beat;
            this.inPlace = inPlace;
            this.proposed = proposed;
            this.baseCheckpoint = baseCheckpoint;
            this.writes = writes;
            this.checkpoint = checkpoint;
        }

        String displayTag() {
            String mode = inPlace
                    ? "in-place"
                    : (checkpoint != null && checkpoint.kind == Progression.Kind.AREA ? "area" : "door");
            return proposed ? "PROPOSED · " + mode : mode;
        }
    }

    final List<Preset> presets;
    final String error;

    private PresetData(List<Preset> presets, String error) {
        this.presets = Collections.unmodifiableList(presets);
        this.error = error;
    }

    static PresetData empty(String error) {
        return new PresetData(new ArrayList<>(), error);
    }

    Preset find(String id) {
        for (Preset p : presets) {
            if (p.id.equals(id)) return p;
        }
        return null;
    }

    /** Never throws: malformed JSON yields an empty set with {@link #error} filled in. */
    static PresetData parse(String presetsJson, CheckpointData checkpoints, String warpsJson) {
        if (presetsJson == null) {
            return empty("presets.json is not bundled");
        }
        try {
            Map<String, Object> root = MiniJson.object(MiniJson.parse(presetsJson));
            Map<String, Object> warps = warpsJson == null ? null : MiniJson.object(MiniJson.parse(warpsJson));
            return build(root, checkpoints, warps);
        } catch (RuntimeException e) {
            return empty(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    static String applyInPlace(Preset preset, Progression.Host host) {
        if (preset == null || !preset.inPlace) return "Preset is not in-place";
        return Progression.applyWrites(preset.name, preset.writes, host);
    }

    private static PresetData build(Map<String, Object> root, CheckpointData checkpoints, Map<String, Object> warps) {
        List<Object> entrances = warps == null ? new ArrayList<>() : MiniJson.array(warps.get("entrances"));
        Map<Integer, Map<String, Object>> areas = warps == null
                ? new java.util.LinkedHashMap<>()
                : CheckpointData.indexAreas(warps);

        List<Preset> list = new ArrayList<>();
        for (Object item : MiniJson.array(root.get("presets"))) {
            Map<String, Object> obj = MiniJson.object(item);
            String id = MiniJson.string(obj.get("id"), "");
            String name = MiniJson.string(obj.get("name"), id);
            String label = MiniJson.string(obj.get("label"), name);
            Object beatObj = obj.get("beat");
            int beat = beatObj instanceof Number ? ((Number) beatObj).intValue() : 0;
            boolean inPlace = Boolean.TRUE.equals(obj.get("in_place"));
            boolean proposed = Boolean.TRUE.equals(obj.get("proposed"));
            String baseId = MiniJson.string(obj.get("base_checkpoint"), "");

            Progression.Checkpoint base = null;
            if (!baseId.isEmpty()) {
                base = checkpoints == null ? null : checkpoints.find(baseId);
                if (base == null) {
                    throw new IllegalArgumentException(id + " references unknown base_checkpoint " + baseId);
                }
            }

            List<Integer> writes = new ArrayList<>();
            if (base != null) {
                for (int w : base.writes) writes.add(w);
            }
            for (Object w : MiniJson.array(obj.get("writes"))) {
                int[] op = CheckpointData.writeOp(MiniJson.object(w));
                writes.add(op[0]);
                writes.add(op[1]);
                writes.add(op[2]);
            }
            int[] packed = new int[writes.size()];
            for (int i = 0; i < packed.length; i++) packed[i] = writes.get(i);

            Progression.Checkpoint cp = null;
            if (!inPlace) {
                cp = buildWarpCheckpoint(id, label, beat, obj, base, entrances, areas, packed);
            }
            list.add(new Preset(id, name, label, beat, inPlace, proposed, baseId, packed, cp));
        }
        return new PresetData(list, null);
    }

    private static Progression.Checkpoint buildWarpCheckpoint(
            String id, String label, int beat, Map<String, Object> obj,
            Progression.Checkpoint base, List<Object> entrances,
            Map<Integer, Map<String, Object>> areas, int[] packed) {
        Object locObj = obj.get("location");
        int[] expectModules = base != null ? base.expectModules : new int[0];
        int expectRoom = base != null ? base.expectRoom : -1;
        int expectArea = base != null ? base.expectArea : -1;

        if (locObj instanceof Map && !areas.isEmpty()) {
            Map<String, Object> loc = MiniJson.object(locObj);
            if (!CheckpointData.hasLocation(loc, areas)) {
                throw new IllegalArgumentException(id + " references unknown warp location");
            }
            CheckpointData.LocationPlan plan = CheckpointData.resolveLocation(loc, entrances, areas);
            return new Progression.Checkpoint(
                    id, label, beat, plan.kind, plan.target, plan.cacheExit, plan.area,
                    plan.viaRoom, plan.primeEntrance, plan.room,
                    expectModules, expectRoom, expectArea, new String[]{id}, packed);
        }
        if (base == null) {
            throw new IllegalArgumentException(id + " has neither location nor base_checkpoint");
        }
        return new Progression.Checkpoint(
                id, label, beat, base.kind, base.target, base.cacheExit, base.area,
                base.viaRoom, base.primeEntrance, base.room,
                expectModules, expectRoom, expectArea, new String[]{id}, packed);
    }
}
