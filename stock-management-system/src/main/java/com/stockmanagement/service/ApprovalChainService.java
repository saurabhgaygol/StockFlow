package com.stockmanagement.service;

import com.stockmanagement.entity.ApprovalLevel;
import com.stockmanagement.entity.Role;
import com.stockmanagement.entity.UserTable;
import com.stockmanagement.repository.ApprovalLevelRepository;
import com.stockmanagement.repository.RoleRepository;
import com.stockmanagement.repository.UserRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ApprovalChainService {

    public static final String TYPE_USER = "USER";
    public static final String TYPE_ROLE = "ROLE";

    private final ApprovalLevelRepository levelRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    public ApprovalChainService(ApprovalLevelRepository levelRepository,
                                UserRepository userRepository,
                                RoleRepository roleRepository) {
        this.levelRepository = levelRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
    }

    public record LevelView(ApprovalLevel level, String approverLabel, String department, int memberCount) {
        public ApprovalLevel getLevel() { return level; }
        public String getApproverLabel() { return approverLabel; }
        public String getDepartment() { return department; }
        public int getMemberCount() { return memberCount; }
    }

    public List<ApprovalLevel> getLevels(String companyName) {
        return levelRepository.findByCompanyNameAndStatusOrderByLevelNoAsc(companyName, "ACTIVE");
    }

    public List<LevelView> getLevelViews(String companyName) {
        Map<Long, UserTable> users = userRepository.findByCompanyNameAndStatusOrderByFirstNameAsc(companyName, "ACTIVE")
                .stream().collect(Collectors.toMap(UserTable::getId, Function.identity()));
        Map<Long, Role> roles = roleRepository.findAll()
                .stream().collect(Collectors.toMap(Role::getId, Function.identity()));

        return getLevels(companyName).stream().map(l -> {
            if (TYPE_USER.equals(l.getApproverType())) {
                UserTable u = users.get(l.getApproverUserId());
                String label = u == null ? "(user removed / inactive)" : fullName(u);
                return new LevelView(l, label, u == null ? "" : nz(u.getDepartment()), u == null ? 0 : 1);
            }
            Role r = roles.get(l.getApproverRoleId());
            long members = userRepository
                    .findByRoleIdAndCompanyNameAndStatus(l.getApproverRoleId(), companyName, "ACTIVE").size();
            return new LevelView(l, r == null ? "(role removed)" : "Role: " + r.getRoleName(), "", (int) members);
        }).collect(Collectors.toList());
    }

    @Transactional
    public void addLevel(String companyName, String levelName, String approverType,
                         Long approverUserId, Long approverRoleId, String createdBy) {
        validate(companyName, levelName, approverType, approverUserId, approverRoleId);

        List<ApprovalLevel> existing = getLevels(companyName);

        ApprovalLevel l = new ApprovalLevel();
        l.setCompanyName(companyName);
        l.setLevelNo(existing.size() + 1);
        applyFields(l, levelName, approverType, approverUserId, approverRoleId);
        l.setStatus("ACTIVE");
        l.setCreatedBy(createdBy);
        levelRepository.save(l);
    }

    @Transactional
    public void updateLevel(Long id, String companyName, String levelName, String approverType,
                            Long approverUserId, Long approverRoleId) {
        validate(companyName, levelName, approverType, approverUserId, approverRoleId);
        ApprovalLevel l = findOwned(id, companyName);
        applyFields(l, levelName, approverType, approverUserId, approverRoleId);
        levelRepository.save(l);
    }

    @Transactional
    public void deleteLevel(Long id, String companyName) {
        ApprovalLevel l = findOwned(id, companyName);
        levelRepository.delete(l);
        renumber(companyName);
    }

    @Transactional
    public void move(Long id, String companyName, String direction) {
        List<ApprovalLevel> levels = getLevels(companyName);
        int idx = -1;
        for (int i = 0; i < levels.size(); i++) {
            if (levels.get(i).getId().equals(id)) { idx = i; break; }
        }
        if (idx < 0) throw new IllegalArgumentException("Level not found.");

        int swapWith = "up".equals(direction) ? idx - 1 : idx + 1;
        if (swapWith < 0 || swapWith >= levels.size()) return;

        ApprovalLevel a = levels.get(idx);
        ApprovalLevel b = levels.get(swapWith);
        int tmp = a.getLevelNo();
        a.setLevelNo(b.getLevelNo());
        b.setLevelNo(tmp);
        levelRepository.saveAll(List.of(a, b));
    }

    private void renumber(String companyName) {
        List<ApprovalLevel> levels = getLevels(companyName);
        int n = 1;
        for (ApprovalLevel l : levels) l.setLevelNo(n++);
        levelRepository.saveAll(levels);
    }

    private ApprovalLevel findOwned(Long id, String companyName) {
        ApprovalLevel l = levelRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Level not found."));
        if (!l.getCompanyName().equals(companyName)) {
            throw new IllegalArgumentException("Level not found.");
        }
        return l;
    }

    private void applyFields(ApprovalLevel l, String levelName, String approverType,
                             Long approverUserId, Long approverRoleId) {
        l.setLevelName(levelName.trim());
        l.setApproverType(approverType);
        if (TYPE_USER.equals(approverType)) {
            l.setApproverUserId(approverUserId);
            l.setApproverRoleId(null);
        } else {
            l.setApproverRoleId(approverRoleId);
            l.setApproverUserId(null);
        }
    }

    private void validate(String companyName, String levelName, String approverType,
                          Long approverUserId, Long approverRoleId) {
        if (levelName == null || levelName.isBlank()) {
            throw new IllegalArgumentException("Level name is required (e.g. \"Accounts Verification\").");
        }
        if (TYPE_USER.equals(approverType)) {
            if (approverUserId == null) throw new IllegalArgumentException("Select an approver.");
            UserTable u = userRepository.findById(approverUserId)
                    .orElseThrow(() -> new IllegalArgumentException("Selected user does not exist."));
            if (!companyName.equals(u.getCompanyName())) {
                throw new IllegalArgumentException("Approver must belong to the same company.");
            }
        } else if (TYPE_ROLE.equals(approverType)) {
            if (approverRoleId == null) throw new IllegalArgumentException("Select a role.");
            if (!roleRepository.existsById(approverRoleId)) {
                throw new IllegalArgumentException("Selected role does not exist.");
            }
        } else {
            throw new IllegalArgumentException("Approver type must be USER or ROLE.");
        }
    }

    public static String fullName(UserTable u) {
        String last = u.getLastName() == null ? "" : " " + u.getLastName();
        return (u.getFirstName() + last).trim();
    }

    private static String nz(String s) { return s == null ? "" : s; }
}