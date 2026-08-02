package il.co.sportpredict.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** How far back the nightly history backfill has reached, per provider+sport. */
@Entity
@Table(name = "ingest_cursor")
@Getter
@Setter
@NoArgsConstructor
public class IngestCursor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Sport sport;

    @Column(name = "oldest_pulled")
    private LocalDate oldestPulled;

    @Column(name = "newest_pulled")
    private LocalDate newestPulled;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public IngestCursor(String provider, Sport sport) {
        this.provider = provider;
        this.sport = sport;
    }
}
