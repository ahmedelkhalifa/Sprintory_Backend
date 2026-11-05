package team.sprintory_backend.notification.events;

import team.sprintory_backend.user.models.User;
import team.sprintory_backend.workspace.models.Workspace;

/**
 * An email was invited to a workspace. The invitation email itself is sent by the invite action;
 * this only adds an in-app notification when the email already has an account. Never carries the
 * token or link.
 */
public record WorkspaceInvited(Workspace workspace, User inviter, String email) {}
