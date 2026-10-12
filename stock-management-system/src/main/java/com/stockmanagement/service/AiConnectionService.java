package com.stockmanagement.service;

import com.stockmanagement.entity.*;
import com.stockmanagement.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AI connections ka poora business logic.
 * Rules:
 *  - COMPANY connection: super admin banata hai; Manage-level holder edit kar sakta hai.
 *  - PERSONAL connection: user ki apni, sirf usi ko dikhti hai (company policy allow kare to).
 *  - API key kabhi UI ya log me wapas nahi jaati (sirf last 4 characters).
 */
@Service
public class AiConnectionService {

    public static final String SCOPE_COMPANY = "COMPANY";
    public static final String SCOPE_PERSONAL = "PERSONAL";

    public static final String MODE_PRIVATE = "PRIVATE";
    public static final String MODE_SELECTED = "SELECTED";
    public static final String MODE_COMPANY = "COMPANY";

    /* ---------- views (UI ko jo chahiye) ---------- */

    public record ModelView(Long id, String modelId, String displayName, boolean isDefault, boolean active) {}

    public record ConnectionView(Long id, String name, String providerKey, String providerLabel, String scope,
                                 String companyName, String baseUrl, String keyLast4, String status,
                                 boolean editable, boolean ownedByMe, String ownerName, String accessLabel,
                                 List<ModelView> models, String myLevel, boolean canShare) {}

    public record Cand(Long id, String name, String sub) {}

    public record AccessInfo(String mode, List<Long> userIds, List<Long> roleIds, List<Cand> users, List<Cand> roles) {}

    public record SaveForm(Long id, String scope, String companyName, String name, String provider,
                           String baseUrl, String apiKey, List<String> modelIds, String defaultModel) {}

    /** Chat (agle step) ke liye: user kaun-kaun se model use kar sakta hai. */
    public record UsableModel(Long connectionId, String connectionName, String providerKey, String providerLabel,
                              Long modelRowId, String modelId, boolean isDefault, String scope) {}

    private final AiConnectionRepository connRepo;
    private final AiConnectionModelRepository modelRepo;
    private final AiAccessRepository accessRepo;
    private final AiSettingRepository settingRepo;
    private final UserRepository userRepo;
    private final RoleRepository roleRepo;
    private final UserPermissionService permissionService;
    private final AiCryptoService crypto;
    private final AiProviderService providerClient;
    private final AiAccessService accessService;

    private final Map<Long, Deque<Long>> testHits = new ConcurrentHashMap<>();

    public AiConnectionService(AiConnectionRepository connRepo, AiConnectionModelRepository modelRepo,
                               AiAccessRepository accessRepo, AiSettingRepository settingRepo,
                               UserRepository userRepo, RoleRepository roleRepo,
                               UserPermissionService permissionService, AiCryptoService crypto,
                               AiProviderService providerClient, AiAccessService accessService) {
        this.connRepo = connRepo;
        this.modelRepo = modelRepo;
        this.accessRepo = accessRepo;
        this.settingRepo = settingRepo;
        this.userRepo = userRepo;
        this.roleRepo = roleRepo;
        this.permissionService = permissionService;
        this.crypto = crypto;
        this.providerClient = providerClient;
        this.accessService = accessService;
    }

    /* ============================================================
       WHO CAN DO WHAT
       ============================================================ */

    public boolean isSuperAdmin(CustomUserDetails u) {
        if (permissionService.hasPermission(u.getUserId(), "SUPER_ADMIN")) {
            return true;
        }
        // is database me SUPER_ADMIN sirf role ke naam se hai, permission row nahi
        Long roleId = u.getUser().getRoleId();
        return roleId != null && roleRepo.findById(roleId)
                .map(r -> "SUPER_ADMIN".equalsIgnoreCase(r.getRoleName()))
                .orElse(false);
    }

    public boolean isCryptoReady() {
        return crypto.isConfigured();
    }

    public boolean personalAllowed(String company) {
        return settingRepo.findByCompanyName(company).map(AiSetting::getAllowPersonalKeys).orElse(true);
    }

    public boolean canAddPersonal(CustomUserDetails u) {
        return isSuperAdmin(u) || personalAllowed(u.getUser().getCompanyName());
    }

    public List<String> companies() {
        return userRepo.findDistinctCompanyNames();
    }

    public Map<String, Boolean> policies() {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (String c : companies()) {
            out.put(c, personalAllowed(c));
        }
        return out;
    }

    @Transactional
    public void setPersonalPolicy(CustomUserDetails actor, String company, boolean allow) {
        requireSuperAdmin(actor);
        if (company == null || !companies().contains(company)) {
            throw new IllegalArgumentException("Choose a valid company.");
        }
        AiSetting s = settingRepo.findByCompanyName(company).orElseGet(() -> {
            AiSetting n = new AiSetting();
            n.setCompanyName(company);
            return n;
        });
        s.setAllowPersonalKeys(allow);
        settingRepo.save(s);
    }

    /* ============================================================
       LIST
       ============================================================ */

    @Transactional(readOnly = true)
    public List<ConnectionView> listFor(CustomUserDetails actor) {
        boolean sa = isSuperAdmin(actor);
        UserTable me = actor.getUser();

        List<AiConnection> conns = new ArrayList<>();
        if (sa) {
            conns.addAll(connRepo.findByScopeOrderByCompanyNameAscNameAsc(SCOPE_COMPANY));
        } else {
            conns.addAll(sharedWithUser(me));
            conns.addAll(disabledButManagedBy(me));   // Manage-holder disable ke baad bhi wapas Enable kar sake
        }
        conns.addAll(connRepo.findByOwnerUserIdAndScopeOrderByNameAsc(me.getId(), SCOPE_PERSONAL));
        if (conns.isEmpty()) {
            return List.of();
        }

        List<Long> ids = conns.stream().map(AiConnection::getId).toList();
        Map<Long, List<AiConnectionModel>> models = modelRepo.findByConnectionIdIn(ids).stream()
                .collect(Collectors.groupingBy(AiConnectionModel::getConnectionId));
        Map<Long, List<AiAccess>> access = accessRepo.findByConnectionIdIn(ids).stream()
                .collect(Collectors.groupingBy(AiAccess::getConnectionId));
        Set<Long> ownerIds = conns.stream().map(AiConnection::getCreatedBy).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> names = userRepo.findAllById(ownerIds).stream()
                .collect(Collectors.toMap(UserTable::getId, this::fullName));

        List<ConnectionView> out = new ArrayList<>();
        for (AiConnection c : conns) {
            boolean personal = SCOPE_PERSONAL.equals(c.getScope());
            boolean mine = Objects.equals(c.getCreatedBy(), me.getId());
            int myRank = accessService.rankOf(me, sa, c, access.getOrDefault(c.getId(), List.of()));
            boolean editable = personal ? Objects.equals(c.getOwnerUserId(), me.getId()) : (sa || myRank >= 3);
            AiProvider p = safeProvider(c.getProvider());
            List<ModelView> mv = models.getOrDefault(c.getId(), List.of()).stream()
                    .map(m -> new ModelView(m.getId(), m.getModelId(),
                            m.getDisplayName() == null ? m.getModelId() : m.getDisplayName(),
                            Boolean.TRUE.equals(m.getDefaultModel()), "ACTIVE".equals(m.getStatus())))
                    .toList();
            out.add(new ConnectionView(c.getId(), c.getName(), p.name(), p.getLabel(), c.getScope(),
                    c.getCompanyName(), c.getBaseUrl(), c.getKeyLast4(), c.getStatus(), editable, mine,
                    names.getOrDefault(c.getCreatedBy(), "-"),
                    personal ? "Only you" : accessLabel(access.getOrDefault(c.getId(), List.of())),
                    mv, personal ? "OWNER" : AiAccessService.levelName(myRank), !personal && myRank >= 2));
        }
        return out;
    }

    private List<AiConnection> disabledButManagedBy(UserTable me) {
        List<AiConnection> off = connRepo.findByCompanyNameAndScopeAndStatus(
                me.getCompanyName(), SCOPE_COMPANY, "DISABLED");
        if (off.isEmpty()) {
            return off;
        }
        Map<Long, List<AiAccess>> access = accessRepo
                .findByConnectionIdIn(off.stream().map(AiConnection::getId).toList()).stream()
                .collect(Collectors.groupingBy(AiAccess::getConnectionId));
        return off.stream()
                .filter(c -> accessService.rankOf(me, false, c, access.getOrDefault(c.getId(), List.of())) >= 3)
                .toList();
    }

    private List<AiConnection> sharedWithUser(UserTable me) {
        List<AiConnection> conns = connRepo.findByCompanyNameAndScopeAndStatus(
                me.getCompanyName(), SCOPE_COMPANY, "ACTIVE");
        if (conns.isEmpty()) {
            return conns;
        }
        Map<Long, List<AiAccess>> access = accessRepo
                .findByConnectionIdIn(conns.stream().map(AiConnection::getId).toList()).stream()
                .collect(Collectors.groupingBy(AiAccess::getConnectionId));
        return conns.stream()
                .filter(c -> accessService.rankOf(me, false, c, access.getOrDefault(c.getId(), List.of())) >= 1)
                .toList();
    }

    private String accessLabel(List<AiAccess> rows) {
        if (rows.isEmpty()) return "Only me";
        if (rows.stream().anyMatch(a -> "COMPANY".equals(a.getTargetType()))) return "Whole company";
        long u = rows.stream().filter(a -> "USER".equals(a.getTargetType())).count();
        long r = rows.stream().filter(a -> "ROLE".equals(a.getTargetType())).count();
        List<String> parts = new ArrayList<>();
        if (u > 0) parts.add(u + (u == 1 ? " person" : " people"));
        if (r > 0) parts.add(r + (r == 1 ? " role" : " roles"));
        return String.join(" + ", parts);
    }

    /** Ek connection ki detail (edit popup ke liye). Key kabhi nahi. */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(CustomUserDetails actor, Long id) {
        AiConnection c = loadEditable(actor, id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("provider", c.getProvider());
        m.put("baseUrl", c.getBaseUrl() == null ? "" : c.getBaseUrl());
        m.put("scope", c.getScope());
        m.put("companyName", c.getCompanyName());
        m.put("keyLast4", c.getKeyLast4());
        m.put("models", modelRepo.findByConnectionIdOrderByIdAsc(id).stream()
                .map(x -> Map.of("modelId", x.getModelId(),
                        "isDefault", Boolean.TRUE.equals(x.getDefaultModel()))).toList());
        return m;
    }

    /* ============================================================
       CREATE / UPDATE / DELETE / TOGGLE
       ============================================================ */

    @Transactional
    public AiConnection save(CustomUserDetails actor, SaveForm f) {
        if (!crypto.isConfigured()) {
            throw new IllegalArgumentException(
                    "Server encryption key is not configured (app.ai.master-key). Please contact IT.");
        }
        boolean sa = isSuperAdmin(actor);
        boolean isNew = f.id() == null;

        String name = clean(f.name(), 100);
        if (name.length() < 2) {
            throw new IllegalArgumentException("Give the connection a name (at least 2 characters).");
        }
        AiProvider provider = AiProvider.parse(f.provider());
        String baseUrl = f.baseUrl() == null ? "" : f.baseUrl().trim();
        if (provider.isBaseUrlRequired() && baseUrl.isEmpty()) {
            throw new IllegalArgumentException("Base URL is required for a custom provider.");
        }
        if (!baseUrl.isEmpty()) {
            providerClient.resolveBaseUrl(provider, baseUrl);   // validate (https, not private)
        }
        List<String> modelIds = cleanModelIds(f.modelIds());
        if (modelIds.isEmpty()) {
            throw new IllegalArgumentException("Select at least one model. Use \"Test connection\" first.");
        }

        AiConnection c;
        if (isNew) {
            c = new AiConnection();
            String scope = SCOPE_COMPANY.equals(f.scope()) ? SCOPE_COMPANY : SCOPE_PERSONAL;
            if (SCOPE_COMPANY.equals(scope)) {
                if (!sa) throw new IllegalArgumentException("Only a super admin can create a company connection.");
                if (f.companyName() == null || !companies().contains(f.companyName())) {
                    throw new IllegalArgumentException("Choose the company for this connection.");
                }
                c.setCompanyName(f.companyName());
            } else {
                if (!canAddPersonal(actor)) {
                    throw new IllegalArgumentException("Personal API keys are turned off for your company.");
                }
                c.setCompanyName(actor.getUser().getCompanyName());
                c.setOwnerUserId(actor.getUserId());
            }
            c.setScope(scope);
            c.setCreatedBy(actor.getUserId());
            if (f.apiKey() == null || f.apiKey().trim().length() < 8) {
                throw new IllegalArgumentException("Enter the API key.");
            }
        } else {
            c = loadEditable(actor, f.id());
        }

        c.setName(name);
        c.setProvider(provider.name());
        c.setBaseUrl(baseUrl.isEmpty() ? null : baseUrl);
        if (f.apiKey() != null && !f.apiKey().isBlank()) {
            String key = f.apiKey().trim();
            c.setApiKeyEnc(crypto.encrypt(key));
            c.setKeyLast4(key.substring(Math.max(0, key.length() - 4)));
        }
        c = connRepo.save(c);
        syncModels(c.getId(), modelIds, f.defaultModel());
        return c;
    }

    private void syncModels(Long connId, List<String> modelIds, String defaultModel) {
        Map<String, AiConnectionModel> existing = modelRepo.findByConnectionIdOrderByIdAsc(connId).stream()
                .collect(Collectors.toMap(AiConnectionModel::getModelId, Function.identity(), (a, b) -> a));
        String def = modelIds.contains(defaultModel) ? defaultModel : modelIds.get(0);

        for (AiConnectionModel old : existing.values()) {
            if (!modelIds.contains(old.getModelId())) {
                modelRepo.delete(old);
            }
        }
        for (String mid : modelIds) {
            AiConnectionModel m = existing.get(mid);
            if (m == null) {
                m = new AiConnectionModel();
                m.setConnectionId(connId);
                m.setModelId(mid);
                m.setDisplayName(mid);
            }
            m.setStatus("ACTIVE");
            m.setDefaultModel(mid.equals(def));
            modelRepo.save(m);
        }
    }

    @Transactional
    public AiConnection toggle(CustomUserDetails actor, Long id) {
        AiConnection c = loadEditable(actor, id);
        c.setStatus("ACTIVE".equals(c.getStatus()) ? "DISABLED" : "ACTIVE");
        return connRepo.save(c);
    }

    @Transactional
    public AiConnection delete(CustomUserDetails actor, Long id) {
        AiConnection c = loadEditable(actor, id);
        accessRepo.deleteByConnectionId(id);
        modelRepo.deleteByConnectionId(id);
        connRepo.delete(c);
        return c;
    }

    /* ============================================================
       TEST + FETCH MODELS
       ============================================================ */

    public List<String> testAndFetchModels(CustomUserDetails actor, String provider, String baseUrl,
                                           String apiKey, Long connectionId) {
        rateLimit(actor.getUserId());
        AiProvider p = AiProvider.parse(provider);
        String key = apiKey == null ? "" : apiKey.trim();
        if (key.isEmpty() && connectionId != null) {
            AiConnection c = loadEditable(actor, connectionId);
            key = crypto.decrypt(c.getApiKeyEnc());
        }
        if (key.isEmpty()) {
            throw new IllegalArgumentException("Enter the API key first.");
        }
        return providerClient.listModels(p, baseUrl, key);
    }

    private void rateLimit(Long userId) {
        long now = System.currentTimeMillis();
        Deque<Long> q = testHits.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > 300_000L) q.pollFirst();
            if (q.size() >= 15) {
                throw new IllegalArgumentException("Too many tests. Please wait a few minutes.");
            }
            q.addLast(now);
        }
    }

    /* ============================================================
       ACCESS (purana "Who can use" popup, sirf super admin)
       ============================================================ */

    @Transactional(readOnly = true)
    public AccessInfo accessInfo(CustomUserDetails actor, Long id) {
        AiConnection c = loadCompanyConnectionForAdmin(actor, id);
        List<AiAccess> rows = baseRows(actor, id);

        String mode = MODE_PRIVATE;
        if (rows.stream().anyMatch(a -> "COMPANY".equals(a.getTargetType()))) mode = MODE_COMPANY;
        else if (!rows.isEmpty()) mode = MODE_SELECTED;

        List<Long> userIds = rows.stream().filter(a -> "USER".equals(a.getTargetType()))
                .map(AiAccess::getTargetId).toList();
        List<Long> roleIds = rows.stream().filter(a -> "ROLE".equals(a.getTargetType()))
                .map(AiAccess::getTargetId).toList();

        List<Cand> users = userRepo.findByCompanyNameAndStatusOrderByFirstNameAsc(c.getCompanyName(), "ACTIVE")
                .stream().map(u -> new Cand(u.getId(), fullName(u),
                        u.getDepartment() == null ? "" : u.getDepartment())).toList();
        List<Cand> roles = roleRepo.findByStatusOrderByRoleNameAsc("ACTIVE").stream()
                .map(r -> new Cand(r.getId(), r.getRoleName(), "")).toList();
        return new AccessInfo(mode, userIds, roleIds, users, roles);
    }

    @Transactional
    public String setAccess(CustomUserDetails actor, Long id, String mode, List<Long> userIds, List<Long> roleIds) {
        AiConnection c = loadCompanyConnectionForAdmin(actor, id);
        // sirf is modal ke apne (base USE) rows replace hote hain; SHARE/MANAGE aur delegate grants safe rehte hain
        accessRepo.deleteAll(baseRows(actor, id));
        accessRepo.flush();

        if (MODE_COMPANY.equals(mode)) {
            addAccess(actor, id, "COMPANY", null);
            return "Whole company";
        }
        if (!MODE_SELECTED.equals(mode)) {
            return "Only me";
        }
        Set<Long> okUsers = userRepo.findByCompanyNameAndStatusOrderByFirstNameAsc(c.getCompanyName(), "ACTIVE")
                .stream().map(UserTable::getId).collect(Collectors.toSet());
        Set<Long> okRoles = roleRepo.findByStatusOrderByRoleNameAsc("ACTIVE").stream()
                .map(Role::getId).collect(Collectors.toSet());
        int u = 0, r = 0;
        for (Long uid : new LinkedHashSet<>(userIds == null ? List.<Long>of() : userIds)) {
            if (okUsers.contains(uid)) { addAccess(actor, id, "USER", uid); u++; }
        }
        for (Long rid : new LinkedHashSet<>(roleIds == null ? List.<Long>of() : roleIds)) {
            if (okRoles.contains(rid)) { addAccess(actor, id, "ROLE", rid); r++; }
        }
        return u + " people, " + r + " roles";
    }

    private List<AiAccess> baseRows(CustomUserDetails actor, Long connId) {
        return accessRepo.findByConnectionId(connId).stream()
                .filter(a -> AiAccessService.USE.equals(a.getAccessLevel())
                        && (a.getGrantedBy() == null || Objects.equals(a.getGrantedBy(), actor.getUserId())))
                .toList();
    }

    private void addAccess(CustomUserDetails actor, Long connId, String type, Long targetId) {
        AiAccess a = new AiAccess();
        a.setConnectionId(connId);
        a.setTargetType(type);
        a.setTargetId(targetId);
        a.setCreatedBy(actor.getUserId());
        a.setGrantedBy(actor.getUserId());
        a.setAccessLevel(AiAccessService.USE);
        accessRepo.save(a);
    }

    /* ============================================================
       NEXT STEP (chat) KE LIYE: user kaun-kaun se model use kar sakta hai
       ============================================================ */

    @Transactional(readOnly = true)
    public List<UsableModel> usableModels(UserTable me) {
        List<AiConnection> conns = new ArrayList<>(
                connRepo.findByOwnerUserIdAndScopeOrderByNameAsc(me.getId(), SCOPE_PERSONAL));
        conns.addAll(sharedWithUser(me));
        conns = conns.stream().filter(c -> "ACTIVE".equals(c.getStatus())).toList();
        if (conns.isEmpty()) return List.of();

        Map<Long, AiConnection> byId = conns.stream().collect(Collectors.toMap(AiConnection::getId, c -> c));
        List<UsableModel> out = new ArrayList<>();
        for (AiConnectionModel m : modelRepo.findByConnectionIdIn(byId.keySet())) {
            if (!"ACTIVE".equals(m.getStatus())) continue;
            AiConnection c = byId.get(m.getConnectionId());
            AiProvider p = safeProvider(c.getProvider());
            out.add(new UsableModel(c.getId(), c.getName(), p.name(), p.getLabel(), m.getId(), m.getModelId(),
                    Boolean.TRUE.equals(m.getDefaultModel()), c.getScope()));
        }
        return out;
    }

    /** Chat backend ke liye: ye connection is user ke liye allowed hai to hi decrypted key deta hai. */
    @Transactional(readOnly = true)
    public String decryptedKeyFor(UserTable me, Long connectionId) {
        boolean allowed = usableModels(me).stream().anyMatch(m -> m.connectionId().equals(connectionId));
        if (!allowed) {
            throw new IllegalArgumentException("You do not have access to this AI connection.");
        }
        return crypto.decrypt(connRepo.findById(connectionId).orElseThrow().getApiKeyEnc());
    }

    /* ============================================================
       helpers
       ============================================================ */

    private AiConnection loadEditable(CustomUserDetails actor, Long id) {
        AiConnection c = connRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Connection not found."));
        boolean ok = SCOPE_COMPANY.equals(c.getScope())
                ? (isSuperAdmin(actor) || accessService.rankOf(actor, c) >= 3)   // super admin ya Manage-level holder
                : Objects.equals(c.getOwnerUserId(), actor.getUserId());
        if (!ok) {
            throw new IllegalArgumentException("You are not allowed to change this connection.");
        }
        return c;
    }

    private AiConnection loadCompanyConnectionForAdmin(CustomUserDetails actor, Long id) {
        requireSuperAdmin(actor);
        AiConnection c = connRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Connection not found."));
        if (!SCOPE_COMPANY.equals(c.getScope())) {
            throw new IllegalArgumentException("Personal connections cannot be shared.");
        }
        return c;
    }

    private void requireSuperAdmin(CustomUserDetails actor) {
        if (!isSuperAdmin(actor)) {
            throw new IllegalArgumentException("Only a super admin can do this.");
        }
    }

    private AiProvider safeProvider(String key) {
        try {
            return AiProvider.parse(key);
        } catch (IllegalArgumentException e) {
            return AiProvider.CUSTOM;
        }
    }

    private String fullName(UserTable u) {
        return (u.getFirstName() == null ? "" : u.getFirstName())
                + (u.getLastName() == null || u.getLastName().isBlank() ? "" : " " + u.getLastName());
    }

    private String clean(String s, int max) {
        String t = s == null ? "" : s.trim().replaceAll("\\s+", " ");
        return t.length() > max ? t.substring(0, max) : t;
    }

    private List<String> cleanModelIds(List<String> raw) {
        if (raw == null) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : raw) {
            String t = s == null ? "" : s.trim();
            if (!t.isEmpty() && t.length() <= 150 && t.matches("[A-Za-z0-9._:/\\-@+]+")) {
                out.add(t);
            }
        }
        return new ArrayList<>(out);
    }
}