package com.mineagent.inventory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mineagent.api.i18n.RuntimeLanguage;
import com.mineagent.tools.inventory.*;
import com.mineagent.tools.crafting.CraftTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class InventoryToolLocaleTest {
    @AfterEach void resetLanguage() { RuntimeLanguage.configure("en_us"); }

    @Test void portugueseMetadataUsesSharedRuntimeLanguage() {
        RuntimeLanguage.configure("pt_br");
        assertTrue(new TransferItemsTool().description().contains("inventário"));
        assertTrue(new DropItemsTool().description().contains("inventário"));
        assertTrue(new EquipItemTool().description().contains("equipamento"));
        assertTrue(new CollectItemsTool().description().contains("itens"));
        assertTrue(new CraftTool().description().contains("receita"));
        assertTrue(new CraftTool().parameterSchema().toString().contains("lotes"));
    }

    @Test void everyOwnedSchemaDescriptionSwitchesWithoutChangingTechnicalFields() {
        var tools = java.util.List.<com.mineagent.api.agent.tool.Tool>of(new TransferItemsTool(),
                new DropItemsTool(), new EquipItemTool(), new CollectItemsTool(), new CraftTool());
        for (var tool : tools) {
            RuntimeLanguage.configure("en_us");
            String english = tool.description();
            var englishSchema = new com.google.gson.Gson().toJsonTree(tool.parameterSchema()).getAsJsonObject();
            RuntimeLanguage.configure("pt_br");
            assertNotEquals(english, tool.description(), tool.name());
            assertFalse(tool.description().contains(" / "), tool.name());
            var portugueseSchema = new com.google.gson.Gson().toJsonTree(tool.parameterSchema()).getAsJsonObject();
            var enProperties = englishSchema.getAsJsonObject("properties");
            var ptProperties = portugueseSchema.getAsJsonObject("properties");
            assertEquals(enProperties.keySet(), ptProperties.keySet());
            for (String key : enProperties.keySet()) {
                var enProperty = enProperties.getAsJsonObject(key).deepCopy();
                var ptProperty = ptProperties.getAsJsonObject(key).deepCopy();
                assertNotEquals(enProperty.remove("description"), ptProperty.remove("description"), tool.name() + ":" + key);
                assertEquals(enProperty, ptProperty, tool.name() + ":" + key);
            }
        }
    }

    @Test void everyOwnedEarlyErrorSwitchesWithSharedLanguage() {
        var tools = java.util.List.<com.mineagent.api.agent.tool.Tool>of(new TransferItemsTool(),
                new DropItemsTool(), new EquipItemTool(), new CollectItemsTool(), new CraftTool());
        for (var tool : tools) {
            JsonObject args = new JsonObject();
            if (tool instanceof CollectItemsTool) args.addProperty("radius", 0);
            AtomicReference<String> response = new AtomicReference<>();
            RuntimeLanguage.configure("en_us");
            tool.onServerCall("test", args, null, response::set);
            String english = JsonParser.parseString(response.get()).getAsJsonObject().get("error").getAsString();
            RuntimeLanguage.configure("pt_br");
            tool.onServerCall("test", args, null, response::set);
            String portuguese = JsonParser.parseString(response.get()).getAsJsonObject().get("error").getAsString();
            assertNotEquals(english, portuguese, tool.name());
            assertFalse(portuguese.contains(" / "), tool.name());
        }
    }

    @Test void portugueseErrorsKeepMachineFieldsAndTechnicalIds() {
        RuntimeLanguage.configure("pt_br");
        AtomicReference<String> response = new AtomicReference<>();
        new TransferItemsTool().onServerCall("test", new JsonObject(), null, response::set);
        JsonObject result = JsonParser.parseString(response.get()).getAsJsonObject();
        assertTrue(result.has("error"));
        assertTrue(result.get("error").getAsString().contains("inteiro"));
        assertTrue(result.get("error").getAsString().contains("from_slot"));
    }
}
