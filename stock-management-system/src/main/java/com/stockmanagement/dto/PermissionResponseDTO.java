package com.stockmanagement.dto;

import java.util.List;

public class PermissionResponseDTO {

    private List<ModuleDTO> modules;

    public PermissionResponseDTO() {
    }

    public PermissionResponseDTO(List<ModuleDTO> modules) {
        this.modules = modules;
    }

    public List<ModuleDTO> getModules() {
        return modules;
    }

    public void setModules(List<ModuleDTO> modules) {
        this.modules = modules;
    }

    public static class ModuleDTO {

        private String module;
        private List<ChildDTO> children;

        public ModuleDTO() {
        }

        public ModuleDTO(String module, List<ChildDTO> children) {
            this.module = module;
            this.children = children;
        }

        public String getModule() {
            return module;
        }

        public void setModule(String module) {
            this.module = module;
        }

        public List<ChildDTO> getChildren() {
            return children;
        }

        public void setChildren(List<ChildDTO> children) {
            this.children = children;
        }
    }

    public static class ChildDTO {

        private String name;
        private List<String> permissions;

        public ChildDTO() {
        }

        public ChildDTO(String name, List<String> permissions) {
            this.name = name;
            this.permissions = permissions;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public List<String> getPermissions() {
            return permissions;
        }

        public void setPermissions(List<String> permissions) {
            this.permissions = permissions;
        }
    }
}