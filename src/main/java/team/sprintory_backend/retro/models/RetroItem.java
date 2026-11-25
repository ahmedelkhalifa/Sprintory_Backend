package team.sprintory_backend.retro.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import team.sprintory_backend.audit.BaseEntity;
import team.sprintory_backend.user.models.User;

import java.time.Instant;

/**
 * A card on a retro board (R31). The body is normalized TipTap JSON, like a comment. Deleting is
 * soft but, unlike comments, a deleted card is never listed again, so check {@link #isDeleted()}.
 */
@Entity
@Table(name = "retro_items")
@Getter
@Setter
@NoArgsConstructor
public class RetroItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "retro_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Retrospective retro;

    @Enumerated(EnumType.STRING)
    @Column(name = "column_name", nullable = false, updatable = false, length = 20)
    private RetroColumn column;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    /** Null once the account no longer exists. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", updatable = false)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User author;

    /** Set when the text really changed; shown as "(edited)". */
    @Column(name = "edited_at")
    private Instant editedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public RetroItem(Retrospective retro, RetroColumn column, String body, User author) {
        this.retro = retro;
        this.column = column;
        this.body = body;
        this.author = author;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** Written by the given user; false once the author's account is gone. */
    public boolean isWrittenBy(User user) {
        return author != null && author.getId().equals(user.getId());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RetroItem other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
