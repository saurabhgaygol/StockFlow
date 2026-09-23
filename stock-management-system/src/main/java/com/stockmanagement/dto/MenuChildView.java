package com.stockmanagement.dto;

import java.util.List;

public class MenuChildView {

    private final String name;
    private final String path;
    private final List<String> permissions;

    public MenuChildView(String name, String path, List<String> permissions) {
        this.name = name;
        this.path = path;
        this.permissions = permissions;
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    public List<String> getPermissions() {
        return permissions;
    }
}
