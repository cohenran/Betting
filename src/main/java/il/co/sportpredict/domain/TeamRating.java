package il.co.sportpredict.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Online-updated strength state per team. Updated after every finished fixture. */
@Entity
@Table(name = "team_rating")
@Getter
@Setter
@NoArgsConstructor
public class TeamRating {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Sport sport;

    @Column(nullable = false)
    private double elo = 1500.0;

    @Column(nullable = false)
    private int matches = 0;

    /** Exponentially weighted goals/points scored per game. */
    @Column(nullable = false)
    private double scored = 0.0;

    /** Exponentially weighted goals/points conceded per game. */
    @Column(nullable = false)
    private double conceded = 0.0;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public TeamRating(Team team, Sport sport, double elo) {
        this.team = team;
        this.sport = sport;
        this.elo = elo;
    }
}
