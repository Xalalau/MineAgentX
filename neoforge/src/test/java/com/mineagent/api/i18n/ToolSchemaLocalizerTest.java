package com.mineagent.api.i18n;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ToolSchemaLocalizerTest {
    @Test
    void literalSchemaValuesAndPropertiesNamedLikeKeywordsAreNotConfusedWithMetadata() {
        JsonObject definition = JsonParser.parseString("""
                {"name":"store","parameters":{"properties":{
                    "enum":{"type":"string","description":"A field named enum."}},
                 "default":{"description":"verbatim data"},
                 "enum":[{"title":"verbatim title"}],
                 "allOf":[{"title":"Constraint"}]}}
                """).getAsJsonObject();
        MessageCatalog catalog = new MessageCatalog(Map.of(
                Language.EN_US, Map.of("field", "A field named enum.", "constraint", "Constraint"),
                Language.PT_BR, Map.of("field", "Um campo chamado enum.", "constraint", "Restrição")));
        Map<String, String> pointers = Map.of("/parameters/properties/enum/description", "field",
                "/parameters/allOf/0/title", "constraint");
        JsonObject localized = ToolSchemaLocalizer.localize(Language.PT_BR, definition, pointers, catalog);
        assertEquals("verbatim data", localized.getAsJsonObject("parameters")
                .getAsJsonObject("default").get("description").getAsString());
        assertEquals("Um campo chamado enum.", localized.getAsJsonObject("parameters")
                .getAsJsonObject("properties").getAsJsonObject("enum").get("description").getAsString());
        var illegal = new java.util.HashMap<>(pointers);
        illegal.put("/name", "field");
        assertThrows(IllegalArgumentException.class,
                () -> ToolSchemaLocalizer.localize(Language.PT_BR, definition, illegal, catalog));
    }

    @Test
    void onlyDeclaredHumanMetadataIsLocalizedWhileTechnicalValuesAndInputsStayIdentical() {
        JsonObject definition = JsonParser.parseString("""
                {"name":"inspect","description":"Inspect current evidence.","parameters":{
                 "type":"object","properties":{"item/id":{"type":"string","description":"Registry ID.",
                    "enum":["minecraft:stone"],"default":"minecraft:stone"},
                    "description":{"type":"string","title":"Stored text"}},
                 "required":["item/id"],"additionalProperties":false}}
                """).getAsJsonObject();
        JsonObject original = definition.deepCopy();
        MessageCatalog catalog = new MessageCatalog(Map.of(
                Language.EN_US, Map.of("inspect.description", "Inspect current evidence.",
                        "inspect.item", "Registry ID.", "inspect.title", "Stored text"),
                Language.PT_BR, Map.of("inspect.description", "Inspecione as evidências atuais.",
                        "inspect.item", "ID de registro.", "inspect.title", "Texto armazenado")));
        Map<String, String> pointers = Map.of("/description", "inspect.description",
                "/parameters/properties/item~1id/description", "inspect.item",
                "/parameters/properties/description/title", "inspect.title");
        JsonObject localized = ToolSchemaLocalizer.localize(Language.PT_BR, definition, pointers, catalog);
        assertEquals(original, definition);
        assertEquals("inspect", localized.get("name").getAsString());
        assertEquals("Inspecione as evidências atuais.", localized.get("description").getAsString());
        var item = localized.getAsJsonObject("parameters").getAsJsonObject("properties").getAsJsonObject("item/id");
        assertEquals("ID de registro.", item.remove("description").getAsString());
        JsonObject originalItem = original.getAsJsonObject("parameters").getAsJsonObject("properties").getAsJsonObject("item/id");
        originalItem.remove("description");
        assertEquals(originalItem, item);
        assertEquals("Texto armazenado", localized.getAsJsonObject("parameters")
                .getAsJsonObject("properties").getAsJsonObject("description").get("title").getAsString());
        assertThrows(IllegalArgumentException.class,
                () -> ToolSchemaLocalizer.localize(Language.PT_BR, definition, Map.of(), catalog));
        assertThrows(IllegalArgumentException.class,
                () -> ToolSchemaLocalizer.localize(Language.PT_BR, definition, Map.of("/description", "absent"), catalog));
    }
}
