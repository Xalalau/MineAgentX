package com.mineagent.tools.construction;

import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.i18n.RuntimeLanguage;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Async body ownership is unconditional here, unlike the design/query tool. */
public final class ExecuteConstructionTool implements Tool {
    private final ConstructionTool.SessionFactory sessions;
    public ExecuteConstructionTool(ConstructionTool.SessionFactory sessions) { this.sessions = Objects.requireNonNull(sessions); }
    @Override public String name() { return "execute_construction"; }
    @Override public boolean dispatchesAsyncTask() { return true; }
    @Override public String description() { return description(RuntimeLanguage.language().id()); }
    public String description(String language) {
        return ConstructionText.text(language, "execute_description");
    }
    @Override public Map<String, Object> parameterSchema() { return ConstructionSchema.executeTool(RuntimeLanguage.language().id()); }
    public Map<String, Object> parameterSchema(String language) { return ConstructionSchema.executeTool(language); }
    @Override public void onServerCall(String callId, JsonObject args, AgentPlayer player, Consumer<String> reply) {
        String locale = RuntimeLanguage.language().id(); JsonObject result;
        try {
            if (args == null || args.toString().length() > 1024) throw new IllegalArgumentException("request_size");
            var session = sessions.open(player);
            int version = ConstructionCodec.integer(args, "revision", 1, 128, -1);
            if (version < 1) throw new IllegalArgumentException("revision");
            var revision = session.repository().read(ConstructionCodec.string(args, "id", null), version);
            result = session.execution().execute(revision, ConstructionCodec.bool(args, "allow_clear"), callId);
            result.addProperty("success", true); result.addProperty("message", ConstructionText.physical(locale));
        } catch (IOException | RuntimeException error) { result = ConstructionTool.error(locale, error); }
        reply.accept(result.toString());
    }
}
