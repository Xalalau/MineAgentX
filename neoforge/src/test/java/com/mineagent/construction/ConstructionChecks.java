package com.mineagent.construction;

import com.mineagent.api.construction.ConstructionDesign;
import com.mineagent.api.construction.ConstructionDesign.*;
import java.util.List;

/** Standalone assertions also invoked by JUnit; no Minecraft bootstrap required. */
public final class ConstructionChecks {
    public static ConstructionDesign design() {
        return new ConstructionDesign("work", "minecraft:overworld", new Point(10, 64, 20),
                1, new Point(3, 2, 4),
                new Decisions("purpose", "interiors", "access", "terrain", "materials", "transport"),
                List.of(new Cell(new Point(0, 0, 0), "minecraft:stone"),
                        new Cell(new Point(2, 1, 3), "minecraft:oak_planks"),
                        new Cell(new Point(1, 0, 1), "minecraft:air")));
    }
    public static void geometry() {
        var d = design();
        check(d.materials().equals(java.util.Map.of("minecraft:stone", 1, "minecraft:oak_planks", 1)), "world-independent exact design material bill");
        check(d.world(new Point(2, 1, 3)).equals(new Point(7, 65, 22)), "quarter-turn world transform");
        rejects(() -> new ConstructionDesign("../bad", d.dimension(), d.origin(), 0, d.size(), d.decisions(), d.cells()));
        rejects(() -> new ConstructionDesign(d.id(), d.dimension(), d.origin(), 0, d.size(), d.decisions(),
                List.of(d.cells().getFirst(), d.cells().getFirst())));
        rejects(() -> new ConstructionDesign(d.id(), "bad", d.origin(), 0, d.size(), d.decisions(), d.cells()));
        rejects(() -> new ConstructionDesign(d.id(), d.dimension(), d.origin(), 4, d.size(), d.decisions(), d.cells()));
        rejects(() -> new ConstructionDesign(d.id(), d.dimension(), d.origin(), 0, d.size(), d.decisions(),
                List.of(new Cell(new Point(3, 0, 0), "minecraft:stone"))));
        rejects(() -> new Cell(new Point(0, 0, 0), "stone"));
        rejects(() -> new ConstructionDesign(d.id(), d.dimension(), new Point(0, -2049, 0), 0,
                d.size(), d.decisions(), d.cells()));
        rejects(() -> new ConstructionDesign(d.id(), d.dimension(), new Point(Integer.MAX_VALUE, 0, 0), 0,
                d.size(), d.decisions(), d.cells()));
    }
    public static void persistence() throws Exception {
        var root = java.nio.file.Files.createTempDirectory(workDirectory(), "repository-");
        var repo = new com.mineagent.engine.construction.ConstructionRepository(root);
        var first = repo.save(design(), 0, "initial reasoning");
        check(first.version() == 1, "initial revision");
        var revised = design().at("minecraft:the_nether", new Point(20, 70, 30), 2);
        repo.save(revised, 1, "new site reasoning");
        var restored = new com.mineagent.engine.construction.ConstructionRepository(root);
        check(restored.read("work", 1).design().equals(design()), "old revision immutable");
        check(restored.read("work", 0).design().equals(revised), "latest revision persists transform");
        check(restored.list("", 10).getFirst().version() == 2, "discoverable latest revision");
        try { repo.save(design(), 1, "stale update"); throw new AssertionError("Expected revision conflict"); }
        catch (java.util.ConcurrentModificationException expected) { }
        var payload = new com.google.gson.JsonObject(); payload.addProperty("verified", 1);
        repo.appendEvidence("work", 1, "execution", 50, payload);
        check(restored.evidence("work", 1).getFirst().payload().get("verified").getAsInt() == 1,
                "observed outcomes survive restart and remain attached to exact revision");
        rejects(() -> ConstructionDesign.requireId("../work"));
        java.nio.file.Files.writeString(root.resolve("work/1.json"), "{\"schema\":99}");
        try { restored.read("work", 1); throw new AssertionError("Expected corrupt version rejection"); }
        catch (java.io.IOException expected) { }
    }
    public static void assessment() {
        var d = design();
        var obstacle = d.world(new Point(2, 1, 3));
        com.mineagent.engine.construction.SiteAssessment.WorldView world = point -> {
            if (point.equals(obstacle)) return new com.mineagent.engine.construction.SiteAssessment.Observation(
                    "minecraft:oak_log", false, true, false, true, false, 12);
            return new com.mineagent.engine.construction.SiteAssessment.Observation(
                    "minecraft:air", true, false, false, false, false, 0);
        };
        var assessment = com.mineagent.engine.construction.SiteAssessment.inspect(d, world, new Point(10, 64, 20), 10);
        check(assessment.sampledVolume() == 24 && assessment.freeVolume() == 23, "entire design envelope inspected");
        check(assessment.placements() == 2 && assessment.excavations() == 1, "conflicting placement needs excavation");
        check(assessment.trees() == 1 && assessment.assetConflicts() == 1, "tree and existing asset evidence");
        check(!assessment.executable(), "existing asset must not be destroyed");
        check(assessment.materials().get("minecraft:stone") == 1 && assessment.materials().size() == 2,
                "exact block bill excludes air");
        check(assessment.effortUnits() == 16 && assessment.breakTicks() == 12, "deterministic effort formula");
        var protectedAir = com.mineagent.engine.construction.SiteAssessment.inspect(d, point ->
                new com.mineagent.engine.construction.SiteAssessment.Observation("minecraft:air", true, false, false, false, true, 0), d.origin(), 13);
        check(!protectedAir.executable() && protectedAir.protectedConflicts() == 2, "placement permissions appear in site assessment");
        var unknown = com.mineagent.engine.construction.SiteAssessment.inspect(d, point -> null, d.origin(), 11);
        check(!unknown.executable() && unknown.unknown() >= 24, "unloaded cells are unknown, not free");
        var satisfied = com.mineagent.engine.construction.SiteAssessment.inspect(d, point -> {
            for (var cell : d.cells()) if (d.world(cell.position()).equals(point))
                return new com.mineagent.engine.construction.SiteAssessment.Observation(cell.block(), cell.clear(), false, false, false, false, 0);
            return world.observe(point);
        }, d.origin(), 12);
        check(satisfied.placements() == 0 && satisfied.excavations() == 0, "idempotent estimates");
    }
    public static void renderer() throws Exception {
        var base = design();
        var d = new ConstructionDesign("render", base.dimension(), base.origin(), 0, new Point(3, 3, 4), base.decisions(),
                List.of(new Cell(new Point(0, 0, 0), "minecraft:stone"),
                        new Cell(new Point(0, 2, 0), "minecraft:gold_block"),
                        new Cell(new Point(2, 0, 3), "minecraft:oak_planks"),
                        new Cell(new Point(1, 1, 2), "minecraft:glass")));
        var renderer = new com.mineagent.engine.construction.OrthographicRenderer();
        var views = renderer.render(d);
        check(views.size() == 6, "six actual views");
        var dir = workDirectory().resolve("previews"); java.nio.file.Files.createDirectories(dir);
        var repeated = renderer.render(d);
        for (int n = 0; n < views.size(); n++) {
            var preview = views.get(n);
            check(preview.width() == 256 && preview.height() == 256, "bounded render dimensions");
            check(preview.dataUrl().startsWith("data:image/png;base64,"), "local vision attachment");
            var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(preview.png()));
            check(image != null && image.getWidth() == 256, "real PNG decode");
            var colors = new java.util.HashSet<Integer>();
            for (int x = 0; x < 256; x++) for (int y = 0; y < 256; y++) colors.add(image.getRGB(x, y));
            check(colors.size() >= 4, "rendered material surfaces, not a placeholder");
            check(java.util.Arrays.equals(preview.png(), repeated.get(n).png()), "deterministic rendering");
            byte[] disposable = preview.png(); disposable[0] = 0;
            check(preview.png()[0] == (byte) 137, "preview bytes are immutable");
            java.nio.file.Files.write(dir.resolve(preview.view() + ".png"), preview.png());
        }
        check(!java.util.Arrays.equals(views.get(0).png(), views.get(1).png()), "top and bottom use opposite depth tests");
        java.nio.file.Files.writeString(dir.resolve("metadata.json"), new com.google.gson.Gson().toJson(
                java.util.Map.of("design", d, "views", views.stream().map(v -> java.util.Map.of(
                        "view", v.view(), "width", v.width(), "height", v.height(), "bytes", v.png().length,
                        "horizontal", v.horizontalAxis(), "vertical", v.verticalAxis(), "view_direction", v.viewDirection())).toList())));
    }
    public static void execution() throws Exception {
        var root = java.nio.file.Files.createTempDirectory(workDirectory(), "execution-");
        var repo = new com.mineagent.engine.construction.ConstructionRepository(root);
        var revision = repo.save(design(), 0, "execution rationale");
        var world = new java.util.HashMap<Point, com.mineagent.engine.construction.SiteAssessment.Observation>();
        var dispatched = new java.util.ArrayList<com.mineagent.engine.construction.ConstructionExecution.Batch>();
        var states = new java.util.HashMap<String, String>();
        var inventory = new java.util.HashMap<String, Integer>();
        inventory.put("minecraft:stone", 1); inventory.put("minecraft:oak_planks", 1);
        var port = new com.mineagent.engine.construction.ConstructionExecution.Port() {
            public String dimension() { return design().dimension(); }
            public Point position() { return design().origin(); }
            public long tick() { return 100; }
            public String sessionId() { return "test-session"; }
            public com.mineagent.engine.construction.SiteAssessment.Observation observe(Point p) {
                return world.getOrDefault(p, new com.mineagent.engine.construction.SiteAssessment.Observation("minecraft:air", true, false, false, false, false, 0));
            }
            public java.util.Map<String, Integer> inventory() { return inventory; }
            public String taskState(String task) { return states.getOrDefault(task, "PENDING"); }
            public void dispatch(String id, com.mineagent.engine.construction.ConstructionExecution.Batch batch) { dispatched.add(batch); }
        };
        var executor = new com.mineagent.engine.construction.ConstructionExecution(repo, port);
        var result = executor.execute(revision, false, "action-1");
        check(result.get("state").getAsString().equals("dispatched") && dispatched.size() == 1, "dispatches one physical batch");
        check(dispatched.getFirst().mode().equals("place") && dispatched.getFirst().positions().getFirst().equals(design().world(new Point(0, 0, 0))), "rotated real coordinates");
        executor.execute(revision, false, "action-2");
        check(dispatched.size() == 1, "pending work never duplicated");
        states.put("action-1", "SUCCESS");
        check(new com.mineagent.engine.construction.ConstructionSession(repo, port).taskFinished("action-1"), "task-finished lifecycle hook finds and records exact design outcome");
        var observed = executor.observe(revision);
        check(observed.get("verified").getAsInt() == 1, "task success is not fabricated world success");
        check(repo.evidence("work", 1).stream().anyMatch(e -> e.kind().equals("outcome") && e.payload().get("matched").getAsInt() == 0), "failed world postcondition recorded");
        inventory.clear();
        check(executor.execute(revision, false, "action-3").get("state").getAsString().equals("missing_materials"), "never creates materials");
        inventory.put("minecraft:stone", 1); inventory.put("minecraft:oak_planks", 1);
        world.put(design().world(new Point(0, 0, 0)), new com.mineagent.engine.construction.SiteAssessment.Observation("minecraft:dirt", false, false, false, false, false, 4));
        check(executor.execute(revision, false, "action-4").get("state").getAsString().equals("terrain_authorization_required"), "no implicit excavation");
        executor.execute(revision, true, "action-5");
        check(dispatched.getLast().mode().equals("clear"), "excavation uses physical clear batch before placement");
        states.put("action-5", "CANCELLED"); executor.observe(revision);
        check(repo.evidence("work", 1).stream().anyMatch(e -> e.kind().equals("outcome") && e.payload().get("task_state").getAsString().equals("CANCELLED")), "cancellation preserved");
    }
    public static void tool() throws Exception {
        var root = java.nio.file.Files.createTempDirectory(workDirectory(), "tool-");
        var repo = new com.mineagent.engine.construction.ConstructionRepository(root);
        var received = new java.util.ArrayList<com.mineagent.engine.construction.OrthographicRenderer.Preview>();
        var tool = new com.mineagent.tools.construction.ConstructionTool(
                player -> new com.mineagent.engine.construction.ConstructionSession(repo, null),
                (player, revision, views) -> { received.addAll(views); return true; });
        var execute = new com.mineagent.tools.construction.ExecuteConstructionTool(
                player -> new com.mineagent.engine.construction.ConstructionSession(repo, null));
        check(execute.name().equals("execute_construction") && execute.dispatchesAsyncTask(), "physical execution reserves body");
        String english = tool.description();
        com.mineagent.api.i18n.RuntimeLanguage.configure("pt_br");
        check(!tool.description().equals(english), "description follows shared runtime language");
        com.mineagent.api.i18n.RuntimeLanguage.configure("en_us");
        check(tool.parameterSchema("pt_br").containsKey("properties"), "complete localized callable schema");
        check(tool.name().equals("construction") && !tool.dispatchesAsyncTask(), "discoverable query/design tool does not reserve body");
        check(tool.description("pt_br").contains("constru"), "Portuguese description");
        check(!tool.description("pt_br").equals(tool.description("en_us")), "localized descriptions");
        java.util.function.Function<com.google.gson.JsonObject, com.google.gson.JsonObject> call = args -> {
            var reply = new java.util.concurrent.atomic.AtomicReference<String>();
            tool.onServerCall("call", args, null, reply::set);
            return com.google.gson.JsonParser.parseString(reply.get()).getAsJsonObject();
        };
        var args = new com.google.gson.JsonObject(); args.addProperty("mode", "revise");
        args.addProperty("expected_revision", 0); args.addProperty("reason", "rationale");
        args.add("design", new com.google.gson.Gson().toJsonTree(design()));
        check(call.apply(args).get("revision").getAsInt() == 1, "actual tool stores a versioned design");
        args = new com.google.gson.JsonObject(); args.addProperty("mode", "preview"); args.addProperty("id", "work");
        check(call.apply(args).get("vision_attached").getAsBoolean() && received.size() == 6, "actual tool delivers six images through optional vision hook");
        args.addProperty("mode", "read"); args.addProperty("offset", 1); args.addProperty("limit", 1);
        var page = call.apply(args);
        check(page.has("total_materials") && page.getAsJsonObject("total_materials").get("minecraft:stone").getAsInt() == 1, "tool discloses complete deterministic bill without full geometry");
        check(page.getAsJsonArray("cells").size() == 1 && page.get("cell_count").getAsInt() == 3, "progressive bounded design read");
        var malformed = new com.google.gson.Gson().toJsonTree(design()).getAsJsonObject();
        malformed.addProperty("quarterTurns", 1.5);
        var invalid = new com.google.gson.JsonObject(); invalid.addProperty("mode", "revise");
        invalid.addProperty("expected_revision", 1); invalid.addProperty("reason", "fractional transform"); invalid.add("design", malformed);
        check(!call.apply(invalid).get("success").getAsBoolean(), "fractional coordinates cannot be truncated by Gson");
        check(repo.read("work", 0).version() == 1, "invalid tool call has no persistence side effect");
    }
    public static void boundedBatchesAndRestart() throws Exception {
        var cells = new java.util.ArrayList<Cell>();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) cells.add(new Cell(new Point(x, 0, z), "minecraft:stone"));
        var d = new ConstructionDesign("large", design().dimension(), design().origin(), 0, new Point(16, 1, 16), design().decisions(), cells);
        var repo = new com.mineagent.engine.construction.ConstructionRepository(java.nio.file.Files.createTempDirectory(workDirectory(), "batch-"));
        var revision = repo.save(d, 0, "batch rationale");
        var port = new TestPort(); port.carried.put("minecraft:stone", 256);
        var executor = new com.mineagent.engine.construction.ConstructionExecution(repo, port);
        executor.execute(revision, false, "batch-1");
        check(port.batches.getFirst().positions().size() == 128, "physical batch bound");
        port.session = "restarted";
        executor.observe(revision);
        check(repo.evidence("large", 1).stream().anyMatch(e -> e.kind().equals("outcome")
                && e.payload().get("task_state").getAsString().equals("interrupted_restart")), "restart records interruption rather than inventing task completion");
        executor.execute(revision, false, "batch-2");
        var batch = port.batches.getLast();
        for (var point : batch.positions()) port.world.put(point, new com.mineagent.engine.construction.SiteAssessment.Observation("minecraft:stone", false, false, false, false, false, 10));
        port.carried.put("minecraft:stone", 128); port.states.put("batch-2", "SUCCESS");
        executor.execute(revision, false, "batch-3");
        check(port.batches.size() == 3 && port.batches.getLast().positions().size() == 128, "resume uses remaining world cells only");
        for (var point : port.batches.getLast().positions()) port.world.put(point, new com.mineagent.engine.construction.SiteAssessment.Observation("minecraft:stone", false, false, false, false, false, 10));
        port.states.put("batch-3", "SUCCESS");
        check(executor.observe(revision).get("state").getAsString().equals("verified"), "final result requires all observed cells");
        var tool = new com.mineagent.tools.construction.ConstructionTool(player -> new com.mineagent.engine.construction.ConstructionSession(repo, port), null);
        var args = new com.google.gson.JsonObject(); args.addProperty("mode", "assess"); args.addProperty("id", "large");
        var candidates = new com.google.gson.JsonArray();
        for (int x : new int[]{10, 50}) {
            var candidate = new com.google.gson.JsonObject(); candidate.addProperty("dimension", d.dimension()); candidate.addProperty("quarterTurns", 0);
            candidate.add("origin", new com.google.gson.Gson().toJsonTree(new Point(x, 64, 20))); candidates.add(candidate);
        }
        args.add("candidates", candidates); var reply = new java.util.concurrent.atomic.AtomicReference<String>();
        tool.onServerCall("site-query", args, null, reply::set);
        var assessment = com.google.gson.JsonParser.parseString(reply.get()).getAsJsonObject();
        check(assessment.getAsJsonArray("candidates").size() == 2, "actual tool compares explicit candidate sites");
        check(repo.evidence("large", 1).getLast().kind().equals("site_assessment"), "candidate evidence persists with exact revision");
        args.addProperty("mode", "preview");
        tool.onServerCall("preview-query", args, null, reply::set);
        check(!com.google.gson.JsonParser.parseString(reply.get()).getAsJsonObject().get("vision_attached").getAsBoolean(), "visual model remains optional and disabled without hook");
    }
    private static final class TestPort implements com.mineagent.engine.construction.ConstructionExecution.Port {
        final java.util.Map<Point, com.mineagent.engine.construction.SiteAssessment.Observation> world = new java.util.HashMap<>();
        final java.util.Map<String, Integer> carried = new java.util.HashMap<>();
        final java.util.Map<String, String> states = new java.util.HashMap<>();
        final java.util.List<com.mineagent.engine.construction.ConstructionExecution.Batch> batches = new java.util.ArrayList<>();
        String session = "initial";
        public String dimension() { return design().dimension(); }
        public Point position() { return design().origin(); }
        public long tick() { return 100; }
        public String sessionId() { return session; }
        public java.util.Map<String, Integer> inventory() { return carried; }
        public String taskState(String id) { return states.getOrDefault(id, "PENDING"); }
        public void dispatch(String id, com.mineagent.engine.construction.ConstructionExecution.Batch batch) { batches.add(batch); }
        public com.mineagent.engine.construction.SiteAssessment.Observation observe(Point p) {
            return world.getOrDefault(p, new com.mineagent.engine.construction.SiteAssessment.Observation("minecraft:air", true, false, false, false, false, 0));
        }
    }
    public static void bridgeIdentity() {
        var owner = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
        var key = com.mineagent.engine.construction.MinecraftConstructionBridge.storageKey(owner, "../companion");
        check(!key.contains("/") && !key.contains("companion"), "stable storage key cannot escape world directory or leak name");
        check(key.equals(com.mineagent.engine.construction.MinecraftConstructionBridge.storageKey(owner, "../companion")), "restart identity stable");
        check(!key.equals(com.mineagent.engine.construction.MinecraftConstructionBridge.storageKey(owner, ".._companion")), "sanitization collisions avoided");
    }
    private static java.nio.file.Path workDirectory() throws java.io.IOException {
        var root = java.nio.file.Path.of("").toAbsolutePath();
        if (!java.nio.file.Files.isRegularFile(root.resolve("AGENTS.md"))
                && root.getParent() != null && java.nio.file.Files.isRegularFile(root.getParent().resolve("AGENTS.md"))) root = root.getParent();
        var directory = root.resolve(".tools/construction-work");
        java.nio.file.Files.createDirectories(directory); return directory;
    }
    public static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void rejects(Runnable operation) {
        try { operation.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected bounded validation to reject input");
    }
    public static void main(String[] args) throws Exception { geometry(); persistence(); assessment(); renderer(); execution(); tool(); bridgeIdentity(); System.out.println("PASS geometry persistence assessment renderer execution tool bridgeIdentity"); }
}
