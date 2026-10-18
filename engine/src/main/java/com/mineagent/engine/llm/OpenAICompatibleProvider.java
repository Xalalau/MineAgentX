package com.mineagent.engine.llm;

import com.google.gson.*;
import com.mineagent.api.llm.ChatMessage;
import com.mineagent.api.llm.LLMResponse;
import com.mineagent.api.llm.LLMCapabilities;
import com.mineagent.api.llm.LLMRequest;
import com.mineagent.api.llm.provider.LLMProvider;
import com.mineagent.api.llm.provider.LLMProviderException;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Local llama.cpp/PrismML Chat Completions transport. MTP is managed by the server. */
public class OpenAICompatibleProvider implements LLMProvider {

    public static final String PROVIDER_ID = "openai-compatible";
    public static final String DEFAULT_MODEL = "swift-bonsai2-27b-katana-mtp";
    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:8899/v1";
    public static final Duration DEFAULT_TIMEOUT = LLMRequest.DEFAULT_DEADLINE;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(new java.net.ProxySelector() {
                @Override public List<java.net.Proxy> select(URI uri) {
                    return List.of(java.net.Proxy.NO_PROXY);
                }
                @Override public void connectFailed(URI uri, java.net.SocketAddress address,
                                                     java.io.IOException failure) {}
            })
            .build();

    private final String providerId;
    private final String defaultBaseUrl;
    private final String displayName;
    private final List<String> models;

    public OpenAICompatibleProvider() {
        this(PROVIDER_ID, "Local Bonsai / llama.cpp (MTP)", DEFAULT_BASE_URL, List.of(DEFAULT_MODEL));
    }

    protected OpenAICompatibleProvider(String providerId, String displayName, String defaultBaseUrl,
                                       List<String> models) {
        this.providerId = providerId;
        this.displayName = displayName;
        this.defaultBaseUrl = defaultBaseUrl;
        this.models = List.copyOf(models);
    }

    @Override
    public boolean requiresApiKey() { return false; }

    @Override
    public String providerId() { return providerId; }

    @Override
    public LLMCapabilities capabilities() {
        return new LLMCapabilities(java.util.EnumSet.of(
                LLMCapabilities.Feature.CHAT_COMPLETIONS,
                LLMCapabilities.Feature.FUNCTION_CALLING), 0, 0);
    }

    @Override
    public String displayName() { return displayName; }

    @Override
    public String defaultBaseUrl() { return defaultBaseUrl; }

    @Override
    public boolean supportsModel(String model) {
        return model != null && !model.isBlank();
    }

    @Override
    public List<String> defaultModels() { return models; }

    /** Validate configured endpoints before a companion is allowed to spawn. */
    public static String validateBaseUrl(String baseUrl) {
        return ProviderSupport.validatedBaseUrl(baseUrl, DEFAULT_BASE_URL, PROVIDER_ID);
    }

    @Override
    public LLMResponse complete(String baseUrl, String apiKey, String model,
                                 List<ChatMessage> messages,
                                 List<Map<String, Object>> tools,
                                 double temperature, int maxTokens,
                                 String reasoningEffort) {
        ProviderSupport.validateRequest(providerId, apiKey, model, messages, temperature, maxTokens, false);
        return complete(new LLMRequest(baseUrl, apiKey, model, messages, List.of(), tools,
                temperature, maxTokens, reasoningEffort, null, null, null, DEFAULT_TIMEOUT, false));
    }

    @Override
    public LLMResponse complete(LLMRequest request) {
        // The state-aware path retains the caller deadline and full tool history.
        ProviderSupport.validateRequest(providerId, request.apiKey(), request.model(),
                request.messages(), request.temperature(), request.maxTokens(), false);
        String resolvedBaseUrl = ProviderSupport.validatedBaseUrl(
                request.baseUrl(), defaultBaseUrl, providerId);
        try {
            JsonObject body = buildRequestBody(request.model(), request.messages(), request.tools(),
                    request.temperature(), request.maxTokens(), false, request.promptCacheKey());
            injectThinkingEffort(body, request.model(), request.reasoningEffort());
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(chatEndpoint(resolvedBaseUrl, request.model())))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                    .timeout(request.deadline());
            addAuthentication(builder, request.apiKey());
            HttpResponse<String> response = HTTP_CLIENT.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw LLMProviderException.http(providerId, response.statusCode(), response.body(),
                        response.headers().firstValue("Retry-After").orElse(null));
            }
            try {
                return parseResponse(response.body());
            } catch (RuntimeException malformed) {
                throw ProviderSupport.malformedResponse(providerId, response.body(), malformed);
            }
        } catch (LLMProviderException failure) {
            throw failure;
        } catch (java.io.IOException | InterruptedException failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw LLMProviderException.transport(providerId, failure);
        }
    }

    /** Endpoint hook shared by local Chat Completions and native Ollama. */
    protected String chatEndpoint(String resolvedBaseUrl, String model) {
        return ProviderSupport.endpoint(resolvedBaseUrl, "/v1/chat/completions");
    }

    /** Authentication hook; the default is the OpenAI Bearer convention. */
    protected void addAuthentication(HttpRequest.Builder builder, String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
    }

    /** Auto preserves the model server's reasoning and chat-template defaults. */
    protected void injectThinkingEffort(JsonObject body, String model, String effort) {
        if (effort == null || effort.isBlank() || "auto".equalsIgnoreCase(effort.trim())) return;
        throw new IllegalArgumentException("Local inference uses server-managed reasoning; select auto");
    }

    // ── Request building ───────────────────────────────────────────

    protected JsonObject buildRequestBody(String model, List<ChatMessage> messages,
                                         List<Map<String, Object>> tools,
                                         double temperature, int maxTokens,
                                         boolean stream, String requestedCacheKey) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);

        // Messages
        JsonArray msgsArr = new JsonArray();
        for (ChatMessage msg : messages) {
            JsonObject msgObj = new JsonObject();
            msgObj.addProperty("role", msg.role());

            // Empty content is valid for assistant tool calls and empty tool results.
            if ("tool".equals(msg.role())) {
                msgObj.addProperty("content",
                        msg.content() != null ? msg.content() : "");
            } else if (msg.content() != null) {
                msgObj.addProperty("content", msg.content());
            } else {
                // null content on non-tool message — use empty string
                msgObj.addProperty("content", "");
            }

            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                JsonArray tcArr = new JsonArray();
                for (var tc : msg.toolCalls()) {
                    JsonObject tcObj = new JsonObject();
                    tcObj.addProperty("id", tc.id());
                    tcObj.addProperty("type", "function");
                    JsonObject fnObj = new JsonObject();
                    fnObj.addProperty("name", tc.name());
                    // Robust arguments: ensure it's a valid JSON string.
                    // Some upstream relays reject non-JSON arguments; if the
                    // caller passed a non-JSON string, wrap it as a JSON
                    // string literal so the API accepts the request.
                    String args = tc.arguments();
                    if (args == null || args.isBlank()) {
                        args = "{}";
                    } else {
                        args = ensureJsonArguments(args);
                    }
                    fnObj.addProperty("arguments", args);
                    tcObj.add("function", fnObj);
                    tcArr.add(tcObj);
                }
                msgObj.add("tool_calls", tcArr);
            }
            if (msg.toolCallId() != null) {
                msgObj.addProperty("tool_call_id", msg.toolCallId());
            }
            msgsArr.add(msgObj);
        }
        body.add("messages", msgsArr);

        // Tools
        if (tools != null && !tools.isEmpty()) {
            JsonArray toolsArr = new JsonArray();
            for (Map<String, Object> tool : tools) {
                toolsArr.add(GSON.toJsonTree(tool));
            }
            body.add("tools", toolsArr);
        }

        body.addProperty("temperature", temperature);
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("stream", stream);
        // Server-side prompt caching and MTP are configured by the launcher,
        // not by cloud-specific prompt_cache_key or speculative decoding fields.

        return body;
    }

    /**
     * Ensure a tool-call arguments string is valid JSON.
     *
     * <p>LLMs sometimes emit malformed arguments — bare words, unquoted
     * keys, trailing commas, or even natural language. This method:
     * <ol>
     *   <li>If the string already parses as JSON, return it unchanged.</li>
     *   <li>Otherwise, try lenient parsing (strip trailing commas, etc.).</li>
     *   <li>If all parsing fails, wrap the raw string as a JSON string
     *       literal under the key {@code "raw"} so the tool at least
     *       receives SOMETHING and the API accepts the request.</li>
     * </ol>
     *
     * <p>This prevents the entire request from being rejected by the API
     * because of a single malformed tool_call argument — a common failure
     * mode with smaller/cheaper models on relay sites.
     */
    private static String ensureJsonArguments(String args) {
        // Fast path: already valid JSON
        try {
            JsonParser.parseString(args);
            return args;
        } catch (JsonSyntaxException ignored) {
            // Fall through to lenient parsing
        }
        // Lenient: try removing trailing commas before } or ]
        String cleaned = args.replaceAll(",\\s*([}\\]])", "$1");
        try {
            JsonParser.parseString(cleaned);
            return cleaned;
        } catch (JsonSyntaxException ignored) {
            // Fall through to wrapping
        }
        // Last resort: wrap as a string literal
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("raw", args);
        return GSON.toJson(wrapper);
    }

    // ── Response parsing ───────────────────────────────────────────

    protected LLMResponse parseResponse(String json) {
        // Robust JSON extraction: some relays prepend BOM, whitespace, or
        // status text before the JSON body. Find the first '{' and try to
        // parse from there. If the whole thing fails, try extracting a
        // code-fenced JSON block (```json ... ```).
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            // Try extracting the outermost JSON object
            String extracted = extractJsonObject(json);
            if (extracted == null) {
                throw new RuntimeException(providerId
                        + " API returned non-JSON response (lenient parse failed). "
                        + "Response: " + truncate(json, 500), e);
            }
            root = JsonParser.parseString(extracted).getAsJsonObject();
        }
        String id = root.has("id") ? root.get("id").getAsString() : "";
        String model = root.has("model") ? root.get("model").getAsString() : "";

        // Validate choices array — some upstream relays return empty/missing
        // choices when content moderation, quota exhaustion, or upstream
        // errors occur. Without this guard we'd NPE or throw
        // IndexOutOfBoundsException, masking the real cause.
        if (!root.has("choices") || !root.get("choices").isJsonArray()) {
            throw new RuntimeException(providerId
                    + " API returned no 'choices' field (likely content moderation, "
                    + "quota exhaustion, or upstream error). Response: " + truncate(json, 500));
        }
        JsonArray choicesArr = root.getAsJsonArray("choices");
        if (choicesArr.isEmpty()) {
            throw new RuntimeException(providerId
                    + " API returned an empty 'choices' array (likely content "
                    + "moderation, quota exhaustion, or upstream error). Response: "
                    + truncate(json, 500));
        }
        if (!choicesArr.get(0).isJsonObject()) {
            throw new RuntimeException(providerId + " API returned a non-object choice");
        }
        JsonObject choiceObj = choicesArr.get(0).getAsJsonObject();
        String finishReason = choiceObj.has("finish_reason")
                && !choiceObj.get("finish_reason").isJsonNull()
                ? choiceObj.get("finish_reason").getAsString() : "";

        if (!choiceObj.has("message") || !choiceObj.get("message").isJsonObject()) {
            throw new RuntimeException(providerId + " API choice has no message object");
        }
        JsonObject messageObj = choiceObj.getAsJsonObject("message");
        String role = messageObj.has("role") ? messageObj.get("role").getAsString() : "assistant";
        String content = messageObj.has("content") && !messageObj.get("content").isJsonNull()
                ? (messageObj.get("content").isJsonPrimitive()
                    ? messageObj.get("content").getAsString()
                    : GSON.toJson(messageObj.get("content"))) : null;

        // Validate the entire advertised tool turn before accepting any calls.
        List<ChatMessage.ToolCallRef> toolCalls = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (var tcElem : ProviderSupport.validatedToolCalls(messageObj)) {
            JsonObject tcObj = tcElem.getAsJsonObject();
            String tcId = ProviderSupport.toolCallId(tcObj, seenIds, "call_");
            JsonObject fnObj = tcObj.getAsJsonObject("function");
            String tcName = fnObj.get("name").getAsString();
            // Preserve the existing tolerant argument normalization; dispatch
            // validates each tool's schema after parsing the response envelope.
            String tcArgs = "{}";
            if (fnObj.has("arguments") && !fnObj.get("arguments").isJsonNull()) {
                var argsElem = fnObj.get("arguments");
                tcArgs = argsElem.isJsonPrimitive()
                        ? ensureJsonArguments(argsElem.getAsString()) : GSON.toJson(argsElem);
            }
            toolCalls.add(new ChatMessage.ToolCallRef(tcId, tcName, tcArgs));
        }
        if (toolCalls.isEmpty()) toolCalls = null;

        ChatMessage message = new ChatMessage(role, content, toolCalls, null);

        // Usage
        LLMResponse.Usage usage = null;
        if (root.has("usage") && root.get("usage").isJsonObject()) {
            JsonObject usageObj = root.getAsJsonObject("usage");
            int promptTokens = usageObj.has("prompt_tokens")
                    ? usageObj.get("prompt_tokens").getAsInt() : 0;
            int cachedTokens = 0;
            // OpenAI reports cached input inside prompt_tokens_details;
            // DeepSeek-compatible APIs expose equivalent hit/miss counters
            // at the usage root. Normalize both so runtime logs can measure
            // the real provider cache rather than the local decision cache.
            if (usageObj.has("prompt_tokens_details")
                    && usageObj.get("prompt_tokens_details").isJsonObject()) {
                JsonObject details = usageObj.getAsJsonObject("prompt_tokens_details");
                if (details.has("cached_tokens")) {
                    cachedTokens = details.get("cached_tokens").getAsInt();
                }
            }
            if (usageObj.has("prompt_cache_hit_tokens")) {
                cachedTokens = Math.max(cachedTokens,
                        usageObj.get("prompt_cache_hit_tokens").getAsInt());
                if (promptTokens == 0) {
                    int misses = usageObj.has("prompt_cache_miss_tokens")
                            ? usageObj.get("prompt_cache_miss_tokens").getAsInt() : 0;
                    promptTokens = cachedTokens + misses;
                }
            }
            usage = new LLMResponse.Usage(
                    promptTokens,
                    usageObj.has("completion_tokens") ? usageObj.get("completion_tokens").getAsInt() : 0,
                    usageObj.has("total_tokens") ? usageObj.get("total_tokens").getAsInt() : 0,
                    cachedTokens,
                    0
            );
        }

        return new LLMResponse(id, model,
                new LLMResponse.Choice(0, message, finishReason), usage, finishReason);
    }

    /**
     * Extract the outermost JSON object from a string that may contain
     * leading/trailing non-JSON text (BOM, status text, code fences).
     *
     * <p>Strategy: find the first '{', then scan forward tracking brace
     * depth (respecting string literals and escape sequences). When depth
     * returns to 0, that's the end of the outermost object.
     *
     * @return the extracted JSON substring, or {@code null} if no balanced
     *         object was found.
     */
    private static String extractJsonObject(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        if (start < 0) return null;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (inString) {
                if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null; // unbalanced
    }

    /** Truncate a string to {@code max} chars, appending "..." if truncated. */
    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...(truncated)";
    }
}
