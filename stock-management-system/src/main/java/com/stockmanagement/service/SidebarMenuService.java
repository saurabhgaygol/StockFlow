package com.stockmanagement.service;

import com.stockmanagement.dto.MenuChildView;
import com.stockmanagement.dto.MenuModuleView;
import com.stockmanagement.dto.PermissionResponseDTO;
import com.stockmanagement.entity.Menu;
import com.stockmanagement.repository.MenuRepository;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SidebarMenuService {

    private final MenuRepository menuRepository;

    private final CompanyFeatureService companyFeatureService;

    public SidebarMenuService(MenuRepository menuRepository, CompanyFeatureService companyFeatureService) {
        this.menuRepository = menuRepository;
        this.companyFeatureService = companyFeatureService;
    }

    /** Menu entries that show only when the logged-in user's company has the feature ON. */
    private static final Map<String, String> FEATURE_GATED = Map.of(
            "products & variants", CompanyFeatureService.RETAIL_VARIANT_STOCK,
            "offers & discounts", CompanyFeatureService.RETAIL_OFFERS);

    private Set<String> enabledFeaturesForCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof CustomUserDetails cud && cud.getUser() != null) {
            return companyFeatureService.enabledCodes(cud.getUser().getCompanyName());
        }
        return Set.of();
    }

    public List<MenuModuleView> buildMenu(PermissionResponseDTO permissionResponse) {

        Map<String, Menu> menuByName = new HashMap<>();
        for (Menu m : menuRepository.findByStatusOrderByDisplayOrderAsc("ACTIVE")) {
            menuByName.put(m.getMenuName().trim().toLowerCase(), m);
        }

        List<MenuModuleView> menu = new ArrayList<>();
        Set<String> enabledFeatures = enabledFeaturesForCurrentUser();

        for (PermissionResponseDTO.ModuleDTO moduleDto : permissionResponse.getModules()) {

            String moduleName = moduleDto.getModule();
            List<MenuChildView> children = new ArrayList<>();
            boolean hiddenByFeature = false;

            for (PermissionResponseDTO.ChildDTO childDto : moduleDto.getChildren()) {
                String childName = childDto.getName();

                // CHANGE: AI Assistant sidebar me nahi dikhana (top bar ka icon alag hai)
                if ("AI Assistant".equalsIgnoreCase(childName.trim())) {
                    continue;
                }

                // CHANGE: API Keys bhi sidebar me nahi (Settings page ke "API Keys" tab se khulta hai)
                if ("API Keys".equalsIgnoreCase(childName.trim())) {
                    continue;
                }

                // CHANGE: Super Admin sirf ek permission hai, iska koi page nahi (sidebar me nahi)
                if ("Super Admin".equalsIgnoreCase(childName.trim())) {
                    continue;
                }

                // Retail menu: company ki feature OFF ho to dikhana nahi
                String gate = FEATURE_GATED.get(childName.trim().toLowerCase());
                if (gate != null && !enabledFeatures.contains(gate)) {
                    hiddenByFeature = true;
                    continue;
                }

                String url = resolveUrl(moduleName, childName, menuByName);
                children.add(new MenuChildView(childName, url, childDto.getPermissions()));
            }

            // sab children feature-gated the aur OFF the -> poora module bhi mat dikhao
            if (children.isEmpty() && hiddenByFeature) {
                continue;
            }
            menu.add(new MenuModuleView(moduleName, children));
        }

        return menu;
    }

    private String resolveUrl(String moduleName, String childName, Map<String, Menu> menuByName) {

        Menu byChild = menuByName.get(childName.trim().toLowerCase());
        if (byChild != null && byChild.getMenuUrl() != null && !byChild.getMenuUrl().isBlank()) {
            return byChild.getMenuUrl();
        }

        Menu byModule = menuByName.get(moduleName.trim().toLowerCase());
        if (byModule != null && byModule.getMenuUrl() != null && !byModule.getMenuUrl().isBlank()) {
            return byModule.getMenuUrl();
        }

        return "/" + slug(moduleName) + "/" + slug(childName);
    }

    private String slug(String value) {
        return value.trim().toLowerCase().replaceAll("\\s+", "-");
    }
}