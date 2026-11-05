package team.sprintory_backend.chat.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import team.sprintory_backend.project.models.Project;
import team.sprintory_backend.user.models.User;

import java.time.Instant;

@Entity
@Table(name = "project_chat_messages")
@Getter
@Setter
@NoArgsConstructor
public class ProjectChatMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Project project;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", updatable = false)
    @OnDelete(action = OnDeleteAction.SET_NULL)
    private User author;

    @Column(name = "author_name", nullable = false, length = 512, updatable = false)
    private String authorName;

    // Only validated TipTap JSON. Null after deletion; never persist unsanitized HTML.
    @Column(name = "content_json", columnDefinition = "text")
    private String contentJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "edited_at")
    private Instant editedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
