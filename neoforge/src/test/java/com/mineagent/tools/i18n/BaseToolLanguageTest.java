package com.mineagent.tools.i18n;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.i18n.RuntimeLanguage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises real tool instances and recursively checks nested schema prose, not source patterns. */
class BaseToolLanguageTest {
    @AfterEach void restoreLanguage() { RuntimeLanguage.configure("en_us"); }

    @ParameterizedTest
    @ValueSource(strings = {
        "CloseGuiTool", "GetOwnerStatusTool", "GetWorldInfoTool", "InspectGuiTool",
        "InteractAtTool", "LocateBiomeTool", "LocateStructureTool",
        "block.AutoMineTool", "block.BuildTool", "block.InspectBlockStorageTool", "block.InspectBlockTool",
        "crafting.LookupRecipeTool", "inventory.EatItemTool", "inventory.UseItemTool",
        "management.CoordinateTeamTool", "management.QueryExtraToolsTool", "management.TaskStatusTool",
        "management.TaskStopTool", "management.TodowriteTool", "management.WaitForTool", "movement.MoveToTool",
        "perception.GetSelfStatusTool", "perception.LookAroundTool", "perception.RecallMemoryTool",
        "perception.ScanBlocksTool", "perception.ScanNearbyEntitiesTool", "planning.ExploreMechanismTool",
        "planning.PlanAcquisitionTool", "planning.ResolveNeedTool", "skill.ExecuteSkillTool",
        "skill.LearnedSkillsTool", "skill.LoadSkillTool"
    })
    void metadataChangesAtRuntimeWithoutChangingTechnicalContract(String className) throws Exception {
        Tool tool = (Tool) Class.forName("com.mineagent.tools." + className).getConstructor().newInstance();
        RuntimeLanguage.configure("en_us");
        String name = tool.name();
        String english = tool.description();
        JsonObject englishSchema = new Gson().toJsonTree(tool.parameterSchema()).getAsJsonObject();
        RuntimeLanguage.configure("pt_br");
        assertEquals(name, tool.name());
        assertNotEquals(english, tool.description(), className + " description must be translated");
        assertFalse(tool.description().isBlank());
        JsonObject portugueseSchema = new Gson().toJsonTree(tool.parameterSchema()).getAsJsonObject();
        assertTechnicalContractAndProse(englishSchema, portugueseSchema, className);
        RuntimeLanguage.configure("en_us");
        assertEquals(english, tool.description(), "No cached Portuguese after language reload");
        assertEquals(englishSchema, new Gson().toJsonTree(tool.parameterSchema()));
    }

    private static void assertTechnicalContractAndProse(JsonElement en, JsonElement pt, String path) {
        if (en.isJsonObject()) {
            assertEquals(en.getAsJsonObject().keySet(), pt.getAsJsonObject().keySet(), path);
            for (var entry : en.getAsJsonObject().entrySet()) {
                String key = entry.getKey();
                JsonElement translated = pt.getAsJsonObject().get(key);
                if ((key.equals("description") || key.equals("title")) && entry.getValue().isJsonPrimitive()
                        && entry.getValue().getAsJsonPrimitive().isString()) {
                    assertNotEquals(entry.getValue(), translated, path + "/" + key);
                } else {
                    assertTechnicalContractAndProse(entry.getValue(), translated, path + "/" + key);
                }
            }
        } else if (en.isJsonArray()) {
            assertEquals(en.getAsJsonArray().size(), pt.getAsJsonArray().size(), path);
            for (int i = 0; i < en.getAsJsonArray().size(); i++)
                assertTechnicalContractAndProse(en.getAsJsonArray().get(i), pt.getAsJsonArray().get(i), path + "/" + i);
        } else {
            assertEquals(en, pt, path + " is technical data");
        }
    }
}
