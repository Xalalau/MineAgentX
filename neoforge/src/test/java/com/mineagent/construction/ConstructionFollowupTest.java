package com.mineagent.construction;

import com.google.gson.*;
import com.mineagent.api.construction.ConstructionDesign;
import com.mineagent.api.construction.ConstructionDesign.Point;
import com.mineagent.engine.construction.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

final class ConstructionFollowupTest {
    @TempDir Path root;
    static final class Port implements ConstructionExecution.Port {
        final Thread owner = Thread.currentThread();
        final Map<Point, SiteAssessment.Observation> world = new HashMap<>();
        String status = "PENDING", session = "session", dimension = "minecraft:overworld";
        public String dimension() { return dimension; }
        public Point position() { return ConstructionChecks.design().origin(); }
        public long tick() { assertSame(owner, Thread.currentThread()); return 12; }
        public String sessionId() { return session; }
        public Map<String, Integer> inventory() { return Map.of("minecraft:stone", 10, "minecraft:oak_planks", 10); }
        public String taskState(String id) { return status; }
        public void dispatch(String id, ConstructionExecution.Batch batch) { }
        public SiteAssessment.Observation observe(Point p) {
            assertSame(owner, Thread.currentThread());
            return world.getOrDefault(p, new SiteAssessment.Observation("minecraft:air", true, false, false, false, false, 0));
        }
    }
    private static boolean terminal(ConstructionSession session, String id, String state) throws Exception {
        return session.execution().taskFinished(id, state);
    }
    @Test void terminalCallbackUsesExactRevisionAndWorldEvenAfterLedgerEviction() throws Exception {
        var repository = new ConstructionRepository(root);
        var revision = repository.save(ConstructionChecks.design(), 0, "reason");
        var port = new Port(); var session = new ConstructionSession(repository, port);
        session.execution().execute(revision, false, "task");
        repository.save(ConstructionChecks.design().at(port.dimension, new Point(20, 65, 20), 0), 1, "new location");
        port.status = null;
        assertTrue(terminal(session, "task", "SUCCESS"));
        var outcome = repository.evidence("work", 1).getLast();
        assertEquals("outcome", outcome.kind()); assertEquals(1, outcome.revision());
        assertEquals("SUCCESS", outcome.payload().get("task_state").getAsString());
        assertEquals(0, outcome.payload().get("matched").getAsInt(), "Provider/task success is not world success");
        assertFalse(terminal(session, "task", "FAILED"), "Duplicate callbacks must not overwrite first outcome");
        assertFalse(terminal(session, "unrelated", "SUCCESS"));
        assertEquals(2, repository.evidence("work", 0).size());
    }
    @Test void terminalLedgerOutsideDesignDimensionRecordsUnknownNotPermanentPending() throws Exception {
        var repository = new ConstructionRepository(root);
        var revision = repository.save(ConstructionChecks.design(), 0, "reason");
        var port = new Port(); var execution = new ConstructionExecution(repository, port);
        execution.execute(revision, false, "task");
        port.dimension = "minecraft:the_nether"; port.status = "CANCELLED";
        assertFalse(execution.reconcile("work"));
        var outcome = repository.evidence("work", 1).getLast().payload();
        assertEquals("CANCELLED", outcome.get("task_state").getAsString());
        assertEquals(1, outcome.get("unknown").getAsInt()); assertEquals(0, outcome.get("matched").getAsInt());
    }
    private JsonObject call(com.mineagent.tools.construction.ConstructionTool tool, String mode) {
        var args = new JsonObject(); args.addProperty("mode", mode); args.addProperty("id", "work");
        var reply = new java.util.concurrent.atomic.AtomicReference<String>();
        tool.onServerCall("query", args, null, reply::set);
        return JsonParser.parseString(reply.get()).getAsJsonObject();
    }
    @Test void reviewIsExplicitAndDisabledWithoutVisionConfiguration() throws Exception {
        var repository = new ConstructionRepository(root);
        repository.save(ConstructionChecks.design(), 0, "reason");
        var tool = new com.mineagent.tools.construction.ConstructionTool(p -> new ConstructionSession(repository, null), null);
        var result = call(tool, "review");
        assertTrue(result.get("success").getAsBoolean(), "An optional disabled review is not malformed syntax");
        assertEquals("vision_not_configured", result.get("state").getAsString());
        assertTrue(repository.evidence("work", 1).isEmpty());
        var properties = (Map<?, ?>) tool.parameterSchema().get("properties");
        assertTrue(((List<?>) ((Map<?, ?>) properties.get("mode")).get("enum")).contains("review_status"));
    }
    private com.mineagent.tools.construction.ConstructionTool configuredTool(ConstructionRepository repository,
            java.util.function.Supplier<com.mineagent.api.config.MineAgentConfig.VisionConfig> settings) throws Exception {
        var port = new Port();
        return new com.mineagent.tools.construction.ConstructionTool(p -> new ConstructionSession(repository, port), null, settings);
    }
    private JsonObject awaitReview(com.mineagent.tools.construction.ConstructionTool tool) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        JsonObject result;
        do {
            result = call(tool, "review_status");
            if (!Set.of("queued", "pending").contains(result.get("state").getAsString())) return result;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Review did not terminate within fixture bound");
    }
    @Test void configuredReviewSendsSixRealPngsThroughBothLocalHttpProtocolsFixtureOnly() throws Exception {
        for (String protocol : List.of("openai-compatible", "ollama")) {
            var received = new java.util.concurrent.atomic.AtomicReference<JsonObject>();
            var path = new java.util.concurrent.atomic.AtomicReference<String>();
            var authorization = new java.util.concurrent.atomic.AtomicReference<String>();
            String locale = protocol.equals("ollama") ? "pt_br" : "en_us";
            var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                path.set(exchange.getRequestURI().getPath());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                received.set(JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject());
                String fixture = protocol.equals("ollama")
                        ? "{\"done\":true,\"message\":{\"role\":\"assistant\",\"content\":\"fixture-only advisory\"}}"
                        : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"fixture-only advisory\"},\"finish_reason\":\"stop\"}]}";
                byte[] bytes = fixture.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.start();
            try {
                var repository = new ConstructionRepository(root.resolve(protocol));
                repository.save(ConstructionChecks.design(), 0, "reason");
                var cfg = new com.mineagent.api.config.MineAgentConfig.VisionConfig(true, protocol, "fixture-review-key", "explicit-fixture-model",
                        "http://127.0.0.1:" + server.getAddress().getPort());
                var tool = configuredTool(repository, () -> cfg);
                com.mineagent.api.i18n.RuntimeLanguage.configure(locale);
                assertEquals("queued", call(tool, "review").get("state").getAsString());
                com.mineagent.api.i18n.RuntimeLanguage.configure(locale.equals("pt_br") ? "en_us" : "pt_br");
                var result = awaitReview(tool);
                assertEquals("reviewed", result.get("state").getAsString());
                assertTrue(result.get("advisory").getAsBoolean());
                assertTrue(result.has("language"), "The persisted advisory must retain its request language");
                assertEquals(locale, result.get("language").getAsString());
                assertEquals("Bearer fixture-review-key", authorization.get());
                String guidance = received.get().getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
                assertTrue(guidance.startsWith(locale.equals("pt_br") ? "Avalie" : "Review"));
                assertEquals("fixture-only advisory", result.get("review").getAsString());
                assertEquals("visual_review", repository.evidence("work", 1).getLast().kind());
                assertEquals("explicit-fixture-model", received.get().get("model").getAsString());
                assertFalse(received.get().has("tools"));
                var message = received.get().getAsJsonArray("messages").get(1).getAsJsonObject();
                JsonArray images;
                if (protocol.equals("ollama")) {
                    assertEquals("/api/chat", path.get()); images = message.getAsJsonArray("images");
                } else {
                    assertEquals("/v1/chat/completions", path.get());
                    images = new JsonArray(); var parts = message.getAsJsonArray("content");
                    assertEquals(7, parts.size());
                    for (int i = 1; i < parts.size(); i++) images.add(parts.get(i).getAsJsonObject().getAsJsonObject("image_url").get("url").getAsString().split(",", 2)[1]);
                }
                assertEquals(6, images.size());
                for (var image : images) assertNotNull(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(Base64.getDecoder().decode(image.getAsString()))));
                call(tool, "review_status"); assertEquals(1, repository.evidence("work", 1).size(), "Polling is idempotent");
            } finally { com.mineagent.api.i18n.RuntimeLanguage.configure("en_us"); server.stop(0); }
        }
    }
    @Test void revisedDesignDiscardsInFlightReviewWithoutPublishingStaleEvidenceFixtureOnly() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes(); started.countDown();
            try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            byte[] bytes = "{\"choices\":[{\"message\":{\"content\":\"fixture obsolete review\"}}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try { exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); } finally { exchange.close(); }
        }); server.start();
        try {
            var repository = new ConstructionRepository(root); repository.save(ConstructionChecks.design(), 0, "reason");
            var cfg = new com.mineagent.api.config.MineAgentConfig.VisionConfig(true, "openai-compatible", "", "fixture-model", "http://127.0.0.1:"+server.getAddress().getPort());
            var tool = configuredTool(repository, () -> cfg);
            assertEquals("queued", call(tool, "review").get("state").getAsString());
            assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS));
            repository.save(ConstructionChecks.design(), 1, "revised rationale");
            assertEquals("stale_revision", call(tool, "review_status").get("state").getAsString());
            release.countDown();
            assertEquals("not_requested", call(tool, "review_status").get("state").getAsString());
            assertTrue(repository.evidence("work", 0).isEmpty());
        } finally { release.countDown(); server.stop(0); }
    }
    @Test void changingExplicitModelDiscardsOldReviewFixtureOnly() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes(); started.countDown();
            try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        }); server.start();
        try {
            var repository = new ConstructionRepository(root); repository.save(ConstructionChecks.design(), 0, "reason");
            var settings = new java.util.concurrent.atomic.AtomicReference<>(new com.mineagent.api.config.MineAgentConfig.VisionConfig(
                    true, "openai-compatible", "", "explicit-model", "http://127.0.0.1:"+server.getAddress().getPort()));
            var tool = configuredTool(repository, settings::get);
            assertEquals("queued", call(tool, "review").get("state").getAsString());
            assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS));
            var cfg = settings.get(); settings.set(new com.mineagent.api.config.MineAgentConfig.VisionConfig(true, cfg.provider(), cfg.apiKey(), "different-explicit-model", cfg.baseUrl()));
            assertEquals("configuration_changed", call(tool, "review_status").get("state").getAsString());
            assertTrue(repository.evidence("work", 0).isEmpty());
        } finally { release.countDown(); server.stop(0); }
    }
    @Test void stalledLocalReviewHasBoundedDeadlineWithoutBlockingServerFixtureOnly() throws Exception {
        var review = new ConstructionVisualReview(java.time.Duration.ofMillis(200));
        var started = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes(); started.countDown();
            try { release.await(3, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        }); server.start();
        try {
            var repository = new ConstructionRepository(root); var revision = repository.save(ConstructionChecks.design(), 0, "reason");
            var config = new com.mineagent.api.config.MineAgentConfig.VisionConfig(true, "openai-compatible", "", "explicit-model", "http://127.0.0.1:"+server.getAddress().getPort());
            assertEquals("queued", review.request(repository, revision, config, "en_us", 1, true).get("state").getAsString());
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
            Thread.sleep(400);
            assertEquals("timeout", review.request(repository, revision, config, "en_us", 2, false).get("state").getAsString());
            assertTrue(repository.evidence("work", 0).isEmpty());
        } finally {
            release.countDown(); server.stop(0);
            review.close();
        }
    }
    @Test void disablingReviewCancelsRatherThanResurrectingOldWorkFixtureOnly() throws Exception {
        var started = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes(); started.countDown();
            try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        }); server.start();
        try {
            var repository = new ConstructionRepository(root); var revision = repository.save(ConstructionChecks.design(), 0, "reason");
            var config = new com.mineagent.api.config.MineAgentConfig.VisionConfig(true, "openai-compatible", "", "explicit-model", "http://127.0.0.1:"+server.getAddress().getPort());
            try (var review = new ConstructionVisualReview()) {
                assertEquals("queued", review.request(repository, revision, config, "en_us", 1, true).get("state").getAsString());
                assertTrue(started.await(3, java.util.concurrent.TimeUnit.SECONDS));
                assertEquals("vision_not_configured", review.request(repository, revision, com.mineagent.api.config.MineAgentConfig.VisionConfig.DEFAULTS, "en_us", 1, false).get("state").getAsString());
                assertEquals("not_requested", review.request(repository, revision, config, "en_us", 2, false).get("state").getAsString());
                assertTrue(repository.evidence("work", 0).isEmpty());
            }
        } finally { release.countDown(); server.stop(0); }
    }
    @Test void ambiguousRenameNeverCreatesOrOverwritesAnotherConstructionNamespace() throws Exception {
        var owner = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertEquals(true, MinecraftConstructionBridge.canRename(root, owner, "old", "new"));
        var oldRoot = root.resolve("mineagent_construction").resolve(MinecraftConstructionBridge.storageKey(owner, "old"));
        new ConstructionRepository(oldRoot).save(ConstructionChecks.design(), 0, "reason");
        assertEquals(false, MinecraftConstructionBridge.canRename(root, owner, "old", "new"));
        assertEquals(true, MinecraftConstructionBridge.canRename(root, owner, "old", "old"));
        assertEquals(false, MinecraftConstructionBridge.canRename(root, owner, "other", "old"));
        assertFalse(Files.exists(root.resolve("mineagent_construction").resolve(MinecraftConstructionBridge.storageKey(owner, "new"))));
        assertEquals(1, new ConstructionRepository(oldRoot).read("work", 0).version());
        Path blocked = root.resolve("not-a-world-directory"); Files.writeString(blocked, "obstruction");
        assertEquals(false, MinecraftConstructionBridge.canRename(blocked, owner, "old", "new"), "Unknown/unreadable paths must fail closed");
    }
    @Test void advisoryEvidenceCannotConsumeReservedPhysicalTerminalReceipt() throws Exception {
        var repository = new ConstructionRepository(root); var revision = repository.save(ConstructionChecks.design(), 0, "reason");
        for (int i = 0; i < 253; i++) repository.appendEvidence("work", 1, "preview", 0, new JsonObject());
        var session = new ConstructionSession(repository, new Port()); session.execution().execute(revision, false, "task");
        repository.appendEvidence("work", 1, "preview", 0, new JsonObject());
        assertThrows(IllegalArgumentException.class, () -> repository.appendEvidence("work", 1, "visual_review", 0, new JsonObject()),
                "An advisory cannot crowd out the observed terminal receipt");
        assertTrue(terminal(session, "task", "FAILED"));
        assertEquals(256, repository.evidence("work", 0).size());
        assertEquals("outcome", repository.evidence("work", 0).getLast().kind());
    }
    @Test void rejectsImplicitVisionAndNonlocalEndpointsWithoutSubmittingWork() throws Exception {
        var repository = new ConstructionRepository(root); var revision = repository.save(ConstructionChecks.design(), 0, "reason");
        try (var review = new ConstructionVisualReview()) {
            for (var cfg : List.of(
                    new com.mineagent.api.config.MineAgentConfig.VisionConfig(false, "ollama", "", "fixture-model", "http://127.0.0.1:1"),
                    new com.mineagent.api.config.MineAgentConfig.VisionConfig(true, "ollama", "", "", "http://127.0.0.1:1"),
                    new com.mineagent.api.config.MineAgentConfig.VisionConfig(true, "unsupported", "", "vision-by-name", "http://127.0.0.1:1")))
                assertEquals("vision_not_configured", review.request(repository, revision, cfg, "en_us", 1, true).get("state").getAsString());
            var remote = new com.mineagent.api.config.MineAgentConfig.VisionConfig(true, "openai-compatible", "", "explicit", "https://example.com/v1");
            assertThrows(IllegalArgumentException.class, () -> review.request(repository, revision, remote, "en_us", 1, true));
            assertTrue(repository.evidence("work", 0).isEmpty());
        }
    }
    @Test void reviewRejectsExcessConcurrentRequestsRatherThanGrowingAnUnboundedQueueFixtureOnly() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            exchange.close();
        }); server.start();
        try (var review = new ConstructionVisualReview()) {
            var cfg = new com.mineagent.api.config.MineAgentConfig.VisionConfig(true, "openai-compatible", "", "explicit", "http://127.0.0.1:"+server.getAddress().getPort());
            for (int n = 0; n < 5; n++) {
                var repository = new ConstructionRepository(root.resolve("scope-"+n));
                var revision = repository.save(ConstructionChecks.design(), 0, "reason");
                assertEquals(n < 4 ? "queued" : "review_busy", review.request(repository, revision, cfg, "en_us", 1, true).get("state").getAsString());
            }
        } finally { release.countDown(); server.stop(0); }
    }
    @Test void taskIdReuseCannotAttachALateTerminalCallbackToAnotherRevision() throws Exception {
        var repository = new ConstructionRepository(root); var first = repository.save(ConstructionChecks.design(), 0, "reason");
        var session = new ConstructionSession(repository, new Port()); session.execution().execute(first, false, "same-id");
        assertTrue(terminal(session, "same-id", "FAILED"));
        var second = repository.save(ConstructionChecks.design(), 1, "revised reason");
        assertEquals("task_id_reused", session.execution().execute(second, false, "same-id").get("state").getAsString());
        assertFalse(terminal(session, "same-id", "SUCCESS"));
        assertTrue(repository.evidence("work", 2).isEmpty());
    }
    @Test void admissionIsNotTerminalReconciliation() throws Exception {
        var repository = new ConstructionRepository(root);
        var revision = repository.save(ConstructionChecks.design(), 0, "reason");
        var port = new Port(); var session = new ConstructionSession(repository, port);
        session.execution().execute(revision, false, "task");
        assertFalse(session.taskFinished("task"), "Pending admission must not be reported as reconciled");
        assertEquals(1, repository.evidence("work", 0).size());
    }
}
