package com.stockmanagement.config;

import com.stockmanagement.dto.MenuChildView;
import com.stockmanagement.dto.MenuModuleView;
import com.stockmanagement.entity.Permission;
import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.SidebarMenuService;
import com.stockmanagement.service.UserPermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class PermissionInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(PermissionInterceptor.class);

    public static final String DENIED_PATH = "/access-denied";
    private static final String SUPER_ADMIN = "SUPER_ADMIN";
    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

    private final UserPermissionService permissionService;
    private final SidebarMenuService sidebarMenuService;

    public PermissionInterceptor(UserPermissionService permissionService,
                                 SidebarMenuService sidebarMenuService) {
        this.permissionService = permissionService;
        this.sidebarMenuService = sidebarMenuService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {

        if (!(handler instanceof HandlerMethod hm)) {
            return true;   // static files etc.
        }

        RequirePermission required = hm.getMethodAnnotation(RequirePermission.class);
        if (required == null) {
            required = AnnotatedElementUtils.findMergedAnnotation(hm.getBeanType(), RequirePermission.class);
        }
        if (required == null) {
            return true;   // is endpoint pe chit nahi lagi
        }

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof CustomUserDetails user)) {
            return true;   // login Spring Security sambhalta hai
        }

        Set<String> codes = permissionService.getAllowedPermissionDetails(user.getUserId()).stream()
                .map(Permission::getPermissionCode)
                .collect(Collectors.toSet());

        if (codes.contains(SUPER_ADMIN)) {
            return true;
        }
        for (String needed : required.value()) {
            if (codes.contains(needed)) {
                return true;
            }
        }

        String path = PATH_HELPER.getPathWithinApplication(request);
        log.warn("ACCESS DENIED user={} {} {} needs one of {}", user.getUsername(), request.getMethod(),
                path, Arrays.toString(required.value()));

        // Login ke baad /dashboard landing hai: permission na ho to pehla allowed page kholo
        if ("/dashboard".equals(path) && "GET".equalsIgnoreCase(request.getMethod())) {
            String first = firstAccessiblePath(user);
            if (first != null) {
                response.sendRedirect(request.getContextPath() + first);
                return false;
            }
        }

        respondDenied(request, response);
        return false;
    }

    private String firstAccessiblePath(CustomUserDetails user) {
        try {
            List<MenuModuleView> menu = sidebarMenuService.buildMenu(
                    permissionService.getPermissionResponse(user.getUserId()));
            for (MenuModuleView m : menu) {
                if (m.getChildren() == null) continue;
                for (MenuChildView c : m.getChildren()) {
                    String p = c.getPath();
                    if (p != null && !p.isBlank() && p.startsWith("/") && !p.equals("/dashboard")) {
                        return p;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // menu fail ho to access-denied page par hi jaayenge
        }
        return null;
    }

    public static void respondDenied(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if ("true".equalsIgnoreCase(request.getHeader("HX-Request"))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setHeader("HX-Redirect", request.getContextPath() + DENIED_PATH);
            return;
        }
        String accept = request.getHeader("Accept");
        boolean wantsHtml = accept != null && accept.contains("text/html");
        boolean xhr = "XMLHttpRequest".equalsIgnoreCase(request.getHeader("X-Requested-With"));
        if (wantsHtml && !xhr) {
            response.sendRedirect(request.getContextPath() + DENIED_PATH);
            return;
        }
        response.sendError(HttpServletResponse.SC_FORBIDDEN, "You do not have permission to do this.");
    }
}