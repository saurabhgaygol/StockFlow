package com.stockmanagement.service;



import com.stockmanagement.dto.MenuChildView;
import com.stockmanagement.dto.MenuModuleView;
import com.stockmanagement.dto.PermissionResponseDTO;
import com.stockmanagement.entity.Menu;
import com.stockmanagement.repository.MenuRepository;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class SidebarMenuService {

    private final MenuRepository menuRepository;

    public SidebarMenuService(MenuRepository menuRepository) {
        this.menuRepository = menuRepository;
    }

    public List<MenuModuleView> buildMenu(PermissionResponseDTO permissionResponse) {

        Map<String, Menu> menuByName = new HashMap<>();
        for (Menu m : menuRepository.findByStatusOrderByDisplayOrderAsc("ACTIVE")) {
            menuByName.put(m.getMenuName().trim().toLowerCase(), m);
        }

        List<MenuModuleView> menu = new ArrayList<>();

        for (PermissionResponseDTO.ModuleDTO moduleDto : permissionResponse.getModules()) {

            String moduleName = moduleDto.getModule();
            List<MenuChildView> children = new ArrayList<>();

            for (PermissionResponseDTO.ChildDTO childDto : moduleDto.getChildren()) {
                String childName = childDto.getName();
                String url = resolveUrl(moduleName, childName, menuByName);
                children.add(new MenuChildView(childName, url, childDto.getPermissions()));
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