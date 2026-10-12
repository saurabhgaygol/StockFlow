package com.stockmanagement.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Provider ko chat request bhejta hai (Claude / Gemini / OpenAI-compatible) aur jawab ka text deta hai.
 * Base URL AiProviderService se validate hota hai (https + private network block). Error me API key kabhi nahi aati.
 */
@Service
public class AiChatClient {

    public record Turn(String role, String content) {}

    private static final int MAX_BODY = 8_000_000;

    private final ObjectMapper mapper;
    private final AiProviderService providerService;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public AiChatClient(ObjectMapper mapper, AiProviderService providerService) {
        this.mapper = mapper;
        this.providerService = providerService;
    }

    public String complete(AiProvider provider, String baseUrl, String apiKey, String model,
                           String system, List<Turn> turns) {
        String base = providerService.resolveBaseUrl(provider, baseUrl);
        String key = apiKey.trim();
        HttpRequest.Builder rb;
        String body;
        try {
            switch (provider.getStyle()) {
                case ANTHROPIC -> {
                    rb = HttpRequest.newBuilder(URI.create(base + "/v1/messages"))
                            .header("x-api-key", key).header("anthropic-version", "2023-06-01");
                    ObjectNode o = mapper.createObjectNode();
                    o.put("model", model);
                    o.put("max_tokens", 4096);
                    if (system != null && !system.isBlank()) o.put("system", system);
                    ArrayNode ms = o.putArray("messages");
                    for (Turn t : turns) ms.addObject().put("role", t.role()).put("content", t.content());
                    body = mapper.writeValueAsString(o);
                }
                case GEMINI -> {
                    String enc = URLEncoder.encode(model, StandardCharsets.UTF_8).replace("+", "%20");
                    rb = HttpRequest.newBuilder(URI.create(base + "/v1beta/models/" + enc + ":generateContent"))
                            .header("x-goog-api-key", key);
                    ObjectNode o = mapper.createObjectNode();
                    if (system != null && !system.isBlank()) {
                        o.putObject("systemInstruction").putArray("parts").addObject().put("text", system);
                    }
                    ArrayNode cs = o.putArray("contents");
                    for (Turn t : turns) {
                        ObjectNode c = cs.addObject();
                        c.put("role", "assistant".equals(t.role()) ? "model" : "user");
                        c.putArray("parts").addObject().put("text", t.content());
                    }
                    body = mapper.writeValueAsString(o);
                }
                default -> {
                    rb = HttpRequest.newBuilder(URI.create(base + "/chat/completions"))
                            .header("Authorization", "Bearer " + key);
                    ObjectNode o = mapper.createObjectNode();
                    o.put("model", model);
                    ArrayNode ms = o.putArray("messages");
                    if (system != null && !system.isBlank()) ms.addObject().put("role", "system").put("content", system);
                    for (Turn t : turns) ms.addObject().put("role", t.role()).put("content", t.content());
                    body = mapper.writeValueAsString(o);
                }
            }
        } catch (IOException e) {
            throw new AiProviderException("Could not prepare the request.");
        }

        HttpRequest req = rb.timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();

        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiProviderException("The request was interrupted. Please try again.");
        } catch (IOException e) {
            throw new AiProviderException("Could not reach the AI provider. Please try again in a moment.");
        }

        int code = res.statusCode();
        String text = res.body();
        if (code < 200 || code >= 300) {
            throw new AiProviderException(errorMessage(code, text));
        }
        if (text == null || text.length() > MAX_BODY) {
            throw new AiProviderException("Unexpected response from the AI provider.");
        }
        try {
            String out = extract(provider, mapper.readTree(text));
            if (out == null || out.isBlank()) {
                throw new AiProviderException("The AI returned an empty answer. Please try again.");
            }
            return out.trim();
        } catch (IOException e) {
            throw new AiProviderException("The AI provider sent a response we could not read.");
        }
    }

    private String extract(AiProvider provider, JsonNode root) {
        StringBuilder sb = new StringBuilder();
        switch (provider.getStyle()) {
            case ANTHROPIC -> {
                for (JsonNode c : root.path("content")) {
                    if ("text".equals(c.path("type").asText())) sb.append(c.path("text").asText(""));
                }
            }
            case GEMINI -> {
                for (JsonNode p : root.path("candidates").path(0).path("content").path("parts")) {
                    sb.append(p.path("text").asText(""));
                }
            }
            default -> {
                JsonNode c = root.path("choices").path(0).path("message").path("content");
                if (c.isArray()) {
                    for (JsonNode p : c) sb.append(p.path("text").asText(""));
                } else {
                    sb.append(c.asText(""));
                }
            }
        }
        return sb.toString();
    }

    private String errorMessage(int code, String body) {
        String detail = "";
        try {
            if (body != null) {
                JsonNode e = mapper.readTree(body).path("error");
                detail = e.isTextual() ? e.asText() : e.path("message").asText("");
            }
        } catch (IOException ignored) {
            // body JSON nahi hai
        }
        if (detail.length() > 200) detail = detail.substring(0, 200) + "...";
        if (code == 401 || code == 403) return "The AI provider rejected the API key (HTTP " + code + "). Ask your admin to check the connection.";
        if (code == 404) return "This model was not found at the provider (HTTP 404). Ask your admin to check the connection's models.";
        if (code == 429) return "The AI provider is busy or the key's quota is used up (HTTP 429). Please try again later.";
        return "The AI provider returned an error (HTTP " + code + ")" + (detail.isBlank() ? "." : ": " + detail);
    }
}