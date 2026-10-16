package com.stockmanagement.aiagent;

import java.util.Set;

/** Logged-in user ka snapshot: server khud banata hai, AI isko kabhi badal nahi sakta. */
public record AgentUser(Long userId, String username, Long roleId, String company, Set<String> permissions) {

    public boolean has(String code) {
        return permissions.contains(code);
    }

    public boolean hasAny(Set<String> codes) {
        for (String c : codes) if (permissions.contains(c)) return true;
        return false;
    }

    /** Company-wide dekhne ka haq (AI me super admin bhi sirf apni company dekhta hai). */
    public boolean viewAll() {
        return permissions.contains("SUPER_ADMIN") || permissions.contains("STOCK_OUT_VIEW_ALL");
    }
}