package team.sprintory_backend.project.charter.exceptions;

/** A completed or archived project cannot change its charter. */
public class CharterReadOnlyException extends RuntimeException {
    public CharterReadOnlyException() { super("The project charter is read-only in this project state."); }
}
