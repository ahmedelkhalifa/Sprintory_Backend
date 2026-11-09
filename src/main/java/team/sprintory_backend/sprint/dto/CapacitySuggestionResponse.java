package team.sprintory_backend.sprint.dto;

import java.util.List;

public record CapacitySuggestionResponse(Integer suggestedCapacity, Double averageVelocity,
        int normalPersonDays, int availablePersonDays, int workingDays,
        List<MemberAvailability> members) {
    public record MemberAvailability(Long userId, int availableDays) {}
}
