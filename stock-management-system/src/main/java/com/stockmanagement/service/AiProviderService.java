package com.stockmanagement.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Provider se "list models" call karke key check karta hai aur available models deta hai.
 * Redirect follow nahi karta, aur private/localhost URL by default block hai (SSRF se bachav).
 * Error message me API key kabhi nahi aati.
 */
@Service
public class AiProviderService {

    private static final int MAX_BODY = 5_000_000;

    private final ObjectMapper mapper;
    private final boolean allowPrivateHosts;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public AiProviderService(ObjectMapper mapper,
                             @Value("${app.ai.allow-private-hosts:false}") boolean allowPrivateHosts) {
        this.mapper = mapper;
        this.allowPrivateHosts = allowPrivateHosts;
    }

    /** Effective base url (blank => provider default), trailing slash hata kar. */
    public String resolveBaseUrl(AiProvider provider, String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        if (b.isEmpty()) {
            b = provider.getDefaultBaseUrl();
        }
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        if (b.isEmpty()) {
            throw new IllegalArgumentException("Base URL is required for this provider.");
        }
        validateUrl(b);
        return b;
    }

    public List<String> listModels(AiProvider provider, String baseUrl, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API key is required.");
        }
        String base = resolveBaseUrl(provider, baseUrl);

        HttpRequest.Builder rb;
        switch (provider.getStyle()) {
            case ANTHROPIC -> rb = HttpRequest.newBuilder(URI.create(base + "/v1/models?limit=100"))
                    .header("x-api-key", apiKey.trim())
                    .header("anthropic-version", "2023-06-01");
            case GEMINI -> rb = HttpRequest.newBuilder(URI.create(base + "/v1beta/models?pageSize=200"))
                    .header("x-goog-api-key", apiKey.trim());
            default -> rb = HttpRequest.newBuilder(URI.create(base + "/models"))
                    .header("Authorization", "Bearer " + apiKey.trim());
        }
        HttpRequest req = rb.timeout(Duration.ofSeconds(15)).header("Accept", "application/json").GET().build();

        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiProviderException("The request was interrupted. Please try again.");
        } catch (IOException e) {
            throw new AiProviderException("Could not reach the provider. Check the Base URL and internet access.");
        }

        int code = res.statusCode();
        if (code == 401 || code == 403) {
            throw new AiProviderException("The provider rejected this API key (HTTP " + code + ").");
        }
        if (code == 404) {
            throw new AiProviderException("Endpoint not found (HTTP 404). Check the Base URL.");
        }
        if (code == 429) {
            throw new AiProviderException("The provider is rate limiting this key (HTTP 429). Try again shortly.");
        }
        if (code < 200 || code >= 300) {
            throw new AiProviderException("The provider returned HTTP " + code + ".");
        }
        String body = res.body();
        if (body == null || body.length() > MAX_BODY) {
            throw new AiProviderException("Unexpected response from the provider.");
        }

        try {
            return parseModels(provider, mapper.readTree(body));
        } catch (IOException e) {
            throw new AiProviderException("The provider sent a response we could not read.");
        }
    }

    private List<String> parseModels(AiProvider provider, JsonNode root) {
        TreeSet<String> ids = new TreeSet<>();
        if (provider.getStyle() == AiProvider.Style.GEMINI) {
            for (JsonNode m : root.path("models")) {
                boolean chat = false;
                for (JsonNode method : m.path("supportedGenerationMethods")) {
                    if ("generateContent".equals(method.asText())) {
                        chat = true;
                    }
                }
                String name = m.path("name").asText("");
                if (chat && !name.isBlank()) {
                    ids.add(name.startsWith("models/") ? name.substring(7) : name);
                }
            }
        } else {
            for (JsonNode m : root.path("data")) {
                String id = m.path("id").asText("");
                if (!id.isBlank()) {
                    ids.add(id);
                }
            }
        }
        return new ArrayList<>(ids);
    }

    private void validateUrl(String base) {
        URI uri;
        try {
            uri = URI.create(base);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Base URL is not valid.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String host = uri.getHost();
        if (host == null || !(scheme.equals("https") || (allowPrivateHosts && scheme.equals("http")))) {
            throw new IllegalArgumentException("Base URL must start with https://");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Base URL must not contain a username or password.");
        }
        if (allowPrivateHosts) {
            return;
        }
        try {
            for (InetAddress a : InetAddress.getAllByName(host)) {
                byte[] b = a.getAddress();
                boolean ula = b.length == 16 && (b[0] & 0xFE) == 0xFC;
                boolean cgnat = b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
                if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                        || a.isSiteLocalAddress() || a.isMulticastAddress() || ula || cgnat) {
                    throw new IllegalArgumentException("This Base URL points to a private network and is not allowed.");
                }
            }
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("Base URL host could not be found.");
        }
    }
}