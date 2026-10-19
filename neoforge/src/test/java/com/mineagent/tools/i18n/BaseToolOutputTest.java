package com.mineagent.tools.i18n;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.i18n.Language;
import com.mineagent.api.i18n.MessageCatalog;
import com.mineagent.api.i18n.RuntimeLanguage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class BaseToolOutputTest {
    @AfterEach void restoreLanguage() { RuntimeLanguage.configure("en_us"); }

    @ParameterizedTest
    @CsvSource({
        "InteractAtTool,button", "block.AutoMineTool,block_type", "block.BuildTool,mode",
        "LocateBiomeTool,biome_type", "LocateStructureTool,structure_type",
        "management.TaskStatusTool,task_id", "management.TaskStopTool,task_id",
        "skill.ExecuteSkillTool,skill_name", "skill.LoadSkillTool,skill_name"
    })
    void requiredArgumentErrorsComeFromActualTool(String type, String parameter) throws Exception {
        Tool tool = (Tool) Class.forName("com.mineagent.tools." + type).getConstructor().newInstance();
        RuntimeLanguage.configure("pt_br");
        AtomicReference<String> reply = new AtomicReference<>();
        tool.onServerCall("call:quoted", new JsonObject(), null, reply::set);
        String error = JsonParser.parseString(reply.get()).getAsJsonObject().get("error").getAsString();
        assertTrue(error.contains("Parâmetro obrigatório ausente"), error);
        assertTrue(error.contains(parameter), error);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "crafting.LookupRecipeTool|{}|Item desconhecido: null",
        "planning.PlanAcquisitionTool|{}|Item registrado desconhecido: null",
        "block.BuildTool|{\"mode\":\"place\"}|Tipo de bloco desconhecido: null"
    })
    void missingValuesRemainErrorsRatherThanNullPointerExceptions(String type, String input, String expected) throws Exception {
        RuntimeLanguage.configure("pt_br");
        Tool tool = (Tool) Class.forName("com.mineagent.tools." + type).getConstructor().newInstance();
        AtomicReference<String> reply = new AtomicReference<>();
        tool.onServerCall("call", JsonParser.parseString(input).getAsJsonObject(), null, reply::set);
        assertEquals(expected, JsonParser.parseString(reply.get()).getAsJsonObject().get("error").getAsString());
    }

    @Test void formattingPreservesArbitraryArgumentsWithoutReinterpretingThem() {
        RuntimeLanguage.configure("pt_br");
        String input = "mod:Item_Name \"player text\" {0} \\ $1\nno translation";
        assertEquals("Item desconhecido: " + input, ToolMessages.text("error.unknown_item", input));
        RuntimeLanguage.configure("en_us");
        assertEquals("Unknown item: " + input, ToolMessages.text("error.unknown_item", input));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"en_us", "pt_br"})
    void taskLedgerKeepsStoredTextAndTechnicalIdentifiersVerbatim(String language) {
        RuntimeLanguage.configure(language);
        var player = new LedgerPlayer();
        String taskId = "call:original \"quoted\" {0} \\ $1\n";
        String storedMessage = "Existing stored prose is evidence, not a translation key: {0}";
        JsonObject storedResult = new JsonObject();
        storedResult.addProperty("player_text", "Owner's original words: não traduzir / do not translate");
        com.mineagent.tools.management.TaskStatusTool.updateTaskInfo(player.companionId(), taskId,
                "mod:Original_Tool", com.mineagent.api.task.TaskState.SUCCESS,
                storedMessage, storedResult.toString(), 17);
        try {
            JsonObject args = new JsonObject(); args.addProperty("task_id", taskId);
            AtomicReference<String> reply = new AtomicReference<>();
            new com.mineagent.tools.management.TaskStatusTool().onServerCall("query", args, player, reply::set);
            JsonObject result = JsonParser.parseString(reply.get()).getAsJsonObject();
            assertEquals(taskId, result.get("task_id").getAsString());
            assertEquals("mod:Original_Tool", result.get("tool_name").getAsString());
            assertEquals("SUCCESS", result.get("state").getAsString());
            assertEquals(storedMessage, result.get("message").getAsString());
            assertEquals(storedResult, result.get("result"));
        } finally {
            com.mineagent.tools.management.TaskStatusTool.removeTaskInfo(player.companionId(), taskId);
        }
    }

    /** Real ledger calls need only an identity, not a Minecraft server or mocked ledger. */
    private static final class LedgerPlayer extends com.mineagent.api.entity.AgentPlayer {
        private final java.util.UUID id = java.util.UUID.randomUUID();
        @Override public java.util.UUID companionId() { return id; }
        @Override public java.util.UUID ownerUuid() { return id; }
        @Override public String companionName() { return "observed companion"; }
        @Override public String ownerName() { return "observed owner"; }
        @Override public String blockPositionStr() { return "0,64,0"; }
        @Override public double posX() { return 0; }
        @Override public double posY() { return 64; }
        @Override public double posZ() { return 0; }
        @Override public float health() { return 20; }
        @Override public float maxHealth() { return 20; }
        @Override public int foodLevel() { return 20; }
        @Override public int airSupply() { return 300; }
        @Override public boolean isInWater() { return false; }
        @Override public boolean isAlive() { return true; }
        @Override public void holdInHand(int slot) { throw new UnsupportedOperationException(); }
        @Override public String mainHandItemId() { return "minecraft:air"; }
        @Override public String dimensionKey() { return "minecraft:overworld"; }
    }

    @Test void everyRequiredCatalogEntryHasPortugueseAndCannotSilentlyFallback() {
        Map<String,String> en = new LinkedHashMap<>(), pt = new LinkedHashMap<>();
        for (String key : ToolMessages.CATALOG.keys()) {
            // Required output keys and all metadata are looked up through the real strict catalog.
            // Resolve templates with harmless literal arguments according to the public contract.
            int count = 0;
            for (;;) {
                try {
                    Object[] args = new Object[count]; java.util.Arrays.fill(args, "literal");
                    en.put(key, ToolMessages.CATALOG.text(Language.EN_US, key, args));
                    pt.put(key, ToolMessages.CATALOG.text(Language.PT_BR, key, args));
                    break;
                } catch (IllegalArgumentException mismatch) {
                    if (++count > 8) throw mismatch;
                }
            }
        }
        assertTrue(en.containsKey("error.unknown_item"));
        assertTrue(en.containsKey("output.container_closed"));
        pt.remove("error.unknown_item");
        assertThrows(IllegalArgumentException.class,
                () -> new MessageCatalog(Map.of(Language.EN_US, en, Language.PT_BR, pt)));
        assertThrows(IllegalArgumentException.class,
                () -> ToolMessages.text("not.a.real.translation.key"));
    }
}
