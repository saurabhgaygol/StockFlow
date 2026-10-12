package com.stockmanagement.service;

import com.stockmanagement.entity.*;
import com.stockmanagement.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Delegated access (levels) for COMPANY AI connections.
 *  USE    - chat me models use kar sakta hai (key kabhi nahi dikhti)
 *  SHARE  - USE + apni team ko USE dena / hatana
 *  MANAGE - SHARE + key replace, models, edit, disable, delete, aur team ko USE/SHARE/MANAGE dena
 * Super admin hamesha sab kar sakta hai. Company-wide aur role-wise grants sirf super admin.
 * "Team" = reporting hierarchy (parent_user_id) + jinhe user ne khud banaya (created_by), neeche tak.
 * Delegate ke diye hue grants tabhi valid hain jab delegate ke paas abhi bhi SHARE/MANAGE ho.
 */
@Service
public class AiAccessService {

    public static final String USE = "USE";
    public static final String SHARE = "SHARE";
    public static final String MANAGE = "MANAGE";

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd MMM yyyy");

    public record Person(Long id, String name, String sub) {}

    public record GrantView(Long rowId, String type, Long targetId, String targetName, String level,
                            String grantedByName, String grantedOn, String expiresOn, Integer dailyLimit,
                            boolean removable) {}

    public record AccessView(boolean superAdmin, String myLevel, List<String> grantable,
                             boolean canRoleAndCompany, List<GrantView> grants,
                             List<Person> people, List<Person> roles) {}

    private final AiConnectionRepository connRepo;
    private final AiAccessRepository accessRepo;
    private final UserRepository userRepo;
    private final RoleRepository roleRepo;
    private final UserPermissionService permissionService;

    public AiAccessService(AiConnectionRepository connRepo, AiAccessRepository accessRepo,
                           UserRepository userRepo, RoleRepository roleRepo,
                           UserPermissionService permissionService) {
        this.connRepo = connRepo;
        this.accessRepo = accessRepo;
        this.userRepo = userRepo;
        this.roleRepo = roleRepo;
        this.permissionService = permissionService;
    }

    /* ---------- level calculation ---------- */

    public static int rank(String level) {
        if (MANAGE.equals(level)) return 3;
        if (SHARE.equals(level)) return 2;
        if (USE.equals(level)) return 1;
        return 0;
    }

    public static String levelName(int rank) {
        return rank >= 3 ? MANAGE : rank == 2 ? SHARE : rank == 1 ? USE : "NONE";
    }

    private boolean isSa(Long userId) {
        if (userId == null) {
            return false;
        }
        if (permissionService.hasPermission(userId, "SUPER_ADMIN")) {
            return true;
        }
        // is database me SUPER_ADMIN sirf role ke naam se hai, permission row nahi
        return userRepo.findById(userId)
                .map(u -> u.getRoleId() != null && roleRepo.findById(u.getRoleId())
                        .map(r -> "SUPER_ADMIN".equalsIgnoreCase(r.getRoleName())).orElse(false))
                .orElse(false);
    }

    /** Rows jo abhi sach me valid hain (expiry + delegate-chain check). */
    List<AiAccess> effectiveRows(AiConnection c, List<AiAccess> rows) {
        LocalDateTime now = LocalDateTime.now();
        List<AiAccess> live = rows.stream()
                .filter(a -> a.getExpiresAt() == null || a.getExpiresAt().isAfter(now)).toList();

        Map<Long, Boolean> saCache = new HashMap<>();
        Set<Long> roots = new HashSet<>();               // creator
        if (c.getCreatedBy() != null) roots.add(c.getCreatedBy());

        Set<AiAccess> valid = new LinkedHashSet<>();
        for (AiAccess a : live) {
            Long g = a.getGrantedBy();
            if (g == null || roots.contains(g) || saCache.computeIfAbsent(g, this::isSa)) {
                valid.add(a);
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            Set<Long> sharers = valid.stream()
                    .filter(a -> "USER".equals(a.getTargetType()) && rank(a.getAccessLevel()) >= 2)
                    .map(AiAccess::getTargetId).collect(Collectors.toSet());
            for (AiAccess a : live) {
                if (!valid.contains(a) && sharers.contains(a.getGrantedBy())) {
                    valid.add(a);
                    changed = true;
                }
            }
        }
        return new ArrayList<>(valid);
    }

    /** User ka is connection par level (0 = koi nahi, 1 USE, 2 SHARE, 3 MANAGE). */
    public int rankOf(UserTable me, boolean sa, AiConnection c, List<AiAccess> rows) {
        if ("PERSONAL".equals(c.getScope())) {
            return Objects.equals(c.getOwnerUserId(), me.getId()) ? 3 : 0;
        }
        if (sa) return 3;
        if (Objects.equals(c.getCreatedBy(), me.getId())) return 3;
        if (!Objects.equals(c.getCompanyName(), me.getCompanyName())) return 0;
        int best = 0;
        for (AiAccess a : effectiveRows(c, rows)) {
            int r = rank(a.getAccessLevel());
            switch (a.getTargetType()) {
                case "COMPANY" -> best = Math.max(best, Math.min(r, 1));
                case "USER" -> { if (Objects.equals(a.getTargetId(), me.getId())) best = Math.max(best, r); }
                case "ROLE" -> { if (me.getRoleId() != null && Objects.equals(a.getTargetId(), me.getRoleId()))
                    best = Math.max(best, Math.min(r, 1)); }
                default -> { }
            }
        }
        return best;
    }

    public int rankOf(CustomUserDetails actor, AiConnection c) {
        return rankOf(actor.getUser(), isSa(actor.getUserId()), c, accessRepo.findByConnectionId(c.getId()));
    }

    /** Chat step ke liye: user ki daily limit (null = unlimited). */
    public Integer dailyLimitFor(UserTable me, AiConnection c) {
        Integer best = null;
        boolean unlimited = false;
        for (AiAccess a : effectiveRows(c, accessRepo.findByConnectionId(c.getId()))) {
            boolean mine = "COMPANY".equals(a.getTargetType())
                    || ("USER".equals(a.getTargetType()) && Objects.equals(a.getTargetId(), me.getId()))
                    || ("ROLE".equals(a.getTargetType()) && Objects.equals(a.getTargetId(), me.getRoleId()));
            if (!mine) continue;
            if (a.getDailyLimit() == null) unlimited = true;
            else best = best == null ? a.getDailyLimit() : Math.max(best, a.getDailyLimit());
        }
        return unlimited ? null : best;
    }

    /* ---------- team ---------- */

    /** me ke neeche ki poori hierarchy (me khud nahi). */
    public Set<Long> teamOf(UserTable me) {
        List<UserTable> all = userRepo.findByCompanyNameAndStatusOrderByFirstNameAsc(me.getCompanyName(), "ACTIVE");
        Set<Long> team = new LinkedHashSet<>();
        Deque<UserTable> q = new ArrayDeque<>();
        q.add(me);
        while (!q.isEmpty()) {
            UserTable p = q.poll();
            for (UserTable u : all) {
                if (Objects.equals(u.getId(), me.getId()) || team.contains(u.getId())) continue;
                boolean child = Objects.equals(u.getParentUserId(), p.getId())
                        || (p.getUsername() != null && p.getUsername().equals(u.getCreatedBy()));
                if (child) {
                    team.add(u.getId());
                    q.add(u);
                }
            }
        }
        return team;
    }

    /* ---------- view ---------- */

    @Transactional(readOnly = true)
    public AccessView view(CustomUserDetails actor, Long connId) {
        AiConnection c = companyConn(connId);
        boolean sa = isSa(actor.getUserId());
        List<AiAccess> all = accessRepo.findByConnectionId(connId);
        int my = rankOf(actor.getUser(), sa, c, all);
        if (my < 2) throw new IllegalArgumentException("You are not allowed to share this connection.");

        List<UserTable> companyUsers = userRepo.findByCompanyNameAndStatusOrderByFirstNameAsc(c.getCompanyName(), "ACTIVE");
        Map<Long, UserTable> byId = companyUsers.stream().collect(Collectors.toMap(UserTable::getId, u -> u, (a, b) -> a));
        Set<Long> team = sa ? byId.keySet() : teamOf(actor.getUser());
        Map<Long, String> roleNames = roleRepo.findByStatusOrderByRoleNameAsc("ACTIVE").stream()
                .collect(Collectors.toMap(Role::getId, Role::getRoleName, (a, b) -> a));

        List<GrantView> grants = new ArrayList<>();
        for (AiAccess a : effectiveRows(c, all)) {
            boolean mineOrTeam = sa || Objects.equals(a.getGrantedBy(), actor.getUserId())
                    || ("USER".equals(a.getTargetType()) && team.contains(a.getTargetId()));
            if (!mineOrTeam) continue;
            String tname = switch (a.getTargetType()) {
                case "COMPANY" -> "Whole company";
                case "ROLE" -> "Role: " + roleNames.getOrDefault(a.getTargetId(), "#" + a.getTargetId());
                default -> byId.containsKey(a.getTargetId()) ? name(byId.get(a.getTargetId())) : "User #" + a.getTargetId();
            };
            UserTable gb = a.getGrantedBy() == null ? null : userRepo.findById(a.getGrantedBy()).orElse(null);
            grants.add(new GrantView(a.getId(), a.getTargetType(), a.getTargetId(), tname, a.getAccessLevel(),
                    gb == null ? "Admin" : name(gb),
                    a.getCreatedAt() == null ? "" : a.getCreatedAt().format(DF),
                    a.getExpiresAt() == null ? "" : a.getExpiresAt().format(DF),
                    a.getDailyLimit(), canRemove(actor.getUserId(), sa, my, team, a)));
        }

        List<Person> people = companyUsers.stream()
                .filter(u -> team.contains(u.getId()) && !Objects.equals(u.getId(), actor.getUserId()))
                .map(u -> new Person(u.getId(), name(u), u.getDepartment() == null ? "" : u.getDepartment()))
                .toList();
        List<Person> roles = sa ? roleNames.entrySet().stream()
                .map(e -> new Person(e.getKey(), e.getValue(), "")).toList() : List.of();

        return new AccessView(sa, levelName(my), grantable(sa, my), sa, grants, people, roles);
    }

    private List<String> grantable(boolean sa, int my) {
        if (sa || my >= 3) return List.of(USE, SHARE, MANAGE);
        if (my == 2) return List.of(USE);
        return List.of();
    }

    private boolean canRemove(Long actorId, boolean sa, int my, Set<Long> team, AiAccess a) {
        if (sa) return true;
        if (Objects.equals(a.getGrantedBy(), actorId)) return true;
        return my >= 3 && a.getGrantedBy() != null && team.contains(a.getGrantedBy());
    }

    /* ---------- grant / revoke ---------- */

    @Transactional
    public String grant(CustomUserDetails actor, Long connId, String targetType, List<Long> targetIds,
                        String level, String expiresOn, Integer dailyLimit) {
        AiConnection c = companyConn(connId);
        boolean sa = isSa(actor.getUserId());
        List<AiAccess> all = accessRepo.findByConnectionId(connId);
        int my = rankOf(actor.getUser(), sa, c, all);
        if (my < 2) throw new IllegalArgumentException("You are not allowed to share this connection.");

        String lvl = level == null ? USE : level.trim().toUpperCase();
        if (!grantable(sa, my).contains(lvl)) {
            throw new IllegalArgumentException("You cannot give the " + lvl + " level.");
        }
        String type = targetType == null ? "USER" : targetType.trim().toUpperCase();
        if (!List.of("USER", "ROLE", "COMPANY").contains(type)) {
            throw new IllegalArgumentException("Invalid target.");
        }
        if (!"USER".equals(type)) {
            if (!sa) throw new IllegalArgumentException("Only a super admin can give role-wise or company-wide access.");
            lvl = USE;
        }

        LocalDateTime exp = null;
        if (expiresOn != null && !expiresOn.isBlank()) {
            try {
                exp = LocalDate.parse(expiresOn.trim()).atTime(23, 59, 59);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid expiry date.");
            }
            if (exp.isBefore(LocalDateTime.now())) {
                throw new IllegalArgumentException("Expiry date must be in the future.");
            }
        }
        if (dailyLimit != null && (dailyLimit < 1 || dailyLimit > 100000)) {
            throw new IllegalArgumentException("Daily limit must be between 1 and 100000.");
        }

        List<Long> targets = new ArrayList<>();
        if ("COMPANY".equals(type)) {
            targets.add(null);
        } else if ("ROLE".equals(type)) {
            Set<Long> okRoles = roleRepo.findByStatusOrderByRoleNameAsc("ACTIVE").stream()
                    .map(Role::getId).collect(Collectors.toSet());
            for (Long id : new LinkedHashSet<>(targetIds == null ? List.<Long>of() : targetIds)) {
                if (okRoles.contains(id)) targets.add(id);
            }
        } else {
            Set<Long> allowed = sa
                    ? userRepo.findByCompanyNameAndStatusOrderByFirstNameAsc(c.getCompanyName(), "ACTIVE")
                        .stream().map(UserTable::getId).collect(Collectors.toSet())
                    : teamOf(actor.getUser());
            for (Long id : new LinkedHashSet<>(targetIds == null ? List.<Long>of() : targetIds)) {
                if (allowed.contains(id) && !Objects.equals(id, actor.getUserId())) targets.add(id);
            }
        }
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("Select at least one valid person" + (sa ? "." : " from your team."));
        }

        Set<Long> team = sa ? Set.of() : teamOf(actor.getUser());
        int done = 0, skipped = 0;
        for (Long tid : targets) {
            AiAccess row = all.stream()
                    .filter(a -> type.equals(a.getTargetType()) && Objects.equals(a.getTargetId(), tid))
                    .findFirst().orElse(null);
            if (row != null && !canRemove(actor.getUserId(), sa, my, team, row)) {
                skipped++;       // kisi aur ka diya hua row, delegate overwrite nahi kar sakta
                continue;
            }
            if (row == null) {
                row = new AiAccess();
                row.setConnectionId(connId);
                row.setTargetType(type);
                row.setTargetId(tid);
            }
            row.setAccessLevel(lvl);
            row.setGrantedBy(actor.getUserId());
            row.setExpiresAt(exp);
            row.setDailyLimit(dailyLimit);
            row.setCreatedBy(actor.getUserId());
            accessRepo.save(row);
            done++;
        }
        accessRepo.flush();
        purgeInvalid(c);
        return done + " granted as " + lvl + (skipped > 0 ? ", " + skipped + " skipped (not yours to change)" : "");
    }

    @Transactional
    public String revoke(CustomUserDetails actor, Long connId, Long rowId) {
        AiConnection c = companyConn(connId);
        boolean sa = isSa(actor.getUserId());
        List<AiAccess> all = accessRepo.findByConnectionId(connId);
        int my = rankOf(actor.getUser(), sa, c, all);
        if (my < 2) throw new IllegalArgumentException("You are not allowed to change this connection's access.");
        AiAccess row = all.stream().filter(a -> Objects.equals(a.getId(), rowId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Access entry not found."));
        Set<Long> team = sa ? Set.of() : teamOf(actor.getUser());
        if (!canRemove(actor.getUserId(), sa, my, team, row)) {
            throw new IllegalArgumentException("You can only remove access that you or your team gave.");
        }
        accessRepo.delete(row);
        accessRepo.flush();
        purgeInvalid(c);
        return "Access removed";
    }

    /** Orphan / expired rows (jinka delegate ab valid nahi) delete kar do. */
    private void purgeInvalid(AiConnection c) {
        List<AiAccess> all = accessRepo.findByConnectionId(c.getId());
        Set<Long> keep = effectiveRows(c, all).stream().map(AiAccess::getId).collect(Collectors.toSet());
        List<AiAccess> drop = all.stream().filter(a -> !keep.contains(a.getId())).toList();
        if (!drop.isEmpty()) {
            accessRepo.deleteAll(drop);
            accessRepo.flush();
        }
    }

    /* ---------- helpers ---------- */

    private AiConnection companyConn(Long id) {
        AiConnection c = connRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Connection not found."));
        if (!"COMPANY".equals(c.getScope())) {
            throw new IllegalArgumentException("Personal connections cannot be shared.");
        }
        return c;
    }

    private String name(UserTable u) {
        return (u.getFirstName() == null ? "" : u.getFirstName())
                + (u.getLastName() == null || u.getLastName().isBlank() ? "" : " " + u.getLastName());
    }
}