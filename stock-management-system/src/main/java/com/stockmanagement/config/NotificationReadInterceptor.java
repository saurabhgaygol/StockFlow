package com.stockmanagement.config;

import com.stockmanagement.service.CustomUserDetails;
import com.stockmanagement.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

@Component
public class NotificationReadInterceptor implements HandlerInterceptor {

    private final NotificationService notificationService;

    public NotificationReadInterceptor(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    // Page khulne se pehle: is URL ki notifications read mark karo
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) return true;

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CustomUserDetails user) {
            notificationService.markReadByUrl(user.getUserId(), request.getRequestURI());
        }
        return true;
    }

    // Page render hone se pehle: bell ka data har page ke model me daalo
    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response,
                           Object handler, ModelAndView modelAndView) {
        if (modelAndView == null || !modelAndView.hasView()) return;

        String viewName = modelAndView.getViewName();
        if (viewName != null && viewName.startsWith("redirect:")) return;

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CustomUserDetails user) {
            modelAndView.addObject("userId", user.getUserId());
            modelAndView.addObject("username", user.getUsername());
            modelAndView.addObject("notifications", notificationService.getRecentNotifications(user.getUserId()));
            modelAndView.addObject("notifUnreadCount", notificationService.getUnreadCount(user.getUserId()));
        }
    }
}