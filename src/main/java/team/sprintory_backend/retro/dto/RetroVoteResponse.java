package team.sprintory_backend.retro.dto;

/** After a vote or its removal (docs/retrospectives-docs.md §3.6, §3.7): the card's new state and the caller's remaining votes. */
public record RetroVoteResponse(Long itemId, int voteCount, boolean votedByMe, int votesLeft) {
}
