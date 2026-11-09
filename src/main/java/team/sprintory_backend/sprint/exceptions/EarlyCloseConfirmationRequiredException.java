package team.sprintory_backend.sprint.exceptions;

import java.time.LocalDate;

/** Closing a sprint before its end date needs an explicit confirmation (PMP-41); 409. */
public class EarlyCloseConfirmationRequiredException extends RuntimeException {
    public EarlyCloseConfirmationRequiredException(LocalDate endDate) {
        super("The sprint is planned to end on " + endDate
                + ". Retry with confirmEarlyClose=true to close it early.");
    }
}