package com.stockmanagement.service;

import com.stockmanagement.entity.AiConnection;
import com.stockmanagement.entity.AiConversation;
import com.stockmanagement.entity.AiMessage;
import com.stockmanagement.entity.UserTable;
import com.stockmanagement.repository.AiConnectionRepository;
import com.stockmanagement.repository.AiConversationRepository;
import com.stockmanagement.repository.AiMessageRepository;
import com.stockmanagement.repository.UserRepository;
import com.stockmanagement.service.AiConnectionService.UsableModel;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Plain AI chat. Software ka koi data (stock, vendors...) AI ko nahi diya jata.
 * Model "Auto" hai: chhota sawal -> tez/sasta model, bada/analysis/code/file -> powerful model.
 * User chat me khud bhi model chun sakta hai (modelKey).
 */
@Service
public class AiChatService {

    public record ConvView(Long id, String title, long updatedAt) {}
    public record MsgView(Long id, String role, String content, long createdAt) {}
    public record ChatResult(Long conversationId, String title, String reply, String model,
                             Long userMessageId, Long assistantMessageId,
                             com.stockmanagement.aiagent.FileSpec.FilePayload file,
                             com.stockmanagement.aiagent.FileSpec.CodePayload codeReport) {}
    public record ModelOption(String key, String modelId, String connection, String provider, boolean isDefault) {}

    private static final String SYSTEM_PROMPT =
            "You are a helpful AI assistant inside the StockFlow web application. "
            + "Answer clearly and concisely, and reply in the same language the user writes in "
            + "(Hindi, English or Hinglish). You do not have access to the company's stock, vendor or "
            + "any other business data in this version, so do not claim to look anything up; "
            + "if asked about such data, say you cannot access it yet. Use simple markdown (bold, lists, code blocks).";

    private static final int CONTEXT_MESSAGES = 20;

    private static final Pattern NON_CHAT = Pattern.compile(
            "(?i)(embed|whisper|tts|dall-e|moderation|imagen|veo|rerank|transcribe|speech|audio|realtime|image|vision-preview|guard)");
    private static final Pattern FAST = Pattern.compile(
            "(?i)(mini|nano|flash|haiku|lite|small|instant|turbo|8b|7b|3b|1b)");
    private static final Pattern HEAVY_WORDS = Pattern.compile(
            "(?i)(analy[sz]|explain|code|script|debug|compare|summar|report|plan|essay|translate|detail|step by step|"
            + "algorithm|calculate|formula|query|sql|vishleshan|samjhao)");

    private final AiConversationRepository convRepo;
    private final AiMessageRepository msgRepo;
    private final AiConnectionRepository connRepo;
    private final AiConnectionService connService;
    private final AiAccessService accessService;
    private final AiChatClient client;
    private final UserRepository userRepo;
    private final com.stockmanagement.aiagent.AgentService agent;

    public AiChatService(AiConversationRepository convRepo, AiMessageRepository msgRepo,
                         AiConnectionRepository connRepo, AiConnectionService connService,
                         AiAccessService accessService, AiChatClient client, UserRepository userRepo,
                         com.stockmanagement.aiagent.AgentService agent) {
        this.agent = agent;
        this.convRepo = convRepo;
        this.msgRepo = msgRepo;
        this.connRepo = connRepo;
        this.connService = connService;
        this.accessService = accessService;
        this.client = client;
        this.userRepo = userRepo;
    }

    /* ---------------- model list (chat ke dropdown ke liye) ---------------- */

    public List<ModelOption> modelOptions(Long userId) {
        UserTable me = userRepo.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found."));
        List<ModelOption> out = new ArrayList<>();
        for (UsableModel m : connService.usableModels(me)) {
            if (NON_CHAT.matcher(m.modelId()).find()) continue;
            out.add(new ModelOption(String.valueOf(m.modelRowId()), m.modelId(), m.connectionName(),
                    m.providerLabel(), m.isDefault()));
        }
        return out;
    }

    /* ---------------- history ---------------- */

    public List<ConvView> conversations(Long userId) {
        List<ConvView> out = new ArrayList<>();
        for (AiConversation c : convRepo.findTop200ByUserIdOrderByUpdatedAtDesc(userId)) {
            out.add(new ConvView(c.getId(), c.getTitle(), ms(c.getUpdatedAt())));
        }
        return out;
    }

    public List<MsgView> messages(Long userId, Long convId) {
        mine(userId, convId);
        List<MsgView> out = new ArrayList<>();
        for (AiMessage m : msgRepo.findByConversationIdOrderByIdAsc(convId)) {
            out.add(new MsgView(m.getId(), m.getRole(), m.getContent(), ms(m.getCreatedAt())));
        }
        return out;
    }

    public void rename(Long userId, Long convId, String title) {
        AiConversation c = mine(userId, convId);
        String t = title == null ? "" : title.trim();
        if (t.isEmpty()) throw new IllegalArgumentException("Title is required.");
        c.setTitle(t.length() > 200 ? t.substring(0, 200) : t);
        convRepo.save(c);
    }

    public void delete(Long userId, Long convId) {
        AiConversation c = mine(userId, convId);
        msgRepo.deleteByConversationId(c.getId());
        convRepo.delete(c);
    }

    /** Edit / regenerate: chat ke is message se aage ka sab hata do (sirf apni chat). */
    public void truncate(Long userId, Long convId, Long fromMessageId) {
        AiConversation c = mine(userId, convId);
        if (fromMessageId == null) throw new IllegalArgumentException("Message id is required.");
        msgRepo.deleteByConversationIdAndIdGreaterThanEqual(c.getId(), fromMessageId);
    }

    /* ---------------- chat ---------------- */

    /** Purana signature: Auto model. */
    public ChatResult chat(Long userId, Long convId, String displayText, String providerText, boolean hasFiles) {
        return chat(userId, convId, displayText, providerText, hasFiles, null);
    }

    /**
     * displayText = chat me jo dikhana/save karna hai; providerText = AI ko jo bhejna hai (file ka text saath).
     * modelKey = dropdown me chuna model ka key (null / "auto" = Auto).
     * Expected problems (no connection, limit, provider error) reply text me wapas aate hain (HTTP 200),
     * kyunki chat UI sirf reply dikhata hai.
     */
    public ChatResult chat(Long userId, Long convId, String displayText, String providerText, boolean hasFiles,
                           String modelKey) {
        UserTable me = userRepo.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found."));
        if (convId != null) mine(userId, convId);

        boolean agentMode = agent.isActiveFor(me);
        if (agentMode) {
            // Pehli deewar: software ke bahar / internals / delete / dabav wali baat model tak jaati hi nahi
            com.stockmanagement.aiagent.ScopeGuard.Decision d = agent.precheck(me, displayText);
            if (!d.allowed()) return soft(convId, d.reply());
        }

        List<UsableModel> usable = connService.usableModels(me);
        UsableModel pick = null;
        if (modelKey != null && !modelKey.isBlank() && !"auto".equalsIgnoreCase(modelKey.trim())) {
            for (UsableModel m : usable) {
                if (String.valueOf(m.modelRowId()).equals(modelKey.trim())) {
                    pick = m;
                    break;
                }
            }
            if (pick == null) {
                return soft(convId, "The model you selected is not available for you. Please choose another model or Auto.");
            }
        } else {
            pick = route(usable, providerText, hasFiles || agentMode);  // agent ko strong model chahiye (JSON protocol)
        }
        if (pick == null) {
            return soft(convId, "No AI model is available for you yet. Ask your admin to share an AI connection with you, "
                    + "or add your own key in Settings > AI Connections.");
        }

        AiConnection conn = connRepo.findById(pick.connectionId()).orElse(null);
        if (conn == null) {
            return soft(convId, "This AI connection no longer exists. Please try again.");
        }

        Integer limit = accessService.dailyLimitFor(me, conn);
        if (limit != null) {
            long used = msgRepo.countUserMessagesSince(userId, conn.getId(), LocalDate.now().atStartOfDay());
            if (used >= limit) {
                return soft(convId, "You have reached your daily limit of " + limit + " messages for this AI connection. "
                        + "It resets tomorrow, or ask your admin to raise it.");
            }
        }

        List<AiChatClient.Turn> turns = new ArrayList<>();
        if (convId != null) {
            List<AiMessage> last = new ArrayList<>(
                    msgRepo.findByConversationIdOrderByIdDesc(convId, PageRequest.of(0, CONTEXT_MESSAGES)));
            Collections.reverse(last);
            for (AiMessage m : last) turns.add(new AiChatClient.Turn(m.getRole(), m.getContent()));
        }
        turns.add(new AiChatClient.Turn("user", providerText));
        turns = mergeSameRole(turns);

        // Auto mode me: model 429/busy ho to agle available model par apne aap chale jao (user ka chuna model nahi badlega)
        final UsableModel first = pick;
        final AiConnection firstConn = conn;
        boolean autoPick = modelKey == null || modelKey.isBlank() || "auto".equalsIgnoreCase(modelKey.trim());
        List<UsableModel> tries = new ArrayList<>();
        tries.add(first);
        if (autoPick) {
            usable.stream()
                    .filter(m -> !NON_CHAT.matcher(m.modelId()).find() && !java.util.Objects.equals(m.modelRowId(), first.modelRowId()))
                    .limit(2).forEach(tries::add);
        }
        String reply = null;
        com.stockmanagement.aiagent.FileSpec.FilePayload filePayload = null;
        com.stockmanagement.aiagent.FileSpec.CodePayload codePayload = null;
        AiProviderException lastErr = null;
        for (UsableModel cand : tries) {
            AiConnection c2 = cand == first ? firstConn : connRepo.findById(cand.connectionId()).orElse(null);
            if (c2 == null) continue;
            try {
                String key = connService.decryptedKeyFor(me, cand.connectionId());
                AiProvider provider = AiProvider.parse(cand.providerKey());
                if (agentMode) {
                    // Agent: file text nahi, sirf user ka asli sawal; history chhoti
                    List<AiChatClient.Turn> hist = new ArrayList<>(turns.subList(0, Math.max(0, turns.size() - 1)));
                    com.stockmanagement.aiagent.AgentService.Reply ar = agent.run(me, provider, c2.getBaseUrl(), key, cand.modelId(), hist, displayText);
                    reply = ar.text();
                    filePayload = ar.file();
                    codePayload = ar.code();
                } else {
                    reply = client.complete(provider, c2.getBaseUrl(), key, cand.modelId(), SYSTEM_PROMPT, turns);
                }
                pick = cand;
                conn = c2;
                lastErr = null;
                break;
            } catch (AiProviderException e) {
                lastErr = e;
                pick = cand;
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (!(msg.contains("429") || msg.toLowerCase().contains("busy") || msg.toLowerCase().contains("quota"))) break;
            } catch (IllegalArgumentException e) {
                return soft(convId, e.getMessage());
            }
        }
        if (reply == null) {
            // Model ka naam / HTTP code user ko nahi dikhana; asli karan sirf server log me
            String raw = lastErr != null && lastErr.getMessage() != null ? lastErr.getMessage() : "";
            org.slf4j.LoggerFactory.getLogger(AiChatService.class).warn("AI failed for user {} (tried {} model(s)): {}", userId, tries.size(), raw);
            String low = raw.toLowerCase();
            String friendly = (low.contains("429") || low.contains("busy") || low.contains("quota"))
                    ? (autoPick ? "The AI service is very busy right now. I tried the other available models too. Please try again in a minute."
                            : "The selected AI model is very busy right now. Please switch the model to Auto, or try again in a minute.")
                    : "I couldn't get an answer from the AI service right now. Please try again in a moment, or contact your admin if it keeps happening.";
            return soft(convId, friendly);
        }

        AiConversation conv;
        if (convId == null) {
            conv = new AiConversation();
            conv.setUserId(userId);
            conv.setTitle(makeTitle(displayText));
            conv.setUpdatedAt(LocalDateTime.now());
            conv = convRepo.save(conv);
        } else {
            conv = mine(userId, convId);
        }

        LocalDateTime now = LocalDateTime.now();
        Long userMsgId = save(conv.getId(), userId, "user", displayText, conn.getId(), pick.modelId(), now);
        Long botMsgId = save(conv.getId(), userId, "assistant", reply, conn.getId(), pick.modelId(),
                now.plusNanos(1_000_000));
        conv.setUpdatedAt(now);
        convRepo.save(conv);

        return new ChatResult(conv.getId(), conv.getTitle(), reply, pick.modelId(), userMsgId, botMsgId, filePayload, codePayload);
    }

    /* ---------------- Auto model ---------------- */

    UsableModel route(List<UsableModel> all, String text, boolean hasFiles) {
        List<UsableModel> chatModels = all.stream().filter(m -> !NON_CHAT.matcher(m.modelId()).find()).toList();
        if (chatModels.isEmpty()) return null;

        String t = text == null ? "" : text;
        boolean heavy = hasFiles || t.length() > 500 || t.contains("```") || HEAVY_WORDS.matcher(t).find();

        List<UsableModel> wanted = chatModels.stream()
                .filter(m -> FAST.matcher(m.modelId()).find() != heavy)
                .toList();

        Comparator<UsableModel> order = Comparator
                .comparing((UsableModel m) -> !m.isDefault())
                .thenComparing(UsableModel::modelId, Comparator.reverseOrder());

        if (!wanted.isEmpty()) {
            return wanted.stream().min(order).orElse(null);
        }
        // is type ka model nahi mila: connection ka default, warna koi bhi
        return chatModels.stream().min(order).orElse(null);
    }

    /* ---------------- helpers ---------------- */

    private AiConversation mine(Long userId, Long convId) {
        return convRepo.findByIdAndUserId(convId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Chat not found."));
    }

    private Long save(Long convId, Long userId, String role, String content, Long connId, String modelId,
                      LocalDateTime at) {
        AiMessage m = new AiMessage();
        m.setConversationId(convId);
        m.setUserId(userId);
        m.setRole(role);
        m.setContent(content);
        m.setConnectionId(connId);
        m.setModelId(modelId);
        m.setCreatedAt(at);
        return msgRepo.save(m).getId();
    }

    /** Kuch providers ko lagataar same role pasand nahi: unhe jod do. */
    private List<AiChatClient.Turn> mergeSameRole(List<AiChatClient.Turn> in) {
        List<AiChatClient.Turn> out = new ArrayList<>();
        for (AiChatClient.Turn t : in) {
            if (!out.isEmpty() && out.get(out.size() - 1).role().equals(t.role())) {
                AiChatClient.Turn p = out.remove(out.size() - 1);
                out.add(new AiChatClient.Turn(p.role(), p.content() + "\n\n" + t.content()));
            } else {
                out.add(t);
            }
        }
        while (!out.isEmpty() && !"user".equals(out.get(0).role())) out.remove(0);
        return out;
    }

    private String makeTitle(String text) {
        String t = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) t = "New chat";
        return t.length() > 40 ? t.substring(0, 40) + "..." : t;
    }

    private ChatResult soft(Long convId, String message) {
        return new ChatResult(convId, null, message, null, null, null, null, null);
    }

    private static long ms(LocalDateTime t) {
        return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}