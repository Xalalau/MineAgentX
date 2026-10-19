package com.mineagent.tools.construction;

import com.google.gson.*;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.construction.ConstructionDesign;
import com.mineagent.engine.construction.*;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import com.mineagent.api.i18n.RuntimeLanguage;

/** Progressive disclosure for model-authored designs; execution has a separate body-task contract. */
public final class ConstructionTool implements Tool {
    @FunctionalInterface public interface SessionFactory { ConstructionSession open(AgentPlayer player) throws IOException; }
    @FunctionalInterface public interface PreviewSink {
        /** Return true only when the six images were actually attached for optional visual review. */
        boolean attach(AgentPlayer player, ConstructionRepository.Revision revision, List<OrthographicRenderer.Preview> views);
    }
    private final SessionFactory sessions;
    private final PreviewSink previews;
    public ConstructionTool(SessionFactory sessions, PreviewSink previews) {
        this.sessions = Objects.requireNonNull(sessions);
        this.previews = previews == null ? (p, r, v) -> false : previews;
    }
    @Override public String name() { return "construction"; }
    @Override public String description() { return description(RuntimeLanguage.language().id()); }
    public String description(String language) { return ConstructionText.description(language); }
    @Override public Map<String, Object> parameterSchema() { return parameterSchema(RuntimeLanguage.language().id()); }
    public Map<String, Object> parameterSchema(String language) { return ConstructionSchema.designTool(language); }
    @Override public int defaultTimeoutSeconds() { return 10; }
    @Override public void onServerCall(String callId, JsonObject args, AgentPlayer player, Consumer<String> reply) {
        String locale = RuntimeLanguage.language().id();
        JsonObject result;
        try {
            if (args == null || args.toString().length() > 800000) throw new IllegalArgumentException("request_size");
            String mode = ConstructionCodec.string(args, "mode", null);
            if (mode.equals("help")) {
                result = new JsonObject(); result.addProperty("guidance", ConstructionText.help(locale));
            } else {
                ConstructionSession session = sessions.open(player);
                result = perform(mode, args, player, session, locale);
            }
            result.addProperty("success", true);
        } catch (IOException | RuntimeException failure) { result = error(locale, failure); }
        reply.accept(result.toString());
    }
    private JsonObject perform(String mode, JsonObject args, AgentPlayer player, ConstructionSession session, String locale) throws IOException {
        var repository = session.repository(); JsonObject result = new JsonObject();
        if (mode.equals("list")) {
            var entries = repository.list(ConstructionCodec.string(args, "after", ""),
                    ConstructionCodec.integer(args, "limit", 1, 20, 10));
            result.add("designs", ConstructionCodec.JSON.toJsonTree(entries));
            result.addProperty("next_after", entries.isEmpty() ? "" : entries.getLast().id()); return result;
        }
        if (mode.equals("revise")) {
            var design = ConstructionCodec.design(args.getAsJsonObject("design"));
            int expected = ConstructionCodec.integer(args, "expected_revision", 0, 128, -1);
            if (expected < 0) throw new IllegalArgumentException("expected_revision");
            var saved = repository.save(design, expected, ConstructionCodec.string(args, "reason", null));
            result.addProperty("id", design.id()); result.addProperty("revision", saved.version());
            result.addProperty("message", ConstructionText.stored(locale)); return result;
        }
        String id = ConstructionCodec.string(args, "id", null);
        int version = ConstructionCodec.integer(args, "revision", 0, 128, 0);
        var revision = repository.read(id, version); var design = revision.design();
        result.addProperty("id", id); result.addProperty("revision", revision.version());
        switch (mode) {
            case "read" -> {
                var metadata = ConstructionCodec.JSON.toJsonTree(design).getAsJsonObject(); metadata.remove("cells");
                result.add("design", metadata); result.addProperty("reason", revision.reason());
                result.add("total_materials", ConstructionCodec.JSON.toJsonTree(design.materials()));
                result.addProperty("latest_revision", repository.read(id, 0).version());
                int offset = ConstructionCodec.integer(args, "offset", 0, ConstructionDesign.MAX_CELLS, 0);
                int limit = ConstructionCodec.integer(args, "limit", 1, 128, 64);
                if (offset > design.cells().size()) throw new IllegalArgumentException("offset");
                int end = Math.min(design.cells().size(), offset + limit);
                result.add("cells", ConstructionCodec.JSON.toJsonTree(design.cells().subList(offset, end)));
                result.addProperty("cell_count", design.cells().size()); result.addProperty("next_offset", end);
            }
            case "evidence" -> {
                var events = repository.evidence(id, revision.version());
                int offset = ConstructionCodec.integer(args, "offset", 0, 256, 0);
                if (offset > events.size()) throw new IllegalArgumentException("offset");
                result.addProperty("event_count", events.size());
                if (offset < events.size()) result.add("event", ConstructionCodec.JSON.toJsonTree(events.get(offset)));
                result.addProperty("next_offset", Math.min(offset + 1, events.size()));
            }
            case "preview" -> {
                var views = new OrthographicRenderer().render(design);
                var metadata = new JsonArray();
                for (var view : views) {
                    var m = new JsonObject(); m.addProperty("view", view.view()); m.addProperty("mime_type", "image/png");
                    m.addProperty("width", view.width()); m.addProperty("height", view.height()); m.addProperty("bytes", view.png().length);
                    m.addProperty("horizontal_axis", view.horizontalAxis()); m.addProperty("vertical_axis", view.verticalAxis());
                    m.addProperty("view_direction", view.viewDirection()); metadata.add(m);
                }
                result.add("views", metadata);
                result.addProperty("vision_attached", previews.attach(player, revision, views));
                repository.appendEvidence(id, revision.version(), "preview", session.port() == null ? 0 : session.port().tick(), result);
            }
            case "assess" -> {
                var port = Objects.requireNonNull(session.port(), "world_port");
                JsonArray candidates = args.has("candidates") && !args.get("candidates").isJsonNull()
                        ? args.getAsJsonArray("candidates") : null;
                if (candidates != null && (candidates.isEmpty() || candidates.size() > 4)) throw new IllegalArgumentException("candidates");
                var assessments = new JsonArray();
                for (int n = 0; n < (candidates == null ? 1 : candidates.size()); n++) {
                    ConstructionDesign candidate = design;
                    if (candidates != null) {
                        JsonObject c = candidates.get(n).getAsJsonObject();
                        ConstructionCodec.require(c, "dimension", "origin", "quarterTurns");
                        JsonObject o = c.getAsJsonObject("origin"); ConstructionCodec.require(o, "x", "y", "z");
                        candidate = design.at(ConstructionCodec.string(c, "dimension", null),
                                new ConstructionDesign.Point(ConstructionCodec.integer(o, "x", -30000000, 30000000, 0),
                                        ConstructionCodec.integer(o, "y", -2048, 2048, 0), ConstructionCodec.integer(o, "z", -30000000, 30000000, 0)),
                                ConstructionCodec.integer(c, "quarterTurns", 0, 3, -1));
                    }
                    if (!port.dimension().equals(candidate.dimension())) {
                        var unavailable = new JsonObject(); unavailable.addProperty("state", "different_dimension");
                        unavailable.addProperty("dimension", candidate.dimension()); assessments.add(unavailable);
                    } else assessments.add(ConstructionCodec.JSON.toJsonTree(SiteAssessment.inspect(candidate, port, port.position(), port.tick(), port.inventory())));
                }
                result.add("candidates", assessments); result.add("known_assets", ConstructionCodec.JSON.toJsonTree(port.knownAssets().stream().limit(16).toList()));
                repository.appendEvidence(id, revision.version(), "site_assessment", port.tick(), result);
            }
            case "observe" -> {
                result.add("observation", session.execution().observe(revision));
                result.addProperty("message", ConstructionText.physical(locale));
            }
            default -> throw new IllegalArgumentException("mode");
        }
        return result;
    }
    static JsonObject error(String locale, Exception error) {
        var result = new JsonObject(); result.addProperty("success", false);
        result.addProperty("error", error instanceof ConcurrentModificationException ? "revision_conflict"
                : error instanceof IOException ? "storage_error" : "invalid_input");
        result.addProperty("message", ConstructionText.invalid(locale)); return result;
    }
}
