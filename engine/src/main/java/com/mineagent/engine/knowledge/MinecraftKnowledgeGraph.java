package com.mineagent.engine.knowledge;

import java.util.*;

/**
 * HDKG — directed graph of Minecraft crafting rules and tool dependencies.
 *
 * <p>Inspired by the Hierarchical Directed Knowledge Graph in Optimus-1 (NeurIPS 2024).
 * Explicitly retrieve crafting rules during planning to avoid hallucinated recipes.
 *
 * <p>This version contains static knowledge of core Minecraft crafting chains:
 * <ul>
 *   <li>Logs → planks → sticks → tools</li>
 *   <li>Cobblestone → stone tools → iron ore → iron ingots → iron tools</li>
 *   <li>Iron ore → iron ingots → shields/armor</li>
 *   <li>Diamonds → diamond tools → Nether</li>
 * </ul>
 */
public class MinecraftKnowledgeGraph {

    /**
     * A crafting rule.
     *
     * @param inputs input items (item name → quantity)
     * @param output output item
     * @param outputCount output quantity
     * @param station crafting station (crafting_table / furnace / smithing_table)
     */
    public record Recipe(
            Map<String, Integer> inputs,
            String output,
            int outputCount,
            String station
    ) {}

    /** Item → recipes that produce it. */
    private final Map<String, List<Recipe>> outputIndex = new HashMap<>();

    /** Item → recipes that consume it (reverse index for what a material can make). */
    private final Map<String, List<Recipe>> inputIndex = new HashMap<>();

    public MinecraftKnowledgeGraph() {
        initCoreRecipes();
    }

    /**
     * Find crafting recipes for an item.
     *
     * @param outputItem target item name
     * @return all available recipes, or an empty list
     */
    public List<Recipe> findRecipes(String outputItem) {
        return outputIndex.getOrDefault(outputItem.toLowerCase(), Collections.emptyList());
    }

    /**
     * Find what can be crafted from a material.
     *
     * @param inputItem input material name
     * @return all recipes using the material
     */
    public List<Recipe> findUses(String inputItem) {
        return inputIndex.getOrDefault(inputItem.toLowerCase(), Collections.emptyList());
    }

    /**
     * Plan a crafting chain from the current inventory to the target item.
     *
     * @param target target item
     * @param inventory current inventory (item name → quantity)
     * @return crafting steps starting with basic materials, or null if crafting is impossible
     */
    public List<Recipe> planCraftingChain(String target, Map<String, Integer> inventory) {
        List<Recipe> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        if (planCraftingChainRecursive(target, 1, inventory, chain, visited)) {
            // Start with basic materials (chain was collected in reverse order)
            Collections.reverse(chain);
            return chain;
        }
        return null;
    }

    /**
     * Generate a knowledge summary for the LLM.
     */
    public String summarizeForPrompt() {
        return """
            ## Minecraft crafting knowledge (known rules)
            - Logs → 4 planks (crafting table)
            - 2 planks → 4 sticks (crafting table)
            - 3 planks → 1 crafting table (crafting table)
            - 2 sticks + 3 planks/cobblestone/iron ingots/diamonds → corresponding tool (crafting table)
            - Stick + 5 iron ingots → shield (crafting table)
            - Iron ore → iron ingot (furnace + fuel)
            - Gold ore → gold ingot (furnace + fuel)
            - Cobblestone → furnace (crafting table, 8 cobblestone)
            - Sticks + string → fishing rod
            - Stick + feather + flint → arrows
            - Diamonds + obsidian + book → enchanting table
            When crafting is needed, consult these rules first instead of inventing recipes.
            """;
    }

    // ── Internal methods ──

    private boolean planCraftingChainRecursive(String target, int count,
                                                 Map<String, Integer> inventory,
                                                 List<Recipe> chain,
                                                 Set<String> visited) {
        if (visited.contains(target)) return false;
        visited.add(target);

        // Check whether the inventory already has enough
        int have = inventory.getOrDefault(target.toLowerCase(), 0);
        if (have >= count) return true;

        // Find a recipe
        List<Recipe> recipes = findRecipes(target);
        if (recipes.isEmpty()) return false; // Cannot craft

        Recipe recipe = recipes.get(0); // Use the first recipe
        int needBatches = (int) Math.ceil((double) (count - have) / recipe.outputCount());

        // Recursively check all input materials
        for (var entry : recipe.inputs().entrySet()) {
            String input = entry.getKey();
            int needed = entry.getValue() * needBatches;
            int haveInput = inventory.getOrDefault(input.toLowerCase(), 0);
            if (haveInput < needed) {
                if (!planCraftingChainRecursive(input, needed - haveInput, inventory, chain, visited)) {
                    return false; // An input material cannot be obtained
                }
            }
        }

        chain.add(recipe);
        return true;
    }

    private void addRecipe(Map<String, Integer> inputs, String output,
                            int outputCount, String station) {
        Recipe recipe = new Recipe(inputs, output, outputCount, station);
        outputIndex.computeIfAbsent(output.toLowerCase(), k -> new ArrayList<>()).add(recipe);
        for (String input : inputs.keySet()) {
            inputIndex.computeIfAbsent(input.toLowerCase(), k -> new ArrayList<>()).add(recipe);
        }
    }

    private void initCoreRecipes() {
        // Wood chain
        addRecipe(Map.of("oak_log", 1), "oak_planks", 4, "inventory");
        addRecipe(Map.of("oak_planks", 2), "stick", 4, "inventory");
        addRecipe(Map.of("oak_planks", 4), "crafting_table", 1, "inventory");

        // Tool chain
        addRecipe(Map.of("stick", 2, "oak_planks", 3), "wooden_pickaxe", 1, "crafting_table");
        addRecipe(Map.of("stick", 2, "oak_planks", 3), "wooden_axe", 1, "crafting_table");
        addRecipe(Map.of("stick", 2, "cobblestone", 3), "stone_pickaxe", 1, "crafting_table");
        addRecipe(Map.of("stick", 2, "cobblestone", 3), "stone_axe", 1, "crafting_table");
        addRecipe(Map.of("stick", 2, "iron_ingot", 3), "iron_pickaxe", 1, "crafting_table");
        addRecipe(Map.of("stick", 2, "iron_ingot", 3), "iron_axe", 1, "crafting_table");
        addRecipe(Map.of("stick", 2, "diamond", 3), "diamond_pickaxe", 1, "crafting_table");
        addRecipe(Map.of("stick", 2, "diamond", 3), "diamond_axe", 1, "crafting_table");

        // Weapons
        addRecipe(Map.of("stick", 1, "oak_planks", 2), "wooden_sword", 1, "crafting_table");
        addRecipe(Map.of("stick", 1, "cobblestone", 2), "stone_sword", 1, "crafting_table");
        addRecipe(Map.of("stick", 1, "iron_ingot", 2), "iron_sword", 1, "crafting_table");
        addRecipe(Map.of("stick", 1, "diamond", 2), "diamond_sword", 1, "crafting_table");

        // Armor
        addRecipe(Map.of("iron_ingot", 8), "iron_chestplate", 1, "crafting_table");
        addRecipe(Map.of("iron_ingot", 5), "iron_helmet", 1, "crafting_table");
        addRecipe(Map.of("iron_ingot", 7), "iron_leggings", 1, "crafting_table");
        addRecipe(Map.of("iron_ingot", 4), "iron_boots", 1, "crafting_table");
        addRecipe(Map.of("diamond", 8), "diamond_chestplate", 1, "crafting_table");

        // Defensive equipment
        addRecipe(Map.of("iron_ingot", 6, "oak_planks", 1), "shield", 1, "crafting_table");

        // Smelting
        addRecipe(Map.of("raw_iron", 1), "iron_ingot", 1, "furnace");
        addRecipe(Map.of("raw_gold", 1), "gold_ingot", 1, "furnace");
        addRecipe(Map.of("raw_copper", 1), "copper_ingot", 1, "furnace");
        addRecipe(Map.of("sand", 1), "glass", 1, "furnace");

        // Furnace
        addRecipe(Map.of("cobblestone", 8), "furnace", 1, "crafting_table");

        // Torches
        addRecipe(Map.of("stick", 1, "coal", 1), "torch", 4, "inventory");

        // Food
        addRecipe(Map.of("wheat", 3), "bread", 1, "inventory");
        addRecipe(Map.of("wheat", 2, "egg", 1), "cake", 1, "crafting_table");

        // Basic redstone
        addRecipe(Map.of("redstone", 1, "stick", 1), "redstone_torch", 1, "crafting_table");
        addRecipe(Map.of("cobblestone", 3, "redstone", 1), "repeater", 1, "crafting_table");

        // Enchanting table
        addRecipe(Map.of("diamond", 2, "obsidian", 4, "book", 1), "enchanting_table", 1, "crafting_table");
        addRecipe(Map.of("leather", 1, "paper", 3), "book", 1, "crafting_table");
    }
}
