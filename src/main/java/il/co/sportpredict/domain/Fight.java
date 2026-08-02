package il.co.sportpredict.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "fight")
@Getter
@Setter
@NoArgsConstructor
public class Fight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_name", length = 240)
    private String eventName;

    @Column(name = "fight_date", nullable = false)
    private Instant fightDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fighter_a_id", nullable = false)
    private Fighter fighterA;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fighter_b_id", nullable = false)
    private Fighter fighterB;

    @Column(name = "weight_class", length = 80)
    private String weightClass;

    @Column(name = "rounds_scheduled")
    private Integer roundsScheduled;

    @Column(name = "title_fight", nullable = false)
    private boolean titleFight = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private EventStatus status = EventStatus.SCHEDULED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "winner_id")
    private Fighter winner;

    @Column(length = 80)
    private String method;

    @Column(name = "end_round")
    private Integer endRound;

    @Column(nullable = false)
    private boolean learned = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public boolean hasResult() {
        return status.isFinal() && winner != null;
    }
}
