package com.flowify.ettea.hooks;

import java.util.Set;

/** Folder identity and filtering rules for Spotify's native Local Files reader. */
public final class LocalFilesFolderPolicy {
    private LocalFilesFolderPolicy() {
    }

    public static String normalizeFolderPath(String path) {
        if (path == null) return "";
        String normalized = path.trim().replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    public static String folderLabel(String path) {
        String normalized = normalizeFolderPath(path);
        return normalized.isEmpty() ? "Local files root" : normalized;
    }

    public static String parentFolderPath(String filePath, String storageRoot) {
        String file = normalizeFolderPath(filePath);
        int separator = file.lastIndexOf('/');
        String parent = separator < 0 ? "" : file.substring(0, separator);
        String root = normalizeFolderPath(storageRoot);
        if (parent.equals(root)) return "";
        if (!root.isEmpty() && parent.startsWith(root + "/")) {
            return parent.substring(root.length() + 1);
        }
        return parent;
    }

    public static boolean isHidden(Set<String> hiddenFolders, String candidatePath) {
        if (hiddenFolders == null || hiddenFolders.isEmpty()) return false;
        String candidate = normalizeFolderPath(candidatePath);
        for (String hiddenFolder : hiddenFolders) {
            String hidden = normalizeFolderPath(hiddenFolder);
            if (hidden.isEmpty() ? candidate.isEmpty()
                    : candidate.equals(hidden) || candidate.startsWith(hidden + "/")) {
                return true;
            }
        }
        return false;
    }
}
