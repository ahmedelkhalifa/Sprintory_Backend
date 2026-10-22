package team.sprintory_backend.security.auth.models;

/** What an emailed link is for. A token only works for the purpose it was issued with. */
public enum OneTimeTokenPurpose {
    VERIFY_EMAIL,
    RESET_PASSWORD
}