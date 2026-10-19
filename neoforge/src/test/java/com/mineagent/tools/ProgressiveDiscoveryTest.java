package com.mineagent.tools;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolRegistry;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.api.llm.*;
import com.mineagent.api.llm.provider.LLMProvider;
import com.mineagent.api.platform.Services;
import com.mineagent.engine.MineAgentEngine;
import com.mineagent.engine.loop.AgentLoop;
import com.mineagent.tools.management.QueryExtraToolsTool;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Offline production discovery -> real loop -> recorded request, not model-quality evidence. */
final class ProgressiveDiscoveryTest {
    @Test void emptyQueryReturnsCompactIndexWithoutRevealingSpecializedSchemas() throws Exception {
        try (var fixture = new Fixture()) {
            var initial = fixture.request();
            assertEquals(23, names(initial).size());
            assertTrue(names(initial).containsAll(Set.of("query_extra_tools", "identity_memory", "build")));
            assertFalse(names(initial).contains("construction"));
            var result = fixture.query("{}");
            assertEquals(0, result.getAsJsonArray("exposed_tools").size());
            assertEquals(20, result.get("total_extra").getAsInt());
            assertTrue(result.has("categories"));
            assertTrue(result.toString().contains("construction"));
            assertTrue(result.toString().contains("identity_memory"));
            assertFalse(result.toString().contains("parameters"));
            assertEquals(names(initial), names(fixture.request()));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"en_us", "pt_br"})
    void categorySelectionReachesOnlyRequestedSchemasInNextRecordedRequest(String language) throws Exception {
        try (var fixture = new Fixture()) {
            RuntimeLanguage.configure(language);
            var initial = names(fixture.request());
            var result = fixture.query("{\"categories\":[\"construction\"]}");
            assertEquals(Set.of("construction", "execute_construction"), jsonNames(result, "exposed_tools"));
            assertEquals(2, result.get("exposed_count").getAsInt());
            assertFalse(result.has("categories"));
            assertFalse(result.toString().contains("parameters"));
            assertFalse(result.toString().contains("properties"));
            var next = names(fixture.request());
            var additions = new HashSet<>(next); additions.removeAll(initial);
            assertEquals(Set.of("construction", "execute_construction"), additions);
            assertEquals(25, next.size());
            assertFalse(next.contains("recall_memory"));
            assertFalse(next.contains("list_learned_skills"));
            var schema = fixture.tool.parameterSchema();
            var properties = (Map<?, ?>) schema.get("properties");
            assertEquals(Set.of("categories", "tool_names", "limit"), properties.keySet());
            assertEquals(false, schema.get("additionalProperties"));
            var categoryItems = (Map<?, ?>) ((Map<?, ?>) properties.get("categories")).get("items");
            assertTrue(((List<?>) categoryItems.get("enum")).contains("construction"));
            assertTrue(((List<?>) categoryItems.get("enum")).contains("identity"));
            assertEquals(4, ((Map<?, ?>) properties.get("limit")).get("maximum"));
            assertTrue(fixture.tool.description().contains(language.equals("pt_br") ? "Descubra" : "Discover"));
            assertTrue(result.get("next_step").getAsString().contains(language.equals("pt_br") ? "próxima" : "next"));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"en_us", "pt_br"})
    void exactNamesAndCategoryUnionExposeOnlyExplicitSpecializations(String language) throws Exception {
        try (var fixture = new Fixture()) {
            RuntimeLanguage.configure(language);
            var initial = names(fixture.request());
            var result = fixture.query("{\"tool_names\":[\"recall_memory\"],\"limit\":1}");
            assertEquals(Set.of("recall_memory"), jsonNames(result, "exposed_tools"));
            var expected = new HashSet<>(initial); expected.add("recall_memory");
            assertEquals(expected, names(fixture.request()));
            result = fixture.query("{\"categories\":[\"construction\",\"identity\"],\"tool_names\":[\"construction\",\"identity_memory\"],\"limit\":2}");
            assertEquals(Set.of("construction", "execute_construction"), jsonNames(result, "exposed_tools"));
            assertEquals(Set.of("identity_memory"), jsonNames(result, "already_available_tools"));
            expected.addAll(Set.of("construction", "execute_construction"));
            assertEquals(expected, names(fixture.request()));
            result = fixture.query("{\"categories\":[\"identity\"],\"limit\":1}");
            assertEquals(Set.of(), jsonNames(result, "exposed_tools"));
            assertEquals(Set.of("identity_memory"), jsonNames(result, "already_available_tools"));
            assertFalse(result.toString().contains("entries"));
            assertFalse(result.toString().contains("designs"));
            assertEquals(expected, names(fixture.request()));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"en_us", "pt_br"})
    void oversizedSelectionIsRejectedAtomicallyWithoutSilentTruncation(String language) throws Exception {
        try (var fixture = new Fixture()) {
            RuntimeLanguage.configure(language);
            var initial = names(fixture.request());
            for (String args : List.of("{\"categories\":[\"construction\"],\"limit\":1}",
                    "{\"categories\":[\"construction\",\"observation\"]}")) {
                var result = fixture.query(args);
                assertTrue(result.has("error"), result.toString());
                assertEquals("discovery_limit_exceeded", result.get("code").getAsString());
                assertFalse(result.has("exposed_tools"));
                assertEquals(initial, names(fixture.request()));
                assertTrue(result.get("error").getAsString().contains(language.equals("pt_br") ? "limite" : "limit"));
            }
        }
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> invalidSelections() {
        var cases = new ArrayList<org.junit.jupiter.params.provider.Arguments>();
        for (String language : List.of("en_us", "pt_br")) {
            cases.add(org.junit.jupiter.params.provider.Arguments.of(language, null, "args"));
            for (String json : List.of(
                    "{\"query\":\"construir uma casa / build a house\"}",
                    "{\"categories\":null}", "{\"categories\":\"construction\"}", "{\"categories\":{}}",
                    "{\"categories\":[null]}", "{\"categories\":[1]}", "{\"categories\":[true]}",
                    "{\"categories\":[\"\"]}", "{\"categories\":[\" construction\"]}",
                    "{\"categories\":[\"Construction\"]}", "{\"categories\":[\"construção\"]}",
                    "{\"categories\":[\"unknown\"]}", "{\"categories\":[\"construction\",\"construction\"]}",
                    "{\"categories\":[\"identity\",\"memory\",\"mechanisms\",\"inventory\",\"team\"]}",
                    "{\"tool_names\":null}", "{\"tool_names\":\"construction\"}", "{\"tool_names\":{}}",
                    "{\"tool_names\":[null]}", "{\"tool_names\":[1]}", "{\"tool_names\":[false]}",
                    "{\"tool_names\":[\"\"]}", "{\"tool_names\":[\"construction \"]}",
                    "{\"tool_names\":[\"Construction\"]}", "{\"tool_names\":[\"construction\\n\"]}",
                    "{\"tool_names\":[\"construção\"]}", "{\"tool_names\":[\"construction\",\"unknown\"]}",
                    "{\"tool_names\":[\"recall_memory\",\"recall_memory\"]}",
                    "{\"tool_names\":[\"construction\",\"recall_memory\",\"drop_items\",\"locate_biome\",\"ranged_attack\"]}",
                    "{\"limit\":null}", "{\"limit\":\"1\"}", "{\"limit\":true}", "{\"limit\":{}}",
                    "{\"limit\":1.5}", "{\"limit\":0}", "{\"limit\":-1}", "{\"limit\":5}",
                    "{\"limit\":4294967297}")) {
                String key = JsonParser.parseString(json).getAsJsonObject().keySet().iterator().next();
                cases.add(org.junit.jupiter.params.provider.Arguments.of(language, json, key));
            }
        }
        return cases.stream();
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0} rejects {1}")
    @org.junit.jupiter.params.provider.MethodSource("invalidSelections")
    void invalidArgumentsNeverNormalizeIdsOrExposePartialSelections(String language, String args, String key) throws Exception {
        try (var fixture = new Fixture()) {
            RuntimeLanguage.configure(language);
            var initial = names(fixture.request());
            var result = assertDoesNotThrow(() -> fixture.query(args));
            assertTrue(result.has("error"), result.toString());
            assertEquals("invalid_discovery_selection", result.get("code").getAsString());
            assertEquals(key, result.get("field").getAsString());
            var expected = args == null ? com.google.gson.JsonNull.INSTANCE : JsonParser.parseString(args).getAsJsonObject().get(key);
            assertEquals(expected, result.get("invalid_value"));
            assertFalse(result.has("exposed_tools"));
            assertTrue(result.get("error").getAsString().contains(language.equals("pt_br") ? "inválida" : "Invalid"));
            assertEquals(initial, names(fixture.request()));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"en_us", "pt_br"})
    void missingCompanionCannotClaimSuccessfulSchemaExposure(String language) throws Exception {
        try (var fixture = new Fixture()) {
            RuntimeLanguage.configure(language);
            fixture.companions.remove(fixture.body.companionId());
            var initial = names(fixture.request());
            var result = fixture.query("{\"tool_names\":[\"construction\"]}");
            assertTrue(result.has("error"), result.toString());
            assertEquals("discovery_unavailable", result.get("code").getAsString());
            assertFalse(result.has("exposed_tools"));
            assertEquals(initial, names(fixture.request()));
            var reply = new AtomicReference<String>();
            assertDoesNotThrow(() -> fixture.tool.onServerCall("literal", JsonParser.parseString("{\"categories\":[\"construction\"]}").getAsJsonObject(), null, reply::set));
            assertEquals("discovery_unavailable", JsonParser.parseString(reply.get()).getAsJsonObject().get("code").getAsString());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"en_us", "pt_br"})
    void indexCoversRegisteredExtrasAndEveryCategoryHasBoundedExactSelection(String language) throws Exception {
        try (var fixture = new Fixture()) {
            RuntimeLanguage.configure(language);
            var index = fixture.query("{\"categories\":[],\"tool_names\":[]}");
            assertTrue(index.toString().length() < 4096, "Capability index must stay compact");
            var indexed = new HashSet<String>();
            var expectedSchemas = new HashSet<>(names(fixture.request()));
            for (var entry : index.getAsJsonArray("categories")) {
                var category = entry.getAsJsonObject();
                var members = jsonNames(category, "tool_names");
                for (var name : members) assertTrue(indexed.add(name), "Duplicate indexed ID: " + name);
                var args = new JsonObject();
                var categories = new com.google.gson.JsonArray(); categories.add(category.get("category"));
                args.add("categories", categories);
                var result = fixture.query(args.toString());
                var expectedExtras = new HashSet<>(members); expectedExtras.removeIf(AgentLoop::isCoreTool);
                assertEquals(expectedExtras, jsonNames(result, "exposed_tools"));
                assertTrue(expectedExtras.size() <= 4);
                assertTrue(result.toString().length() < 1024);
                expectedSchemas.addAll(expectedExtras);
                assertEquals(expectedSchemas, names(fixture.request()));
            }
            var allExpected = new HashSet<String>();
            ToolRegistry.all().stream().filter(t -> !AgentLoop.isCoreTool(t.name())).forEach(t -> allExpected.add(t.name()));
            allExpected.add("identity_memory");
            assertEquals(allExpected, indexed);
            assertEquals(ToolRegistry.size(), expectedSchemas.size());
        }
    }

    @Test void additionalRegisteredSpecializationRemainsDiscoverableByItsLiteralId() throws Exception {
        try (var fixture = new Fixture()) {
            String id = "addon_probe_v2";
            ToolRegistry.register(new Tool() {
                public String name() { return id; }
                public String description() { return "Offline extension fixture"; }
                public Map<String, Object> parameterSchema() { return Map.of("type", "object"); }
                public void onServerCall(String callId, JsonObject args, AgentPlayer body, java.util.function.Consumer<String> reply) {
                    throw new AssertionError("Discovery must not execute a discovered specialization");
                }
            });
            var initial = names(fixture.request());
            var index = fixture.query("{}");
            var group = new AtomicReference<JsonObject>();
            index.getAsJsonArray("categories").forEach(entry -> {
                if (entry.getAsJsonObject().get("category").getAsString().equals("other")) group.set(entry.getAsJsonObject());
            });
            assertEquals(Set.of(id), jsonNames(group.get(), "tool_names"));
            assertEquals(21, index.get("total_extra").getAsInt());
            assertEquals(initial, names(fixture.request()));
            var result = fixture.query("{\"tool_names\":[\"addon_probe_v2\"],\"limit\":1}");
            assertEquals(Set.of(id), jsonNames(result, "exposed_tools"));
            var expected = new HashSet<>(initial); expected.add(id);
            assertEquals(expected, names(fixture.request()));
            result = fixture.query("{\"categories\":[\"other\"],\"limit\":1}");
            assertEquals(Set.of(id), jsonNames(result, "exposed_tools"));
        }
    }

    private static void record(String event, Object payload) {
        String path = System.getProperty("mineagentx.discoveryRequestLog");
        if (path == null) return;
        try {
            var entry = Map.of("event", event, "language", RuntimeLanguage.language().id(), "payload", payload);
            java.nio.file.Files.writeString(java.nio.file.Path.of(path), new com.google.gson.GsonBuilder().serializeNulls().create().toJson(entry) + "\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException failure) {
            throw new AssertionError("Could not record offline request evidence", failure);
        }
    }

    private static Set<String> jsonNames(JsonObject result, String field) {
        var names = new LinkedHashSet<String>();
        result.getAsJsonArray(field).forEach(id -> names.add(id.getAsString()));
        return names;
    }

    private static Set<String> names(LLMRequest request) {
        var names = new LinkedHashSet<String>();
        for (var definition : request.tools()) {
            var function = (Map<?, ?>) definition.get("function");
            names.add((String) function.get("name"));
        }
        return names;
    }

    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static final class Fixture implements AutoCloseable {
        final List<Tool> previousTools = new ArrayList<>(ToolRegistry.all());
        final String previousLanguage = RuntimeLanguage.language().id();
        final java.lang.reflect.Field platformField = Services.class.getDeclaredField("PLATFORM");
        final Object previousPlatform;
        final AgentPlayer body = new TestBody();
        final AgentLoop loop;
        final Map<UUID, MineAgentEngine.CompanionState> companions;
        final MineAgentEngine.CompanionState previousCompanion;
        final QueryExtraToolsTool tool = new QueryExtraToolsTool();
        Fixture() throws Exception {
            platformField.setAccessible(true);
            previousPlatform = platformField.get(null);
            platformField.set(null, new Services.IPlatform() {
                public void scheduleOnServer(Runnable task) { task.run(); }
                public Optional<UUID> findPlayerUuid(String name) { return Optional.empty(); }
                public boolean isPlayerOnline(UUID id) { return false; }
                public boolean isClientSide() { return false; }
                public long gameTime() { return 0; }
            });
            ToolRegistry.clear();
            ToolRegistration.registerAll();
            loop = new AgentLoop(body, "ollama", "", "fixture", "http://127.0.0.1", 0, 100, "auto");
            var mapField = MineAgentEngine.class.getDeclaredField("COMPANIONS");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked") var map = (Map<UUID, MineAgentEngine.CompanionState>) mapField.get(null);
            companions = map;
            previousCompanion = companions.put(body.companionId(), new MineAgentEngine.CompanionState(null, loop, null, null));
        }
        JsonObject query(String json) {
            var reply = new AtomicReference<String>();
            tool.onServerCall("literal-call-{0}-$-\\-ç", json == null ? null : JsonParser.parseString(json).getAsJsonObject(), body, reply::set);
            assertNotNull(reply.get());
            var result = JsonParser.parseString(reply.get()).getAsJsonObject();
            record("query", Map.of("args", json == null ? com.google.gson.JsonNull.INSTANCE : JsonParser.parseString(json), "result", result));
            return result;
        }
        LLMRequest request() throws Exception {
            var definitions = AgentLoop.class.getDeclaredMethod("buildToolDefinitions");
            definitions.setAccessible(true);
            var method = AgentLoop.class.getDeclaredMethod("callLLMWithRetry", LLMProvider.class, List.class, int.class);
            method.setAccessible(true);
            var provider = new RecordingProvider();
            assertNotNull(method.invoke(loop, provider, definitions.invoke(loop), 0));
            assertNotNull(provider.request);
            return provider.request;
        }
        public void close() throws Exception {
            ((ExecutorService) field(loop, "executor")).shutdownNow();
            if (previousCompanion == null) companions.remove(body.companionId());
            else companions.put(body.companionId(), previousCompanion);
            ToolRegistry.clear();
            previousTools.forEach(ToolRegistry::register);
            RuntimeLanguage.configure(previousLanguage);
            platformField.set(null, previousPlatform);
        }
    }

    private static final class RecordingProvider implements LLMProvider {
        LLMRequest request;
        public String providerId() { return "ollama"; }
        public String displayName() { return "Offline recording fixture"; }
        public String defaultBaseUrl() { return "http://127.0.0.1"; }
        public boolean supportsModel(String model) { return true; }
        public List<String> defaultModels() { return List.of(); }
        public LLMResponse complete(LLMRequest request) {
            this.request = request;
            record("request", Map.of("messages", request.messages(), "tools", request.tools()));
            return new LLMResponse("fixture", "fixture", new LLMResponse.Choice(0, ChatMessage.assistant(""), "stop"), null, "stop");
        }
        public LLMResponse complete(String baseUrl, String key, String model, List<ChatMessage> messages,
                                    List<Map<String, Object>> tools, double temperature, int tokens, String effort) {
            throw new AssertionError("Expected actual LLMRequest entry point");
        }
    }

    private static final class TestBody extends AgentPlayer {
        public UUID companionId() { return new UUID(0, 701); }
        public UUID ownerUuid() { return new UUID(0, 702); }
        public String companionName() { return "discovery fixture"; }
        public String ownerName() { return "fixture owner"; }
        public String blockPositionStr() { return "0,0,0"; }
        public double posX() { return 0; } public double posY() { return 0; } public double posZ() { return 0; }
        public float health() { return 20; } public float maxHealth() { return 20; }
        public int foodLevel() { return 20; } public int airSupply() { return 300; }
        public boolean isInWater() { return false; } public boolean isAlive() { return true; }
        public void holdInHand(int slot) { }
        public String mainHandItemId() { return "minecraft:air"; }
        public String dimensionKey() { return "minecraft:overworld"; }
    }
}
