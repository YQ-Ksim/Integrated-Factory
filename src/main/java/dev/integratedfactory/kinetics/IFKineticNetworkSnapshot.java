package dev.integratedfactory.kinetics;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import java.lang.reflect.Field;

/** Reads Create 6.0.10's current network totals without trusting reload caches. */
public final class IFKineticNetworkSnapshot {
    private static final Field UNLOADED_STRESS = field("unloadedStress");
    private static final Field UNLOADED_CAPACITY = field("unloadedCapacity");
    private static final Field UNLOADED_MEMBERS = field("unloadedMembers");
    public static float stress(KineticNetwork network) {
        // The cached fields can contain Create's persisted unloaded totals while
        // a world is being restored. Recalculate from the current members so a
        // stale snapshot cannot become a phantom load in the bridge.
        return finite(network.calculateStress());
    }

    public static float capacity(KineticNetwork network) {
        return finite(network.calculateCapacity());
    }

    /** Stress from live block entities only; excludes Create's unloaded snapshot. */
    public static float loadedStress(KineticNetwork network) {
        return loadedStress(network, null);
    }

    /**
     * Stress from live block entities only, optionally excluding one member.
     * The exclusion is used for the host core: its mapped remote load is the
     * load being budgeted, not an unrelated consumer that should reduce the
     * capacity offered to the remote room.
     */
    public static float loadedStress(KineticNetwork network, KineticBlockEntity excluded) {
        float total = 0;
        for (var member : network.members.keySet()) {
            if (member == excluded) continue;
            if (member.getLevel() == null || member.getLevel().getBlockEntity(member.getBlockPos()) != member) continue;
            total += network.getActualStressOf(member);
        }
        return finite(total);
    }

    /** Capacity from live kinetic sources only; excludes Create's unloaded snapshot. */
    public static float loadedCapacity(KineticNetwork network) {
        float total = 0;
        for (var source : network.sources.keySet()) {
            if (source.getLevel() == null || source.getLevel().getBlockEntity(source.getBlockPos()) != source) continue;
            total += network.getActualCapacityOf(source);
        }
        return finite(total);
    }

    /** Number of Create members which have not been reattached after loading. */
    public static int unloadedMembers(KineticNetwork network) {
        if (UNLOADED_MEMBERS == null) return 0;
        try {
            return Math.max(0, UNLOADED_MEMBERS.getInt(network));
        } catch (IllegalAccessException ignored) {
            return 0;
        }
    }

    private static float finite(float value) { return Float.isFinite(value) ? Math.max(0, value) : 0; }

    /**
     * A machine room is confined to one loaded chunk. Once its bridge block is
     * ticking, old unloaded totals cannot refer to a valid member outside that
     * room, so discard them before the first post-load network calculation.
     */
    public static void clearUnloadedSnapshot(KineticNetwork network) {
        write(UNLOADED_STRESS, network, 0f);
        write(UNLOADED_CAPACITY, network, 0f);
        write(UNLOADED_MEMBERS, network, 0);
    }

    /** Removes only a known stale member contribution from Create's unloaded total. */
    public static void subtractUnloadedStress(KineticNetwork network, float amount) {
        if (amount <= 0 || UNLOADED_STRESS == null) return;
        try {
            float current = UNLOADED_STRESS.getFloat(network);
            UNLOADED_STRESS.setFloat(network, Math.max(0, current - amount));
        } catch (IllegalAccessException ignored) {
            // The normal member update still runs if reflection is restricted.
        }
    }

    private static Field field(String name) {
        try {
            Field field = KineticNetwork.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static void write(Field field, KineticNetwork network, Object value) {
        if (field == null) return;
        try {
            if (value instanceof Float number) field.setFloat(network, number);
            else if (value instanceof Integer number) field.setInt(network, number);
        } catch (IllegalAccessException ignored) {
            // The public calculation path remains available if reflection is restricted.
        }
    }

    private IFKineticNetworkSnapshot() {}
}
