package com.stockmanagement.apikey;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stockmanagement.aiagent.AgentUser;
import com.stockmanagement.aiagent.DataCatalog;
import com.stockmanagement.aiagent.QueryEngine;
import com.stockmanagement.entity.UserTable;
import com.stockmanagement.service.AuditLogService;
import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.UserPermissionService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MCP (Model Context Protocol) darwaza: Grok / Claude / koi bhi MCP client yahan se StockFlow se baat karta hai.
 *
 *  POST /api/v1/mcp   (header: Authorization: Bearer sf_live_...)
 *
 * Tools:
 *  - "stockflow_datasets" -> kaun sa data (dataset) aur fields aap dekh sakte ho (AI ki zaroorat NAHI)
 *  - "stockflow_query"    -> data nikalo (QueryEngine: safe, read-only, company + permission scope server lagata hai).
 *                            Sochne ka kaam bahar wala AI (Grok) karta hai, StockFlow me koi AI model nahi chahiye.
 *  - "stockflow_ask"      -> StockFlow ka apna AI assistant (ye tabhi chalega jab AI Connections me model laga ho)
 *  - "stockflow_me"       -> key kis user ki hai
 *
 * Security wahi: /api/v1/** ki chain, ApiKeyAuthFilter (key, HTTPS, IP, rate limit). Delete kabhi nahi.
 *
 * Transport: MCP "Streamable HTTP" (sirf POST, JSON jawab). Server se push (SSE) nahi hai, isliye GET = 405.
 * Methods: initialize, notifications/*, ping, tools/list, tools/call.
 */
@RestController
@RequestMapping("/api/v1/mcp")
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private static final String DEFAULT_PROTOCOL = "2025-03-26";
    private static final String TOOL_ASK = "stockflow_ask";
    private static final String TOOL_ME = "stockflow_me";
    private static final String TOOL_DATASETS = "stockflow_datasets";
    private static final String TOOL_QUERY = "stockflow_query";
    private static final int MAX_MESSAGE = 4000;
    private static final int MAX_RESULT_CHARS = 60000;
    /** Max JSON-RPC requests inside one batch array. */
    private static final int MAX_BATCH = 5;
    /** Max tools/call executions per API key per minute (batching cannot bypass the per-request limit). */
    private static final int MAX_TOOL_CALLS_PER_MINUTE = 30;
    private static final int MAX_PROTOCOL_VERSION_LEN = 32;

    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.ArrayDeque<Long>> toolCalls =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Sliding 60s window per key. Returns true if this call is allowed. */
    private boolean allowToolCall(HttpServletRequest req) {
        Object k = req.getAttribute(ApiKeyAuthFilter.ATTR_KEY);
        if (!(k instanceof ApiKey key) || key.getId() == null) return false;
        long now = System.currentTimeMillis();
        if (toolCalls.size() > 10_000) {
            toolCalls.entrySet().removeIf(e -> {
                synchronized (e.getValue()) {
                    while (!e.getValue().isEmpty() && e.getValue().peekFirst() < now - 60_000L) e.getValue().removeFirst();
                    return e.getValue().isEmpty();
                }
            });
        }
        java.util.ArrayDeque<Long> d = toolCalls.computeIfAbsent(key.getId(), x -> new java.util.ArrayDeque<>());
        synchronized (d) {
            while (!d.isEmpty() && d.peekFirst() < now - 60_000L) d.removeFirst();
            if (d.size() >= MAX_TOOL_CALLS_PER_MINUTE) return false;
            d.addLast(now);
            return true;
        }
    }

    private final ExternalApiController external;
    private final ObjectMapper mapper;
    private final DataCatalog catalog;
    private final QueryEngine engine;
    private final UserPermissionService permissionService;
    private final AuditLogService audit;

    public McpController(ExternalApiController external, ObjectMapper mapper, DataCatalog catalog,
                         QueryEngine engine, UserPermissionService permissionService, AuditLogService audit) {
        this.external = external;
        this.mapper = mapper;
        this.catalog = catalog;
        this.engine = engine;
        this.permissionService = permissionService;
        this.audit = audit;
    }

    /** Server-push (SSE) support nahi hai; MCP spec me 405 dena theek hai. */
    @GetMapping
    public ResponseEntity<Void> noStream() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).header("Allow", "POST").build();
    }

    @PostMapping(consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> handle(@AuthenticationPrincipal CustomUserDetails me,
                                           @RequestBody JsonNode body,
                                           HttpServletRequest req) {
        // Batch (array) bhi chalta hai
        if (body.isArray()) {
            if (body.size() > MAX_BATCH) {
                return ResponseEntity.badRequest().body(error(null, -32600,
                        "Batch too large (max " + MAX_BATCH + " requests per call)."));
            }
            ArrayNode out = mapper.createArrayNode();
            for (JsonNode one : body) {
                JsonNode r = dispatch(me, one, req);
                if (r != null) out.add(r);
            }
            return out.isEmpty() ? ResponseEntity.accepted().build() : ResponseEntity.ok(out);
        }
        JsonNode r = dispatch(me, body, req);
        // notification (id nahi) = jawab nahi, sirf 202
        return r == null ? ResponseEntity.accepted().build() : ResponseEntity.ok(r);
    }

    /* ---------------- JSON-RPC ---------------- */

    private JsonNode dispatch(CustomUserDetails me, JsonNode msg, HttpServletRequest req) {
        if (msg == null || !msg.isObject()) return error(null, -32600, "Invalid request.");
        JsonNode id = msg.get("id");
        boolean isNotification = id == null || id.isNull();
        String method = msg.path("method").asText("");

        if (method.isEmpty()) {
            // client ka jawab / kuch aur: hume kuch karna nahi
            return null;
        }
        if (isNotification) {
            return null; // notifications/initialized, notifications/cancelled, ...
        }

        try {
            switch (method) {
                case "initialize":
                    return result(id, initialize(msg.path("params")));
                case "ping":
                    return result(id, mapper.createObjectNode());
                case "tools/list":
                    return result(id, toolsList());
                case "tools/call":
                    if (!allowToolCall(req)) {
                        return error(id, -32000, "Too many tool calls. Please slow down.");
                    }
                    return result(id, toolsCall(me, msg.path("params"), req));
                default:
                    return error(id, -32601, "Method not found: " + method);
            }
        } catch (IllegalArgumentException e) {
            return error(id, -32602, e.getMessage());
        } catch (RuntimeException e) {
            log.warn("MCP request failed (method {}): {}", method, e.toString());
            return error(id, -32603, "Something went wrong. Please try again.");
        }
    }

    private ObjectNode initialize(JsonNode params) {
        String requested = params.path("protocolVersion").asText("");
        ObjectNode r = mapper.createObjectNode();
        boolean sane = !requested.isBlank() && requested.length() <= MAX_PROTOCOL_VERSION_LEN
                && requested.matches("[0-9A-Za-z.\\-]+");
        r.put("protocolVersion", sane ? requested : DEFAULT_PROTOCOL);
        r.putObject("capabilities").putObject("tools").put("listChanged", false);
        ObjectNode info = r.putObject("serverInfo");
        info.put("name", "StockFlow");
        info.put("version", "1.1.0");
        r.put("instructions", "StockFlow stock data. First call stockflow_datasets to see what data you can read, "
                + "then call stockflow_query to fetch it (group_by + metrics for totals, columns for detail rows). "
                + "Answers follow the permissions of the API key's user. Data is read-only.");
        return r;
    }

    private ObjectNode toolsList() {
        ObjectNode r = mapper.createObjectNode();
        ArrayNode tools = r.putArray("tools");

        // ---- stockflow_datasets ----
        ObjectNode ds = tools.addObject();
        ds.put("name", TOOL_DATASETS);
        ds.put("description", "List the StockFlow datasets (stock, inward, outward, returns, vendors, employees ...) "
                + "and their fields that this connection is allowed to read. Call this first, before stockflow_query.");
        ObjectNode dsSchema = ds.putObject("inputSchema");
        dsSchema.put("type", "object");
        dsSchema.putObject("properties");

        // ---- stockflow_query ----
        ObjectNode qry = tools.addObject();
        qry.put("name", TOOL_QUERY);
        qry.put("description", "Fetch StockFlow data (read-only). Use dataset and field names from stockflow_datasets. "
                + "For totals use group_by + metrics (e.g. count of rows per status). For detail rows use columns "
                + "(max 12 columns, max 200 rows). Example: {\"dataset\":\"<name>\",\"group_by\":[\"status\"],"
                + "\"metrics\":[{\"fn\":\"count\",\"as\":\"total\"}],\"filters\":[{\"field\":\"status\",\"op\":\"=\",\"value\":\"APPROVED\"}],"
                + "\"order_by\":[{\"field\":\"total\",\"dir\":\"desc\"}],\"limit\":50}. "
                + "metric fn: count, count_distinct, sum, avg, min, max. filter op: =, !=, >, >=, <, <=, in, contains, between, is_null, not_null. "
                + "Date fields can be grouped as \"<field>:day|month|year\". Dates are YYYY-MM-DD.");
        ObjectNode qs = qry.putObject("inputSchema");
        qs.put("type", "object");
        ObjectNode qp = qs.putObject("properties");
        qp.putObject("dataset").put("type", "string").put("description", "Dataset name from stockflow_datasets.");
        qp.putObject("group_by").put("type", "array").putObject("items").put("type", "string");
        qp.putObject("metrics").put("type", "array").putObject("items").put("type", "object");
        qp.putObject("columns").put("type", "array").putObject("items").put("type", "string");
        qp.putObject("filters").put("type", "array").putObject("items").put("type", "object");
        qp.putObject("order_by").put("type", "array").putObject("items").put("type", "object");
        qp.putObject("limit").put("type", "integer");
        qs.putArray("required").add("dataset");

        // ---- stockflow_ask ----
        ObjectNode ask = tools.addObject();
        ask.put("name", TOOL_ASK);
        ask.put("description", "Ask StockFlow's own built-in AI assistant a question in plain words. "
                + "Works only if an AI model is configured inside StockFlow; otherwise use stockflow_datasets + stockflow_query. "
                + "Pass conversation_id from a previous answer to continue the same conversation.");
        ObjectNode schema = ask.putObject("inputSchema");
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("message").put("type", "string")
                .put("description", "Your question, e.g. \"How many vendors do we have?\"");
        props.putObject("conversation_id").put("type", "integer")
                .put("description", "Optional. conversation_id returned by an earlier answer.");
        schema.putArray("required").add("message");

        // ---- stockflow_me ----
        ObjectNode me = tools.addObject();
        me.put("name", TOOL_ME);
        me.put("description", "Check which StockFlow user and access (read only / read + add/edit) this connection has.");
        ObjectNode meSchema = me.putObject("inputSchema");
        meSchema.put("type", "object");
        meSchema.putObject("properties");
        return r;
    }

    private ObjectNode toolsCall(CustomUserDetails me, JsonNode params, HttpServletRequest req) {
        String name = params.path("name").asText("");
        JsonNode args = params.path("arguments");

        if (TOOL_ME.equals(name)) {
            Map<String, Object> out = external.me(me, req);
            return text(mapper.valueToTree(out).toPrettyString(), false);
        }
        if (TOOL_DATASETS.equals(name)) {
            AgentUser u = agentUser(me);
            String menu = catalog.menuFor(u);
            if (menu.isBlank()) return text("No datasets are available for this user.", true);
            return text(menu, false);
        }
        if (TOOL_QUERY.equals(name)) {
            if (!args.isObject()) return text("Arguments must be an object with at least \"dataset\".", true);
            AgentUser u = agentUser(me);
            try {
                QueryEngine.Result res = engine.run(u, args);
                audit("API_QUERY", me, "MCP query: " + cut(args.toString(), 300));
                return text(render(res), false);
            } catch (QueryEngine.QueryException e) {
                return text(e.getMessage(), true);   // message safe hai, client ko wapas jaata hai
            }
        }
        if (TOOL_ASK.equals(name)) {
            String message = args.path("message").asText("").trim();
            if (message.isEmpty()) return text("\"message\" is required.", true);
            if (message.length() > MAX_MESSAGE) return text("\"message\" is too long (max " + MAX_MESSAGE + " characters).", true);
            Long convId = args.hasNonNull("conversation_id") && args.get("conversation_id").canConvertToLong()
                    ? args.get("conversation_id").asLong() : null;

            ResponseEntity<Map<String, Object>> res = external.ask(me,
                    new ExternalApiController.AskRequest(message, convId), req);
            Map<String, Object> m = res.getBody();
            if (m == null) return text("Empty response.", true);
            if (m.get("error") != null) return text(String.valueOf(m.get("error")), true);

            StringBuilder sb = new StringBuilder(String.valueOf(m.get("reply")));
            if (m.get("conversation_id") != null) {
                sb.append("\n\n(conversation_id: ").append(m.get("conversation_id")).append(")");
            }
            return text(sb.toString(), false);
        }
        throw new IllegalArgumentException("Unknown tool: " + name);
    }

    /* ---------------- helpers ---------------- */

    /** Key wale user ka snapshot (company + asli permissions DB se). Client isko badal nahi sakta. */
    private AgentUser agentUser(CustomUserDetails me) {
        UserTable u = me.getUser();
        Set<String> codes = permissionService.getAllowedPermissionDetails(u.getId()).stream()
                .map(p -> p.getPermissionCode()).collect(Collectors.toSet());
        return new AgentUser(u.getId(), u.getUsername(), u.getRoleId(), u.getCompanyName(), codes);
    }

    private String render(QueryEngine.Result r) {
        ObjectNode o = mapper.createObjectNode();
        ArrayNode cols = o.putArray("columns");
        r.columns().forEach(cols::add);
        ArrayNode rows = o.putArray("rows");
        int chars = 0;
        boolean cutOff = r.truncated();
        for (List<Object> row : r.rows()) {
            ArrayNode a = mapper.createArrayNode();
            for (Object v : row) {
                if (v == null) a.addNull();
                else if (v instanceof Number n) a.add(n.toString());
                else a.add(cut(String.valueOf(v), 200));
            }
            chars += a.toString().length();
            if (chars > MAX_RESULT_CHARS) { cutOff = true; break; }
            rows.add(a);
        }
        o.put("row_count", rows.size());
        if (cutOff) o.put("note", "Result truncated; narrow the filters or use group_by for complete totals.");
        return o.toString();
    }

    private void audit(String action, CustomUserDetails me, String desc) {
        try {
            audit.log(me.getUserId(), me.getUsername() + " (via API key)", action, "AI", "External API", desc, "API");
        } catch (RuntimeException e) {
            log.warn("MCP audit failed: {}", e.getMessage());
        }
    }

    private static String cut(String s, int n) {
        return s == null ? "" : (s.length() > n ? s.substring(0, n) : s);
    }

    private ObjectNode text(String s, boolean isError) {
        ObjectNode r = mapper.createObjectNode();
        r.putArray("content").addObject().put("type", "text").put("text", s);
        r.put("isError", isError);
        return r;
    }

    private ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode o = mapper.createObjectNode();
        o.put("jsonrpc", "2.0");
        o.set("id", id);
        o.set("result", result);
        return o;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode o = mapper.createObjectNode();
        o.put("jsonrpc", "2.0");
        if (id == null) o.putNull("id"); else o.set("id", id);
        ObjectNode e = o.putObject("error");
        e.put("code", code);
        e.put("message", message);
        return o;
    }
}