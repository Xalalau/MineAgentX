package com.mineagent.engine.planning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Short-lived reservations for scarce inventory, stations and work areas.
 *
 * <p>Observed ownership answers "does it exist?"; a reservation answers
 * "is another action already relying on it?".  Reservations never create
 * items and expire automatically, so a crashed plan cannot permanently lock a
 * companion.  The ledger is intentionally Minecraft-free and can be shared by
 * tools, planning and team coordination.</p>
 */
public final class ResourceReservationLedger {
    public record Reservation(String id, String resource, String purpose,
                              long ownerTick, long expiresTick, boolean exclusive) {
        public Reservation {
            id = normalize(id, "reservation");
            resource = normalize(resource, "resource");
            purpose = normalize(purpose, "planned use");
            ownerTick = Math.max(0L, ownerTick);
            expiresTick = Math.max(ownerTick, expiresTick);
        }
        public boolean active(long tick) { return tick <= expiresTick; }
    }
    public record State(List<Reservation> reservations, long revision) {}

    private static final int MAX = 256;
    private final LinkedHashMap<String, Reservation> reservations = new LinkedHashMap<>();
    private long revision;

    public synchronized boolean reserve(String id, String resource, String purpose,
                                         long tick, long ttlTicks, boolean exclusive) {
        expire(tick);
        String reservationId = normalize(id, "reservation-" + revision);
        String normalized = normalize(resource, "resource").toLowerCase(Locale.ROOT);
        Reservation existingById = reservations.get(reservationId);
        if (existingById != null) {
            // A retried tool call with the same id is idempotent only when it
            // asks for the same resource.  Silently replacing an existing id
            // with a different resource leaves the old claim in an ambiguous
            // state and can make two physical actions believe they own one
            // station.
            if (!existingById.resource().equalsIgnoreCase(normalized)) return false;
            return true;
        }
        if (reservations.values().stream().anyMatch(value ->
                value.resource().equalsIgnoreCase(normalized)
                        && !value.id().equals(reservationId)
                        && (exclusive || value.exclusive()))) return false;
        reservations.put(reservationId, new Reservation(reservationId, normalized, purpose, tick,
                saturatedAdd(tick, Math.max(20L, ttlTicks)), exclusive));
        revision++;
        trim();
        return true;
    }

    public synchronized void release(String id) {
        if (id != null && reservations.remove(id) != null) revision++;
    }

    public synchronized List<Reservation> expire(long tick) {
        List<Reservation> expired = new ArrayList<>();
        for (Reservation value : List.copyOf(reservations.values())) {
            if (!value.active(tick)) { reservations.remove(value.id()); expired.add(value); }
        }
        if (!expired.isEmpty()) revision++;
        return expired;
    }

    public synchronized boolean isReserved(String resource, long tick) {
        expire(tick);
        return reservations.values().stream().anyMatch(value ->
                value.resource().equalsIgnoreCase(normalize(resource, "resource")));
    }

    public synchronized State exportState() { return new State(List.copyOf(reservations.values()), revision); }
    public synchronized void importState(State state) {
        // Reservations describe live ownership of a body slot, station, or
        // work area. That ownership cannot survive process restart. Keeping
        // the old expiry tick could block a new plan in a restored world.
        reservations.clear();
        revision = state == null ? 0L : Math.max(0L, state.revision()) + 1L;
    }

    public synchronized String summarizeForPrompt(long tick) {
        expire(tick);
        if (reservations.isEmpty()) return "Reservations: none\n";
        StringBuilder out = new StringBuilder("Reservations (temporary claims):\n");
        reservations.values().stream().sorted(Comparator.comparing(Reservation::resource))
                .limit(12).forEach(value -> out.append("- ").append(value.resource())
                        .append(" for ").append(value.purpose()).append(" until ")
                        .append(value.expiresTick()).append('\n'));
        return out.toString();
    }

    private void trim() {
        while (reservations.size() > MAX) {
            String id = reservations.values().stream()
                    .min(Comparator.comparingLong(Reservation::expiresTick))
                    .map(Reservation::id).orElse(null);
            if (id == null) break;
            reservations.remove(id);
        }
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
    private static long saturatedAdd(long first, long second) {
        return second > 0L && first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }
}
