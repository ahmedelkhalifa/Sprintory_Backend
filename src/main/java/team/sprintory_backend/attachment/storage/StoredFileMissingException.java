package team.sprintory_backend.attachment.storage;

/**
 * An attachment row points at a file that is not in the store (lost volume, manual deletion).
 * This is a server fault, not the caller's: it is answered with the generic 500.
 */
public class StoredFileMissingException extends RuntimeException {

    public StoredFileMissingException(String key) {
        super("No stored file for attachment key " + key);
    }
}