package com.stockmanagement.apikey;

import com.stockmanagement.aiagent.ActionService;
import com.stockmanagement.entity.UserTable;
import com.stockmanagement.repository.RoleRepository;
import com.stockmanagement.repository.UserRepository;
import com.stockmanagement.service.AuditLogService;
import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.UserPermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * API Keys ka saara kaam: banana, check karna, delete, expiry. (Settings > API Keys)
 *
 * Niyam:
 *  - Key = "sf_live_" + 256-bit random. DB me sirf SHA-256 hash; poori key sirf banate waqt ek baar dikhti hai.
 *  - Ek user ki ek hi chalu key. Nayi chahiye to pehle purani delete karo.
 *  - Key banana: API_KEY_MANAGE permission ya SUPER_ADMIN.
 *  - Key sirf us user ke naam se chalti hai; user ki apni permissions aur company hi lagti hain. Koi extra power nahi.
 *  - User inactive ho jaye, company badal jaye ya password change pending ho to key apne aap band.
 *  - Expiry khali = no expiry. Allowed IPs khali = koi bhi IP.
 *
 * Bahar se aaye add/edit ka Confirm link bhi yahin rakha jaata hai (10 minute, ek baar, sirf usi user ke liye).
 */
@Service
public class ApiKeyService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    public static final String PERMISSION = "API_KEY_MANAGE";
    public static final String PREFIX = "sf_live_";
    private static final int RANDOM_BYTES = 32;                       // 256 bit
    private static final int RANDOM_CHARS = 43;                       // base64url bina padding
    private static final Pattern KEY_FORMAT = Pattern.compile("^sf_live_[A-Za-z0-9_-]{43}$");
    private static final Pattern IPV4 = Pattern.compile("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");
    private static final Pattern IPV6 = Pattern.compile("^[0-9a-fA-F:]{2,39}$");
    private static final int MAX_IPS = 20;
    private static final long TOUCH_EVERY_MS = 60_000L;               // last-used har request par DB me nahi likhte
    private static final long CONFIRM_TTL_MS = 10 * 60 * 1000L;
    private static final int MAX_CONFIRMS_PER_USER = 10;
    private static final SecureRandom RND = new SecureRandom();

    /** Naya key: entity + poori key (sirf ek baar dikhane ke liye, kahin save nahi hoti). */
    public record Created(ApiKey key, String plainKey) {}

    /** Request ke time sahi key mili: kis key se aur kaunsa user. */
    public record Verified(ApiKey key, UserTable user) {}

    /** Bahar se aaya add/edit jo StockFlow me Confirm hona baaki hai. */
    public record PendingConfirm(Long userId, String proposalToken, ActionService.ProposalView view,
                                 String keyName, long expiresAtMs) {}

    private final ApiKeyRepository repo;
    private final UserRepository userRepo;
    private final RoleRepository roleRepo;
    private final UserPermissionService permissionService;
    private final AuditLogService audit;
    private final Object createLock = new Object();
    private final Map<String, PendingConfirm> confirms = new ConcurrentHashMap<>();

    public ApiKeyService(ApiKeyRepository repo, UserRepository userRepo, RoleRepository roleRepo,
                         UserPermissionService permissionService, AuditLogService audit) {
        this.repo = repo;
        this.userRepo = userRepo;
        this.roleRepo = roleRepo;
        this.permissionService = permissionService;
        this.audit = audit;
    }

    /* ======================= kaun kya kar sakta hai ======================= */

    /** SUPER_ADMIN: permission row se ya role ke naam se (AiConnectionService jaisa hi). */
    public boolean isSuperAdmin(CustomUserDetails u) {
        if (u == null) return false;
        if (permissionService.hasPermission(u.getUserId(), "SUPER_ADMIN")) return true;
        Long roleId = u.getUser().getRoleId();
        return roleId != null && roleRepo.findById(roleId)
                .map(r -> "SUPER_ADMIN".equalsIgnoreCase(r.getRoleName()))
                .orElse(false);
    }

    public boolean canManage(CustomUserDetails u) {
        return u != null && (isSuperAdmin(u) || permissionService.hasPermission(u.getUserId(), PERMISSION));
    }

    /* ======================= list ======================= */

    /** User ki chalu key (ek hi hoti hai). */
    public Optional<ApiKey> myKey(Long userId) {
        return repo.findByUserIdAndDeletedAtIsNull(userId).stream().findFirst();
    }

    /** Admin ke liye saari chalu keys. Non-admin ko khali list. */
    public List<ApiKey> allKeys(CustomUserDetails actor) {
        if (!isSuperAdmin(actor)) return List.of();
        return repo.findByDeletedAtIsNullOrderByCreatedAtDesc();
    }

    /** Admin list me user ka naam dikhane ke liye. */
    public Map<Long, String> userNames(Collection<ApiKey> keys) {
        Map<Long, String> out = new HashMap<>();
        Set<Long> ids = new HashSet<>();
        for (ApiKey k : keys) ids.add(k.getUserId());
        for (UserTable u : userRepo.findAllById(ids)) out.put(u.getId(), u.getUsername());
        return out;
    }

    /* ======================= create ======================= */

    @Transactional
    public Created create(CustomUserDetails actor, String name, LocalDate expiryDate, boolean canWrite,
                          String allowedIps, String ip) {
        if (!canManage(actor)) {
            throw new IllegalArgumentException("You do not have permission to create an API key. Ask your administrator.");
        }
        UserTable me = userRepo.findById(actor.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("User not found."));
        if (!"ACTIVE".equalsIgnoreCase(me.getStatus())) {
            throw new IllegalArgumentException("Your account is not active.");
        }
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty()) throw new IllegalArgumentException("Key name is required.");
        if (cleanName.length() > 100) throw new IllegalArgumentException("Key name is too long (max 100 characters).");

        LocalDateTime expiresAt = toExpiry(expiryDate);
        String ips = cleanIps(allowedIps);

        synchronized (createLock) {
            if (!repo.findByUserIdAndDeletedAtIsNull(me.getId()).isEmpty()) {
                throw new IllegalArgumentException("You already have an API key. Delete it first, then create a new one.");
            }
            String random = randomPart();
            String plain = PREFIX + random;

            ApiKey k = new ApiKey();
            k.setUserId(me.getId());
            k.setCompanyName(me.getCompanyName());
            k.setName(cleanName);
            k.setKeyPrefix(PREFIX + random.substring(0, 6));
            k.setKeyHash(sha256(plain));
            k.setCanWrite(canWrite);
            k.setAllowedIps(ips);
            k.setExpiresAt(expiresAt);
            k.setCreatedAt(LocalDateTime.now());
            k.setCreatedBy(me.getUsername());
            k = repo.save(k);

            audit(me.getId(), me.getUsername(), "CREATE",
                    "Created API key '" + cleanName + "' (" + k.getKeyPrefix() + "..., "
                            + (canWrite ? "read + add/edit with confirm" : "read only") + ", "
                            + (expiresAt == null ? "no expiry" : "expires " + expiryDate) + ", "
                            + (ips == null ? "any IP" : "IPs " + ips) + ")", ip, k.getId());
            return new Created(k, plain);
        }
    }

    /* ======================= delete / expiry ======================= */

    /** Apni key delete. SUPER_ADMIN kisi ki bhi key delete kar sakta hai. Key usi second se band. */
    @Transactional
    public ApiKey delete(CustomUserDetails actor, Long id, String ip) {
        ApiKey k = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("API key not found."));
        boolean own = k.getUserId().equals(actor.getUserId());
        if (!own && !isSuperAdmin(actor)) throw new IllegalArgumentException("API key not found.");
        if (k.getDeletedAt() != null) throw new IllegalArgumentException("This API key is already deleted.");
        k.setDeletedAt(LocalDateTime.now());
        k.setDeletedBy(actor.getUsername());
        repo.save(k);
        confirms.values().removeIf(c -> c.userId().equals(k.getUserId()));
        audit(actor.getUserId(), actor.getUsername(), "DELETE",
                "Deleted API key '" + k.getName() + "' (" + k.getKeyPrefix() + "...)"
                        + (own ? "" : " of user id " + k.getUserId()), ip, k.getId());
        return k;
    }

    /** Expiry badalna: date = us din raat tak; null = no expiry. Sirf apni key (ya SUPER_ADMIN). */
    @Transactional
    public ApiKey changeExpiry(CustomUserDetails actor, Long id, LocalDate expiryDate, String ip) {
        ApiKey k = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("API key not found."));
        boolean own = k.getUserId().equals(actor.getUserId());
        if (!own && !isSuperAdmin(actor)) throw new IllegalArgumentException("API key not found.");
        if (k.getDeletedAt() != null) throw new IllegalArgumentException("This API key is deleted.");
        k.setExpiresAt(toExpiry(expiryDate));
        repo.save(k);
        audit(actor.getUserId(), actor.getUsername(), "UPDATE",
                "Changed expiry of API key '" + k.getName() + "' (" + k.getKeyPrefix() + "...) to "
                        + (expiryDate == null ? "no expiry" : expiryDate.toString()), ip, k.getId());
        return k;
    }

    /* ======================= request ke time check ======================= */

    /**
     * Bahar se aayi key sahi hai? Sab check yahin: format, hash, delete, expiry, user ACTIVE,
     * password change pending nahi, company wahi, IP allowed. Kuch bhi galat = empty (karan bahar nahi batate).
     */
    @Transactional
    public Optional<Verified> verify(String rawKey, String clientIp) {
        if (rawKey == null || !KEY_FORMAT.matcher(rawKey).matches()) return Optional.empty();
        Optional<ApiKey> found = repo.findByKeyHash(sha256(rawKey));
        if (found.isEmpty()) return Optional.empty();
        ApiKey k = found.get();
        LocalDateTime now = LocalDateTime.now();
        if (!k.isUsable(now)) return Optional.empty();

        UserTable u = userRepo.findById(k.getUserId()).orElse(null);
        if (u == null || !"ACTIVE".equalsIgnoreCase(u.getStatus())) return Optional.empty();
        if (Boolean.TRUE.equals(u.getForcePasswordChange())) return Optional.empty();
        if (!Objects.equals(u.getCompanyName(), k.getCompanyName())) return Optional.empty();
        if (!ipAllowed(k.getAllowedIps(), clientIp)) {
            log.warn("API key {}... used from IP {} which is not in its allowed list", k.getKeyPrefix(), clientIp);
            return Optional.empty();
        }

        if (k.getLastUsedAt() == null
                || java.time.Duration.between(k.getLastUsedAt(), now).toMillis() > TOUCH_EVERY_MS
                || !Objects.equals(k.getLastUsedIp(), clientIp)) {
            k.setLastUsedAt(now);
            k.setLastUsedIp(clientIp == null ? null : (clientIp.length() > 64 ? clientIp.substring(0, 64) : clientIp));
            repo.save(k);
        }
        return Optional.of(new Verified(k, u));
    }

    /* ======================= Confirm link (add/edit) ======================= */

    /** Bahar se aaya proposal: ek random link id deta hai (10 min, ek baar, sirf usi user ke liye). */
    public String registerConfirm(Long userId, ActionService.ProposalView view, String keyName) {
        purgeConfirms();
        long mine = confirms.values().stream().filter(c -> c.userId().equals(userId)).count();
        if (mine >= MAX_CONFIRMS_PER_USER) {
            confirms.entrySet().stream()
                    .filter(e -> e.getValue().userId().equals(userId))
                    .min(Comparator.comparingLong(e -> e.getValue().expiresAtMs()))
                    .ifPresent(e -> confirms.remove(e.getKey()));
        }
        byte[] b = new byte[24];
        RND.nextBytes(b);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        confirms.put(id, new PendingConfirm(userId, view.token(), view, keyName,
                System.currentTimeMillis() + CONFIRM_TTL_MS));
        return id;
    }

    /** Dekhna (hatana nahi). Galat user / expire = empty. */
    public Optional<PendingConfirm> peekConfirm(String linkId, Long userId) {
        purgeConfirms();
        PendingConfirm c = linkId == null ? null : confirms.get(linkId);
        if (c == null || !c.userId().equals(userId)) return Optional.empty();
        return Optional.of(c);
    }

    /** Lena aur hata dena (ek baar ka). Galat user / expire = empty. */
    public Optional<PendingConfirm> takeConfirm(String linkId, Long userId) {
        purgeConfirms();
        PendingConfirm c = linkId == null ? null : confirms.get(linkId);
        if (c == null || !c.userId().equals(userId)) return Optional.empty();
        confirms.remove(linkId);
        return Optional.of(c);
    }

    private void purgeConfirms() {
        long now = System.currentTimeMillis();
        confirms.values().removeIf(c -> c.expiresAtMs() < now);
    }

    /* ======================= helpers ======================= */

    private static LocalDateTime toExpiry(LocalDate date) {
        if (date == null) return null;
        if (date.isBefore(LocalDate.now())) throw new IllegalArgumentException("Expiry date cannot be in the past.");
        if (date.isAfter(LocalDate.now().plusYears(10))) throw new IllegalArgumentException("Expiry date is too far (max 10 years).");
        return LocalDateTime.of(date, LocalTime.of(23, 59, 59));
    }

    /** Comma / space / new line se alag IPs. Khali = null (koi bhi IP). Sirf asli IP likh sakte hain. */
    static String cleanIps(String raw) {
        if (raw == null || raw.isBlank()) return null;
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String p : raw.split("[,\\s]+")) {
            String ip = p.trim();
            if (ip.isEmpty()) continue;
            boolean ok = IPV4.matcher(ip).matches() || (ip.contains(":") && IPV6.matcher(ip).matches());
            if (!ok) throw new IllegalArgumentException("\"" + ip + "\" is not a valid IP address.");
            out.add(ip.toLowerCase(Locale.ROOT));
        }
        if (out.isEmpty()) return null;
        if (out.size() > MAX_IPS) throw new IllegalArgumentException("Too many IP addresses (max " + MAX_IPS + ").");
        String joined = String.join(",", out);
        if (joined.length() > 500) throw new IllegalArgumentException("IP list is too long.");
        return joined;
    }

    static boolean ipAllowed(String allowed, String clientIp) {
        if (allowed == null || allowed.isBlank()) return true;
        if (clientIp == null) return false;
        String c = clientIp.trim().toLowerCase(Locale.ROOT);
        for (String a : allowed.split(",")) if (a.trim().equals(c)) return true;
        return false;
    }

    private static String randomPart() {
        byte[] b = new byte[RANDOM_BYTES];
        RND.nextBytes(b);
        String s = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        if (s.length() != RANDOM_CHARS) throw new IllegalStateException("Key generation failed.");
        return s;
    }

    static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void audit(Long userId, String username, String action, String desc, String ip, Long refId) {
        try {
            String d = desc == null ? "" : (desc.length() > 480 ? desc.substring(0, 480) + "..." : desc);   // audit_logs.description max 500
            audit.log(userId, username, action, "Settings", "API Keys", d, ip, refId, "API_KEY");
        } catch (RuntimeException e) {
            log.warn("API key audit failed: {}", e.getMessage());
        }
    }
}