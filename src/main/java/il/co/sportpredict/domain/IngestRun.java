package il.co.sportpredict.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "ingest_run")
@Getter
@Setter
@NoArgsConstructor
public class IngestRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Sport sport;

    @Column(name = "from_date")
    private LocalDate fromDate;

    @Column(name = "to_date")
    private LocalDate toDate;

    @Column(nullable = false)
    private int requests = 0;

    @Column(nullable = false)
    private int records = 0;

    @Column(nullable = false)
    private int created = 0;

    @Column(nullable = false)
    private int updated = 0;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(columnDefinition = "text")
    private String error;

    public IngestRun(String provider, Sport sport, LocalDate fromDate, LocalDate toDate) {
        this.provider = provider;
        this.sport = sport;
        this.fromDate = fromDate;
        this.toDate = toDate;
    }
}
