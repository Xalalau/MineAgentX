package com.mineagent.engine.identity;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.*;

/** Additive per-companion file; legacy memory sets with no identity remain uncommitted. */
public final class IdentityPersistence {
    private static final Gson GSON = new Gson();
    public static final String FILE_NAME = "identity.json";
    private record Envelope(int version, EvolvingIdentity.State identity, IdentityReflection.State reflection) {}
    private IdentityPersistence() {}

    public static void save(Path directory, EvolvingIdentity identity, IdentityReflection reflection) throws IOException {
        Files.createDirectories(directory);
        Path target = directory.resolve(FILE_NAME);
        Path temporary = Files.createTempFile(directory, "identity-", ".tmp");
        try {
            Envelope snapshot;
            // Same lock order as reflection revision; never checkpoint an evidence cursor
            // newer than the evidence snapshot while a server callback records an event.
            synchronized (reflection) {
                snapshot = new Envelope(1, identity.exportState(), reflection.exportState());
            }
            Files.writeString(temporary, GSON.toJson(snapshot));
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void load(Path directory, EvolvingIdentity identity, IdentityReflection reflection) throws IOException {
        Path file = directory.resolve(FILE_NAME);
        if (!Files.exists(file)) return;
        try {
            if (Files.size(file) > 1_048_576) throw new IOException("identity_file_too_large");
            Envelope envelope = GSON.fromJson(Files.readString(file), Envelope.class);
            if (envelope == null || envelope.version() != 1 || envelope.identity() == null
                    || envelope.reflection() == null || envelope.reflection().reviewedSequence() < 0
                    || envelope.reflection().reviewedSequence() > envelope.identity().sequence()
                    || envelope.reflection().lastOpportunityTick() < -1) {
                throw new IOException("invalid_identity_envelope");
            }
            identity.importState(envelope.identity());
            reflection.importState(envelope.reflection());
        } catch (RuntimeException malformed) {
            throw new IOException("invalid_identity_state", malformed);
        }
    }
}
