package org.openbased.library;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Explains why the server cannot use a folder. From the service account's point of view a folder
 * behind a directory it may not enter looks the same as a missing one, so this walks the path from
 * the root to find the first directory that blocks access.
 */
public final class FolderAccess {

    private FolderAccess() {
    }

    /** @return a description of the problem with a suggested fix, or empty if the folder is usable */
    public static Optional<String> problem(Path folder) {
        String user = System.getProperty("user.name");
        Path current = folder.getRoot();
        for (Path part : folder) {
            Path next = current.resolve(part);
            if (!Files.isExecutable(current)) {
                return Optional.of(blocked(user, current, "x"));
            }
            if (!Files.exists(next)) {
                return Optional.of("The folder " + folder + " does not exist (" + next + " was not found).");
            }
            if (!Files.isDirectory(next)) {
                return Optional.of(next + " is a file, not a folder.");
            }
            current = next;
        }
        if (!Files.isReadable(folder) || !Files.isExecutable(folder)) {
            return Optional.of(blocked(user, folder, "rX"));
        }
        return Optional.empty();
    }

    private static String blocked(String user, Path directory, String permission) {
        String recursive = permission.equals("x") ? "" : "-R ";
        return "The server runs as the user '" + user + "', which is not allowed to "
                + (permission.equals("x") ? "enter " : "read ") + directory + ". Grant access with: sudo setfacl "
                + recursive + "-m u:" + user + ":" + permission + " " + directory
                + " (needs the 'acl' package), or change the folder's permissions.";
    }
}
