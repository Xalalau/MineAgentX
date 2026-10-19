package com.mineagent.engine.construction;

import com.google.gson.*;
import com.mineagent.api.config.MineAgentConfig.VisionConfig;
import com.mineagent.api.llm.*;
import com.mineagent.api.llm.provider.LLMProvider;
import com.mineagent.engine.llm.OpenAICompatibleProvider;
import com.mineagent.engine.llm.OllamaNativeProvider;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Bounded advisory review. Only immutable request data leaves the server thread; poll publishes evidence there. */
public final class ConstructionVisualReview implements AutoCloseable {
    private static final ConstructionVisualReview SHARED = new ConstructionVisualReview();
    public static ConstructionVisualReview shared() { return SHARED; }
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(2), runnable -> {
                var thread = new Thread(runnable, "construction-visual-review"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final java.time.Duration deadline;
    private final ScheduledThreadPoolExecutor deadlines = new ScheduledThreadPoolExecutor(1, runnable -> {
        var thread = new Thread(runnable, "construction-review-deadline"); thread.setDaemon(true); return thread;
    });
    public ConstructionVisualReview() { this(LLMRequest.DEFAULT_DEADLINE); }
    public ConstructionVisualReview(java.time.Duration deadline) {
        if (deadline == null || deadline.isZero() || deadline.isNegative() || deadline.compareTo(LLMRequest.DEFAULT_DEADLINE) > 0)
            throw new IllegalArgumentException("review_deadline");
        this.deadline = deadline; deadlines.setRemoveOnCancelPolicy(true);
    }
    private record Key(Path scope, String id) {}
    private static final class Job {
        final int revision;
        final VisionConfig config;
        final String language;
        final FutureTask<LLMResponse> future;
        volatile ScheduledFuture<?> timer;
        volatile boolean expired;
        JsonObject result;
        Job(int revision, VisionConfig config, String language, Callable<LLMResponse> call) {
            this.revision = revision; this.config = config; this.language = language;
            this.future = new FutureTask<>(call) {
                @Override protected void done() { if (timer != null) timer.cancel(false); }
            };
        }
        void cancel() { future.cancel(true); if (timer != null) timer.cancel(false); }
    }
    private final Map<Key, Job> jobs = new LinkedHashMap<>();

    /** Invoke only on the server thread. Starting never waits for the provider. */
    public synchronized JsonObject request(ConstructionRepository repository, ConstructionRepository.Revision revision,
                                          VisionConfig config, String locale, long tick, boolean start) throws IOException {
        var key = new Key(repository.scope(), revision.design().id());
        Job job = jobs.get(key);
        if (!config.configured()) {
            if (job != null) { job.cancel(); jobs.remove(key); }
            return state("vision_not_configured");
        }
        if (repository.read(key.id(), 0).version() != revision.version()
                || job != null && job.revision != revision.version()) {
            if (job != null) { job.cancel(); jobs.remove(key); }
            return state("stale_revision");
        }
        if (job != null && !job.config.equals(config)) {
            job.cancel(); jobs.remove(key);
            return state("configuration_changed");
        }
        if (job == null) {
            if (!start) return state("not_requested");
            OpenAICompatibleProvider.validateBaseUrl(config.baseUrl());
            if (jobs.size() >= 64) {
                var oldest = jobs.entrySet().iterator();
                while (oldest.hasNext()) {
                    if (oldest.next().getValue().future.isDone()) { oldest.remove(); break; }
                }
                if (jobs.size() >= 64) return state("review_busy");
            }
            var views = new OrthographicRenderer().render(revision.design());
            var messages = List.of(ChatMessage.system(ConstructionReviewText.guidance(locale)),
                    ChatMessage.userWithImages(ConstructionReviewText.context(locale, revision, views),
                            views.stream().map(OrthographicRenderer.Preview::dataUrl).toList()));
            var request = new LLMRequest(config.baseUrl(), config.apiKey(), config.model(), messages,
                    List.of(), List.of(), 0.2, 2048, null, null, null, null, deadline, false);
            LLMProvider provider = config.provider().equals("ollama") ? new OllamaNativeProvider() : new OpenAICompatibleProvider();
            try {
                job = new Job(revision.version(), config, locale, () -> provider.complete(request));
                Job scheduled = job;
                job.timer = deadlines.schedule(() -> {
                    if (!scheduled.future.isDone()) { scheduled.expired = true; scheduled.future.cancel(true); }
                }, deadline.toNanos(), TimeUnit.NANOSECONDS);
                workers.purge(); workers.execute(job.future);
                jobs.put(key, job);
            } catch (RejectedExecutionException busy) {
                if (job != null) job.cancel();
                return state("review_busy");
            }
            return state("queued");
        }
        if (job.result != null) return job.result.deepCopy();
        if (!job.future.isDone()) return state("pending");
        if (job.expired) { job.result = state("timeout"); return job.result.deepCopy(); }
        try {
            var response = job.future.get();
            var message = response.choice().message();
            if (message.content() == null || message.content().isBlank() || message.content().length() > 16384
                    || message.toolCalls() != null && !message.toolCalls().isEmpty()) throw new IllegalArgumentException("review_response");
            var result = state("reviewed"); result.addProperty("advisory", true);
            result.addProperty("revision", job.revision); result.addProperty("provider", job.config.provider());
            result.addProperty("model", job.config.model()); result.addProperty("language", job.language);
            result.addProperty("review", message.content());
            repository.appendEvidence(key.id(), job.revision, "visual_review", tick, result);
            job.result = result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); job.result = state("review_failed");
        } catch (ExecutionException | RuntimeException failure) {
            Throwable cause = failure;
            while (cause.getCause() != null && cause != cause.getCause()) cause = cause.getCause();
            job.result = state(cause instanceof java.net.http.HttpTimeoutException ? "timeout" : "review_failed");
        }
        return job.result.deepCopy();
    }
    @Override public synchronized void close() {
        jobs.values().forEach(Job::cancel); jobs.clear(); workers.shutdownNow(); deadlines.shutdownNow();
    }
    private static JsonObject state(String state) {
        var result = new JsonObject(); result.addProperty("state", state); return result;
    }
}
