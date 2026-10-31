package com.stockmanagement.config;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Single, safe place to work out the real client IP.
 *
 * Rules:
 *  - X-Forwarded-For is believed ONLY when trustProxy is true AND the TCP peer itself is a
 *    loopback / private address (i.e. our own nginx / load balancer). A direct internet caller can
 *    never fake its IP by sending the header.
 *  - The header is read from the RIGHT: the proxy appends the address it actually saw, so the value
 *    {@code trustedHops} entries from the end is the real client. Entries further left are
 *    client-supplied and are ignored.
 *  - The chosen value must be a plain IP literal (no host names, so no DNS lookups, no junk
 *    stuffed into logs or rate-limit maps). Otherwise we fall back to the TCP peer address.
 */
public final class ClientIp {

    private ClientIp() {}

    public static String resolve(HttpServletRequest req, boolean trustProxy, int trustedHops) {
        String peer = req.getRemoteAddr();
        if (!trustProxy || !isPrivateOrLoopback(peer)) return peer;

        String header = req.getHeader("X-Forwarded-For");
        if (header == null || header.isBlank() || header.length() > 2000) return peer;

        List<String> parts = new ArrayList<>();
        for (String p : header.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) parts.add(t);
        }
        int hops = Math.max(1, trustedHops);
        if (parts.size() < hops) return peer;
        String candidate = parts.get(parts.size() - hops);
        return isIpLiteral(candidate) ? candidate : peer;
    }

    /**
     * Parses a comma-separated list of trusted IP addresses (exact IPs only, e.g. "203.0.113.5, 198.51.100.7").
     * Anything that is not a plain IP literal is ignored, so a typo can never widen access by accident.
     * Empty / missing list = empty set = nobody is special.
     */
    public static Set<String> parseIpList(String csv) {
        Set<String> out = new HashSet<>();
        if (csv == null) return out;
        for (String p : csv.split(",")) {
            String t = p.trim();
            if (isIpLiteral(t)) out.add(t);
        }
        return out;
    }

    /** True if the (already resolved) client IP is in the trusted set. */
    public static boolean isTrusted(Set<String> trusted, String ip) {
        return ip != null && !trusted.isEmpty() && trusted.contains(ip);
    }

    /** True for 127.x, ::1, 10.x, 172.16-31.x, 192.168.x, fe80::, fc00::/7. */
    public static boolean isPrivateOrLoopback(String addr) {
        if (!isIpLiteral(addr)) return false;
        try {
            InetAddress a = InetAddress.getByName(addr);   // literal only, no DNS
            return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress()
                    || (a.getAddress().length == 16 && (a.getAddress()[0] & 0xFE) == 0xFC);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isLoopback(String addr) {
        if (!isIpLiteral(addr)) return false;
        try {
            return InetAddress.getByName(addr).isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }

    /** Cheap literal check: only digits / hex / '.' / ':' and a sane length. Never resolves a host name. */
    static boolean isIpLiteral(String s) {
        if (s == null || s.isEmpty() || s.length() > 45) return false;
        boolean hasDot = false, hasColon = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '.') hasDot = true;
            else if (c == ':') hasColon = true;
            else if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
        }
        if (!hasDot && !hasColon) return false;
        if (hasDot && !hasColon) {   // plain IPv4: exactly 4 numeric parts 0-255
            String[] o = s.split("\\.", -1);
            if (o.length != 4) return false;
            for (String p : o) {
                if (p.isEmpty() || p.length() > 3) return false;
                for (int i = 0; i < p.length(); i++) if (p.charAt(i) < '0' || p.charAt(i) > '9') return false;
                if (Integer.parseInt(p) > 255) return false;
            }
        }
        return true;
    }
}