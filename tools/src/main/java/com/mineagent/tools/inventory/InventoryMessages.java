package com.mineagent.tools.inventory;

import com.mineagent.api.i18n.Language;
import com.mineagent.api.i18n.MessageCatalog;
import com.mineagent.api.i18n.RuntimeLanguage;
import java.util.LinkedHashMap;
import java.util.Map;

/** Inventory/crafting catalog; selection is exclusively server RuntimeLanguage. */
public final class InventoryMessages {
    private InventoryMessages() {}
    private static final MessageCatalog CATALOG = createCatalog();
    public static String t(String key) { return CATALOG.text(RuntimeLanguage.language(), key); }

    private static MessageCatalog createCatalog() {
        Map<String, String> english = new LinkedHashMap<>();
        Map<String, String> portuguese = new LinkedHashMap<>();
        String[][] entries = {
        {"Craft delivered, but a recipe notification failed; no further batches were attempted.", "Craft delivered, but a recipe notification failed; no further batches were attempted.", "A fabricação foi entregue, mas uma notificação da receita falhou; nenhum outro lote foi tentado."},
        {"Source slot index", "Source slot index", "Índice do slot de origem"},
        {"Destination slot index", "Destination slot index", "Índice do slot de destino"},
        {"Items to move; default is the full source stack", "Items to move; default is the full source stack", "Itens a mover; por padrão, a pilha inteira da origem"},
        {"Source: player or container (default player)", "Source: player or container (default player)", "Origem: player ou container (padrão player)"},
        {"Destination: player or container (default player)", "Destination: player or container (default player)", "Destino: player ou container (padrão player)"},
        {"from_slot must be an integer", "from_slot must be an integer", "from_slot deve ser um inteiro"},
        {"to_slot must be an integer", "to_slot must be an integer", "to_slot deve ser um inteiro"},
        {"count must be an integer from 1 to 64", "count must be an integer from 1 to 64", "count deve ser um inteiro de 1 a 64"},
        {"source and destination must be 'player' or 'container'", "source and destination must be 'player' or 'container'", "source e destination devem ser 'player' ou 'container'"},
        {"No container GUI is currently open", "No container GUI is currently open", "Nenhuma interface de contêiner está aberta"},
        {"Source slot ", "Source slot ", "Slot de origem "},
        {"Destination slot ", "Destination slot ", "Slot de destino "},
        {" is out of range", " is out of range", " está fora do intervalo"},
        {"No items moved; slot rules or capacity refused the operation.", "No items moved; slot rules or capacity refused the operation.", "Nenhum item movido; regras dos slots ou capacidade impediram a operação."},
        {"Item ID to drop (e.g. 'minecraft:cobblestone')", "Item ID to drop (e.g. 'minecraft:cobblestone')", "ID do item a descartar"},
        {"Number of items to drop (1-64)", "Number of items to drop (1-64)", "Quantidade de itens a descartar (1-64)"},
        {"Missing required parameter 'item_id'.", "Missing required parameter 'item_id'.", "Parâmetro obrigatório 'item_id' ausente."},
        {"Missing required parameter 'count'.", "Missing required parameter 'count'.", "Parâmetro obrigatório 'count' ausente."},
        {"Unknown item: ", "Unknown item: ", "Item desconhecido: "},
        {"'count' must be an integer from 1 to ", "'count' must be an integer from 1 to ", "'count' deve ser um inteiro de 1 a "},
        {"No matching items could be dropped.", "No matching items could be dropped.", "Nenhum item correspondente pôde ser descartado."},
        {"Registered item ID to equip", "Registered item ID to equip", "ID registrado do item a equipar"},
        {"head, chest, legs, feet, offhand, or hotbar_1-9", "head, chest, legs, feet, offhand, or hotbar_1-9", "head, chest, legs, feet, offhand ou hotbar_1-9"},
        {"Missing required parameter 'slot'", "Missing required parameter 'slot'", "Parâmetro obrigatório 'slot' ausente"},
        {"Unknown slot '", "Unknown slot '", "Slot desconhecido '"},
        {"'; use head/chest/legs/feet/offhand/hotbar_1-9", "'; use head/chest/legs/feet/offhand/hotbar_1-9", "'; use head/chest/legs/feet/offhand/hotbar_1-9"},
        {"Item '", "Item '", "Item '"},
        {"' not found in inventory", "' not found in inventory", "' não encontrado no inventário"},
        {"Equipment slots are unavailable.", "Equipment slots are unavailable.", "Slots de equipamento indisponíveis."},
        {"Equipment move was refused or could not be verified.", "Equipment move was refused or could not be verified.", "A movimentação do equipamento foi recusada ou não pôde ser verificada."},
        {"Item ID to collect (e.g. 'minecraft:diamond'). If null, collects all items.", "Item ID to collect (e.g. 'minecraft:diamond'). If null, collects all items.", "ID do item a coletar. Se nulo, coleta todos os itens."},
        {"Search radius in blocks (1-32, default 8)", "Search radius in blocks (1-32, default 8)", "Raio de busca em blocos (1-32, padrão 8)"},
        {"Max number of items to collect (1-64, default 16)", "Max number of items to collect (1-64, default 16)", "Quantidade máxima de itens a coletar (1-64, padrão 16)"},
        {"'radius' must be an integer from 1 to ", "'radius' must be an integer from 1 to ", "'radius' deve ser um inteiro de 1 a "},
        {"'count' must be an integer from 1 to 64.", "'count' must be an integer from 1 to 64.", "'count' deve ser um inteiro de 1 a 64."},
        {"Registered crafting recipe ID (e.g. 'minecraft:stone_pickaxe')", "Registered crafting recipe ID (e.g. 'minecraft:stone_pickaxe')", "ID registrado da receita de fabricação"},
        {"Number of recipe batches to craft (1-64)", "Number of recipe batches to craft (1-64)", "Quantidade de lotes da receita a fabricar (1-64)"},
        {"Crafting station: 'auto', 'table', or 'inventory' (default: 'auto')", "Crafting station: 'auto', 'table', or 'inventory' (default: 'auto')", "Estação de fabricação: 'auto', 'table' ou 'inventory' (padrão: 'auto')"},
        {"Desired total count after crafting. Defaults to count multiplied by recipe output; existing inventory reduces work.", "Desired total count after crafting. Defaults to count multiplied by recipe output; existing inventory reduces work.", "Quantidade total desejada após fabricar. O padrão é count multiplicado pelo resultado da receita; o inventário existente reduz o trabalho."},
        {"Why this item is needed; state the capability or explicit request for an additional copy", "Why this item is needed; state the capability or explicit request for an additional copy", "Por que este item é necessário; informe a capacidade ou o pedido explícito de uma cópia adicional"},
        {"Use only after a returned reuse candidate was verified unavailable, unsafe, too costly, or the owner explicitly requested an additional copy", "Use only after a returned reuse candidate was verified unavailable, unsafe, too costly, or the owner explicitly requested an additional copy", "Use somente após verificar que uma opção de reutilização está indisponível, é insegura, custosa demais ou o proprietário pediu explicitamente uma cópia adicional"},
        {"Missing required parameter 'recipe_id'.", "Missing required parameter 'recipe_id'.", "Parâmetro obrigatório 'recipe_id' ausente."},
        {"Invalid recipe ID: ", "Invalid recipe ID: ", "ID de receita inválido: "},
        {"'use_table' must be 'auto', 'table', or 'inventory'.", "'use_table' must be 'auto', 'table', or 'inventory'.", "'use_table' deve ser 'auto', 'table' ou 'inventory'."},
        {"Recipe '", "Recipe '", "A receita '"},
        {"' was not found.", "' was not found.", "' não foi encontrada."},
        {"' is not a crafting-grid recipe. Use its required station instead.", "' is not a crafting-grid recipe. Use its required station instead.", "' não usa a grade de fabricação. Use a estação exigida."},
        {"'desired_total' must be an integer from 1 to 4096.", "'desired_total' must be an integer from 1 to 4096.", "'desired_total' deve ser um inteiro de 1 a 4096."},
        {"The requested total is already present; no ingredients were consumed.", "The requested total is already present; no ingredients were consumed.", "O total solicitado já está presente; nenhum ingrediente foi consumido."},
        {"unspecified", "unspecified", "não especificado"},
        {"Compare live safety, reachability, time and owner intent. Verify stored contents with goto + inspect_block_storage/open GUI before transfer. Retry with bypass_reuse_check=true only with a concrete reason or explicit extra-copy requirement.", "Compare live safety, reachability, time and owner intent. Verify stored contents with goto + inspect_block_storage/open GUI before transfer. Retry with bypass_reuse_check=true only with a concrete reason or explicit extra-copy requirement.", "Compare a segurança atual, acessibilidade, tempo e intenção do proprietário. Verifique o conteúdo armazenado com goto + inspect_block_storage ou a interface aberta antes de transferir. Tente novamente com bypass_reuse_check=true somente com uma justificativa concreta ou uma necessidade explícita de cópia adicional."},
        {"' does not fit the 2x2 inventory grid.", "' does not fit the 2x2 inventory grid.", "' não cabe na grade 2x2 do inventário."},
        {"No crafting table was found within ", "No crafting table was found within ", "Nenhuma bancada de trabalho foi encontrada a até "},
        {" blocks for recipe '", " blocks for recipe '", " blocos para a receita '"},
        {"'. Observe or obtain a table before retrying.", "'. Observe or obtain a table before retrying.", "'. Observe ou obtenha uma bancada antes de tentar novamente."},
        {"A crafting table exists nearby but is outside crafting range.", "A crafting table exists nearby but is outside crafting range.", "Existe uma bancada de trabalho por perto, mas está fora do alcance de fabricação."},
        {"Use goto with goal_mode=block and the nearest_table coordinates, then retry craft.", "Use goto with goal_mode=block and the nearest_table coordinates, then retry craft.", "Use goto com goal_mode=block e as coordenadas de nearest_table; depois tente craft novamente."},
        {"craft ", "craft ", "fabricar "},
        {"The requested output is already reserved by another active action; verify current plan before crafting again.", "The requested output is already reserved by another active action; verify current plan before crafting again.", "O resultado solicitado já está reservado por outra ação ativa; verifique o plano atual antes de fabricar novamente."},
        {"Missing ingredients that form a valid recipe input.", "Missing ingredients that form a valid recipe input.", "Faltam ingredientes que formem uma entrada válida para a receita."},
        {"The selected ingredients produced no crafting result.", "The selected ingredients produced no crafting result.", "Os ingredientes selecionados não produziram um resultado de fabricação."},
        {"Recipe output changed between batches.", "Recipe output changed between batches.", "O resultado da receita mudou entre lotes."},
        {"Recipe returned an invalid remainder list.", "Recipe returned an invalid remainder list.", "A receita retornou uma lista inválida de itens restantes."},
        {"Insufficient carried capacity or changed ingredients; this batch consumed nothing.", "Insufficient carried capacity or changed ingredients; this batch consumed nothing.", "Capacidade insuficiente ou ingredientes alterados; este lote não consumiu nada."},
        {"Cannot craft '", "Cannot craft '", "Não é possível fabricar '"},
        {"Unknown crafting failure.", "Unknown crafting failure.", "Falha de fabricação desconhecida."},
        {"Craft output delivery was not verified.", "Craft output delivery was not verified.", "A entrega do resultado da fabricação não foi verificada."},
        {"description.transfer", "Move items between live player slots or an open container. Player slots are 0-40; container indices come from inspect_gui. Slot permissions, components and capacity apply. Results distinguish moved counts from completion.", "Mova itens entre slots reais do inventário do jogador ou de um contêiner aberto. Os slots do jogador são 0-40; os índices do contêiner vêm de inspect_gui. Permissões dos slots, componentes e capacidade são respeitados. Os resultados distinguem a quantidade movida da conclusão."},
        {"description.drop", "Drop carried items onto the ground through normal player toss permissions and events. Only verified spawned items count as dropped; a refused delivery retains the items.", "Descarte itens do inventário no chão pelas permissões e eventos normais de descarte do jogador. Somente itens cuja criação no mundo foi verificada contam como descartados; uma entrega recusada mantém os itens."},
        {"description.equip", "Equip an existing inventory item in head, chest, legs, feet, offhand, or hotbar_1-9. Real equipment slot restrictions apply, including removal restrictions. Displaced stacks return to the source slot.", "Equipe um item existente do inventário em head, chest, legs, feet, offhand ou hotbar_1-9. As restrições reais dos slots de equipamento são respeitadas, incluindo as de remoção. Pilhas substituídas voltam ao slot de origem."},
        {"description.collect", "Walk to and pick up nearby dropped items. Filter by item ID or collect all matching items within the radius, bounded by count. Returns a task_id for tracking; acceptance is not proof of collection.", "Caminhe até itens próximos no chão e colete-os. Filtre pelo ID do item ou colete todos os itens correspondentes dentro do raio, limitado por count. Retorna task_id para acompanhamento; a aceitação não comprova a coleta."},
        {"description.craft", "Craft a registered recipe using the 2x2 grid or a nearby crafting table. Count is recipe batches, not output items. Every batch needs carried space for its complete output and recipe leftovers before any ingredients are consumed. Partial results report completed and remaining batches.", "Fabrique uma receita registrada usando a grade 2x2 ou uma bancada de trabalho próxima. count representa lotes da receita, não itens produzidos. Cada lote exige espaço no inventário para todo o resultado e os itens restantes da receita antes de consumir ingredientes. Resultados parciais informam lotes concluídos e restantes."}
        };
        for (String[] entry : entries) {
            if (english.put(entry[0], entry[1]) != null || portuguese.put(entry[0], entry[2]) != null) {
                throw new IllegalStateException("Duplicate inventory locale key: " + entry[0]);
            }
        }
        return new MessageCatalog(Map.of(Language.EN_US, english, Language.PT_BR, portuguese));
    }
}
