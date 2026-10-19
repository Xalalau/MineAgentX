package com.mineagent.engine.i18n;

import com.mineagent.api.i18n.Language;
import com.mineagent.api.i18n.MessageCatalog;
import com.mineagent.api.i18n.RuntimeLanguage;
import java.util.Map;

/** Trusted scheduler prose only; task/chain IDs and external diagnostics stay literal. */
public final class SchedulerMessages {
    private SchedulerMessages() {}

    private static final MessageCatalog CATALOG = new MessageCatalog(Map.of(
            Language.EN_US, Map.ofEntries(
                    Map.entry("admission.busy", "Body is busy with task '{0}'; wait for completion or call task_stop before dispatching another body task"),
                    Map.entry("intent.success", "Executor reports success"),
                    Map.entry("task.running", "Running"),
                    Map.entry("admission.accepted", "Accepted"),
                    Map.entry("task.error", "Task failed with {0}: {1}"),
                    Map.entry("fallback.PENDING", "pending"),
                    Map.entry("fallback.RUNNING", "running"),
                    Map.entry("fallback.PAUSED", "paused"),
                    Map.entry("fallback.SUCCESS", "success"),
                    Map.entry("fallback.FAILED", "failed"),
                    Map.entry("fallback.CANCELLED", "cancelled"),
                    Map.entry("snapshot.running", "{0} is running"),
                    Map.entry("snapshot.error", "Progress snapshot failed: {0}"),
                    Map.entry("task.paused", "Paused for survival chain {0}"),
                    Map.entry("log.paused", "[MineAgent] Body task {0} paused for survival chain {1}"),
                    Map.entry("ui.idle", "Idle"),
                    Map.entry("log.chain.priority", "[MineAgent] Chain {0} priority error: {1}"),
                    Map.entry("log.chain.interrupt", "[MineAgent] Chain interrupt error: {0}"),
                    Map.entry("log.chain.tick", "[MineAgent] Chain {0} tick error: {1}"),
                    Map.entry("log.task.tick", "[MineAgent] Task tick error: {0}"),
                    Map.entry("log.task.cleanup", "[MineAgent] Task cleanup error: {0}"),
                    Map.entry("log.idle", "[MineAgent] Idle/reflex error: {0}"),
                    Map.entry("log.reflex.tick", "[MineAgent] Reflex {0} tick error: {1}")
            ),
            Language.PT_BR, Map.ofEntries(
                    Map.entry("admission.busy", "O corpo está ocupado com a tarefa '{0}'; aguarde a conclusão ou chame task_stop antes de despachar outra tarefa corporal"),
                    Map.entry("intent.success", "O executor informa sucesso"),
                    Map.entry("task.running", "Em execução"),
                    Map.entry("admission.accepted", "Aceita"),
                    Map.entry("task.error", "A tarefa falhou com {0}: {1}"),
                    Map.entry("fallback.PENDING", "pendente"),
                    Map.entry("fallback.RUNNING", "em execução"),
                    Map.entry("fallback.PAUSED", "pausada"),
                    Map.entry("fallback.SUCCESS", "sucesso"),
                    Map.entry("fallback.FAILED", "falhou"),
                    Map.entry("fallback.CANCELLED", "cancelada"),
                    Map.entry("snapshot.running", "{0} está em execução"),
                    Map.entry("snapshot.error", "Falha no snapshot de progresso: {0}"),
                    Map.entry("task.paused", "Pausada para a cadeia de sobrevivência {0}"),
                    Map.entry("log.paused", "[MineAgent] Tarefa corporal {0} pausada para a cadeia de sobrevivência {1}"),
                    Map.entry("ui.idle", "Ocioso"),
                    Map.entry("log.chain.priority", "[MineAgent] Erro de prioridade da cadeia {0}: {1}"),
                    Map.entry("log.chain.interrupt", "[MineAgent] Erro ao interromper cadeia: {0}"),
                    Map.entry("log.chain.tick", "[MineAgent] Erro de tick da cadeia {0}: {1}"),
                    Map.entry("log.task.tick", "[MineAgent] Erro de tick da tarefa: {0}"),
                    Map.entry("log.task.cleanup", "[MineAgent] Erro na limpeza da tarefa: {0}"),
                    Map.entry("log.idle", "[MineAgent] Erro de ociosidade/reflexo: {0}"),
                    Map.entry("log.reflex.tick", "[MineAgent] Erro de tick do reflexo {0}: {1}")
            )
    ));

    public static String text(String key, Object... arguments) {
        return CATALOG.text(RuntimeLanguage.language(), key, arguments);
    }
}
