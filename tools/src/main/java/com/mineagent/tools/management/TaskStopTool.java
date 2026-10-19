package com.mineagent.tools.management;

import com.mineagent.tools.i18n.ToolMessages;
import com.google.gson.JsonObject;
import com.mineagent.api.agent.tool.Schema;
import com.mineagent.api.agent.tool.Tool;
import com.mineagent.api.agent.tool.ToolArgs;
import com.mineagent.api.entity.AgentPlayer;
import com.mineagent.api.task.CompanionTickDispatcher;
import com.mineagent.api.task.TaskState;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Cancel a running task by its task_id. The task will be interrupted
 * on the next tick.
 *
 * <p>This is a <b>sync</b> tool — replies immediately.
 */
public class TaskStopTool implements Tool {

    @Override
    public String name() { return "task_stop"; }

    @Override
    public String description() {
        return ToolMessages.text("task_stop.description");
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("task_id", ToolMessages.text("task_stop.parameter.task_id"))
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, AgentPlayer player,
                              Consumer<String> reply) {
        String taskId = ToolArgs.getString(args, "task_id");
        if (taskId == null || taskId.isBlank()) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.missing_required_parameter_task_id")));
            return;
        }

        // Check if the task exists in TaskStatusTool's tracking
        var taskInfo = TaskStatusTool.getTaskInfo(player.companionId(), taskId);
        if (taskInfo == null) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.task_0_not_found", taskId)));
            return;
        }

        if (taskInfo.state == TaskState.SUCCESS || taskInfo.state == TaskState.FAILED
                || taskInfo.state == TaskState.CANCELLED) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.task_0_is_already_1", taskId, taskInfo.state.name())));
            return;
        }

        // Actually cancel the running task in the priority auction.
        // (Previously this only updated the tracking ledger — a no-op that
        // reported success while the task kept running.)
        // NOTE: we deliberately do NOT cancel the agent loop turn here —
        // the loop is the caller of this tool and is waiting for our reply.
        var stateOpt = com.mineagent.engine.MineAgentEngine.getCompanion(player.companionId());
        if (stateOpt.isEmpty() || !stateOpt.get().auction.cancelTask(taskId)) {
            reply.accept(ToolArgs.errorJson(ToolMessages.text("error.task_0_is_no_longer_active", taskId)));
            return;
        }

        // PriorityAuction publishes the authoritative terminal ledger entry.

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("task_id", taskId);
        result.addProperty("state", "CANCELLED");
        reply.accept(result.toString());
    }
}
