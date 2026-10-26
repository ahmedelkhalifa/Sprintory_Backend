package team.sprintory_backend.project.exceptions;

/** Another project of the same workspace already uses this key (story 8). */
public class ProjectKeyTakenException extends RuntimeException {

    public ProjectKeyTakenException(String message) {
        super(message);
    }
}