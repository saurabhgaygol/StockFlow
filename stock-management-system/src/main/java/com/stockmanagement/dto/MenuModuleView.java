package com.stockmanagement.dto;

import java.util.List;

public class MenuModuleView {

    private final String module;
    private final List<MenuChildView> children;

    public MenuModuleView(String module, List<MenuChildView> children) {
        this.module = module;
        this.children = children;
    }

    public String getModule() {
        return module;
    }

    public List<MenuChildView> getChildren() {
        return children;
    }

    public boolean isFlat() {
        return children != null
                && children.size() == 1
                && children.get(0).getName().trim().equalsIgnoreCase(module.trim());
    }
}