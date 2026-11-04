package team.sprintory_backend.notification.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import team.sprintory_backend.exceptionHandler.BadRequestException;
import team.sprintory_backend.notification.dto.NotificationPreferenceResponse;
import team.sprintory_backend.notification.dto.NotificationPreferencesResponse;
import team.sprintory_backend.notification.dto.UpdateNotificationPreferencesRequest;
import team.sprintory_backend.notification.dto.UpdateNotificationPreferencesRequest.Change;
import team.sprintory_backend.notification.models.NotificationCategory;
import team.sprintory_backend.notification.models.NotificationChannel;
import team.sprintory_backend.notification.models.NotificationPreference;
import team.sprintory_backend.notification.models.NotificationPreferenceId;
import team.sprintory_backend.notification.repositories.NotificationPreferenceRepository;
import team.sprintory_backend.user.models.User;

class NotificationPreferenceServiceTest {

    private NotificationPreferenceRepository repository;
    private NotificationPreferenceService service;
    private User user;

    @BeforeEach
    void setUp() {
        repository = mock(NotificationPreferenceRepository.class);
        service = new NotificationPreferenceService(repository);
        user = User.builder().id(17L).firstName("Ada").lastName("Lovelace").email("ada@example.com").build();
    }

    private static UpdateNotificationPreferencesRequest request(Change... changes) {
        return new UpdateNotificationPreferencesRequest(Arrays.asList(changes));
    }

    private static NotificationPreferenceResponse row(NotificationPreferencesResponse matrix, NotificationCategory c) {
        return matrix.preferences().stream().filter(r -> r.category() == c).findFirst().orElseThrow();
    }

    private static NotificationPreferenceId id(Long userId, NotificationCategory c, NotificationChannel ch) {
        return new NotificationPreferenceId(userId, c, ch);
    }

    // --- defaults (contract §6) ---

    @Test
    void theDefaultsMatchTheContract() {
        for (NotificationCategory c : NotificationCategory.values()) {
            assertTrue(c.defaultInApp(), c + " in-app is on by default");
        }
        assertTrue(NotificationCategory.ASSIGNMENT.defaultEmail());
        assertTrue(NotificationCategory.MENTION.defaultEmail());
        assertFalse(NotificationCategory.COMMENT.defaultEmail());
        assertFalse(NotificationCategory.STATUS_CHANGE.defaultEmail());
        assertFalse(NotificationCategory.PROJECT_MEMBERSHIP.defaultEmail());
        assertFalse(NotificationCategory.SPRINT.defaultEmail());
        assertTrue(NotificationCategory.INVITATION.locked());
        assertTrue(NotificationCategory.SECURITY.locked());
        assertEquals(2, Arrays.stream(NotificationCategory.values()).filter(NotificationCategory::locked).count());
    }

    @Test
    void aNewUserGetsEveryCategoryWithItsDefaultsInOrder() {
        when(repository.findByIdUserId(17L)).thenReturn(List.of());

        NotificationPreferencesResponse matrix = service.get(user);

        assertEquals(Arrays.asList(NotificationCategory.values()),
                matrix.preferences().stream().map(NotificationPreferenceResponse::category).toList());
        for (NotificationPreferenceResponse r : matrix.preferences()) {
            assertEquals(r.defaultInApp(), r.inApp());
            assertEquals(r.defaultEmail(), r.email());
            assertEquals(r.category().locked(), r.locked());
        }
    }

    @Test
    void aStoredSwitchReplacesItsDefaultOnly() {
        when(repository.findByIdUserId(17L)).thenReturn(List.of(
                new NotificationPreference(user, NotificationCategory.STATUS_CHANGE, NotificationChannel.EMAIL, true)));

        NotificationPreferenceResponse status = row(service.get(user), NotificationCategory.STATUS_CHANGE);

        assertTrue(status.email());
        assertTrue(status.inApp());
        assertFalse(status.defaultEmail());
    }

    @Test
    void aLockedCategoryIsShownOnEvenIfARowSaysOtherwise() {
        when(repository.findByIdUserId(17L)).thenReturn(List.of(
                new NotificationPreference(user, NotificationCategory.SECURITY, NotificationChannel.EMAIL, false)));

        NotificationPreferenceResponse security = row(service.get(user), NotificationCategory.SECURITY);

        assertTrue(security.email());
        assertTrue(security.locked());
    }

    // --- isEnabled (used by the listener) ---

    @Test
    void isEnabledFallsBackToTheDefault() {
        when(repository.findById(any())).thenReturn(Optional.empty());

        assertTrue(service.isEnabled(17L, NotificationCategory.ASSIGNMENT, NotificationChannel.EMAIL));
        assertFalse(service.isEnabled(17L, NotificationCategory.STATUS_CHANGE, NotificationChannel.EMAIL));
        assertTrue(service.isEnabled(17L, NotificationCategory.STATUS_CHANGE, NotificationChannel.IN_APP));
    }

    @Test
    void isEnabledUsesTheStoredSwitch() {
        when(repository.findById(id(17L, NotificationCategory.STATUS_CHANGE, NotificationChannel.IN_APP)))
                .thenReturn(Optional.of(new NotificationPreference(user, NotificationCategory.STATUS_CHANGE,
                        NotificationChannel.IN_APP, false)));

        assertFalse(service.isEnabled(17L, NotificationCategory.STATUS_CHANGE, NotificationChannel.IN_APP));
    }

    @Test
    void isEnabledIsAlwaysTrueForLockedCategoriesWithoutAQuery() {
        assertTrue(service.isEnabled(17L, NotificationCategory.INVITATION, NotificationChannel.EMAIL));
        assertTrue(service.isEnabled(17L, NotificationCategory.SECURITY, NotificationChannel.IN_APP));
        verifyNoInteractions(repository);
    }

    // --- update ---

    @Test
    void turningOffADefaultStoresARow() {
        when(repository.findById(any())).thenReturn(Optional.empty());
        when(repository.findByIdUserId(17L)).thenReturn(List.of());

        service.update(user, request(new Change(NotificationCategory.ASSIGNMENT, null, false)));

        ArgumentCaptor<NotificationPreference> saved = ArgumentCaptor.forClass(NotificationPreference.class);
        verify(repository).save(saved.capture());
        assertEquals(id(17L, NotificationCategory.ASSIGNMENT, NotificationChannel.EMAIL), saved.getValue().getId());
        assertFalse(saved.getValue().isEnabled());
    }

    @Test
    void anExistingRowIsUpdatedInPlace() {
        NotificationPreference stored = new NotificationPreference(user, NotificationCategory.COMMENT,
                NotificationChannel.IN_APP, true);
        when(repository.findById(id(17L, NotificationCategory.COMMENT, NotificationChannel.IN_APP)))
                .thenReturn(Optional.of(stored));
        when(repository.findByIdUserId(17L)).thenReturn(List.of());

        // COMMENT in-app defaults to on, so "false" is a real switch; the stored row flips.
        service.update(user, request(new Change(NotificationCategory.COMMENT, false, null)));

        assertFalse(stored.isEnabled());
        verify(repository, never()).save(any());
    }

    @Test
    void settingAValueBackToItsDefaultDeletesTheRow() {
        when(repository.findByIdUserId(17L)).thenReturn(List.of());

        service.update(user, request(new Change(NotificationCategory.STATUS_CHANGE, true, false)));

        verify(repository).deleteById(id(17L, NotificationCategory.STATUS_CHANGE, NotificationChannel.IN_APP));
        verify(repository).deleteById(id(17L, NotificationCategory.STATUS_CHANGE, NotificationChannel.EMAIL));
        verify(repository, never()).save(any());
    }

    @Test
    void aNullChannelIsLeftUnchanged() {
        when(repository.findById(any())).thenReturn(Optional.empty());
        when(repository.findByIdUserId(17L)).thenReturn(List.of());

        service.update(user, request(new Change(NotificationCategory.SPRINT, null, true)));

        verify(repository, never()).findById(id(17L, NotificationCategory.SPRINT, NotificationChannel.IN_APP));
        verify(repository, never()).deleteById(id(17L, NotificationCategory.SPRINT, NotificationChannel.IN_APP));
        verify(repository).save(any(NotificationPreference.class));
    }

    @Test
    void updateReturnsTheWholeMatrix() {
        when(repository.findById(any())).thenReturn(Optional.empty());
        when(repository.findByIdUserId(17L)).thenReturn(List.of(
                new NotificationPreference(user, NotificationCategory.STATUS_CHANGE, NotificationChannel.EMAIL, true)));

        NotificationPreferencesResponse matrix =
                service.update(user, request(new Change(NotificationCategory.STATUS_CHANGE, null, true)));

        assertEquals(NotificationCategory.values().length, matrix.preferences().size());
        assertTrue(row(matrix, NotificationCategory.STATUS_CHANGE).email());
    }

    @Test
    void turningOffALockedCategoryIsRefusedAndNothingIsWritten() {
        BadRequestException e = assertThrows(BadRequestException.class, () -> service.update(user, request(
                new Change(NotificationCategory.ASSIGNMENT, null, false),
                new Change(NotificationCategory.INVITATION, null, false))));

        assertEquals("Invitation notifications cannot be turned off.", e.getMessage());
        verifyNoInteractions(repository);
    }

    @Test
    void turningOffSecurityInAppIsRefused() {
        BadRequestException e = assertThrows(BadRequestException.class, () -> service.update(user,
                request(new Change(NotificationCategory.SECURITY, false, null))));

        assertEquals("Security notifications cannot be turned off.", e.getMessage());
        verifyNoInteractions(repository);
    }

    @Test
    void sendingTrueForALockedCategoryIsAcceptedAndChangesNothing() {
        when(repository.findByIdUserId(17L)).thenReturn(List.of());

        service.update(user, request(new Change(NotificationCategory.SECURITY, true, true)));

        verify(repository, never()).save(any());
        verify(repository, never()).deleteById(any());
    }

    @Test
    void aDuplicateCategoryIsRefusedAndNothingIsWritten() {
        BadRequestException e = assertThrows(BadRequestException.class, () -> service.update(user, request(
                new Change(NotificationCategory.COMMENT, true, null),
                new Change(NotificationCategory.COMMENT, null, true))));

        assertEquals("Category COMMENT appears more than once.", e.getMessage());
        verifyNoInteractions(repository);
    }
}