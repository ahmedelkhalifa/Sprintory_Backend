package team.sprintory_backend.audit.models;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * One old → new value of an audit entry. A null old value means "added", a null new value
 * "removed". Values are display strings and must never hold a password, token or link.
 */
@Embeddable
@Getter
@EqualsAndHashCode
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AuditChange {

    @Column(name = "field", nullable = false, length = 50)
    private String field;

    @Column(name = "old_value", columnDefinition = "text")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "text")
    private String newValue;

    public static AuditChange of(String field, String oldValue, String newValue) {
        return new AuditChange(field, oldValue, newValue);
    }
}