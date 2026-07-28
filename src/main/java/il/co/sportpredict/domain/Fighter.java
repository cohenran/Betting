package il.co.sportpredict.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "fighter")
@Getter
@Setter
@NoArgsConstructor
public class Fighter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "normalized_name", nullable = false, length = 200, unique = true)
    private String normalizedName;

    @Column(length = 160)
    private String nickname;

    @Column(name = "height_cm")
    private Double heightCm;

    @Column(name = "reach_cm")
    private Double reachCm;

    @Column(name = "weight_kg")
    private Double weightKg;

    @Column(length = 32)
    private String stance;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(nullable = false)
    private int wins = 0;

    @Column(nullable = false)
    private int losses = 0;

    @Column(nullable = false)
    private int draws = 0;

    @Column(name = "win_streak", nullable = false)
    private int winStreak = 0;

    @Column(nullable = false)
    private double elo = 1500.0;

    @Column(name = "strikes_per_min")
    private Double strikesPerMin;

    @Column(name = "strike_accuracy")
    private Double strikeAccuracy;

    @Column(name = "takedowns_avg")
    private Double takedownsAvg;

    @Column(name = "submissions_avg")
    private Double submissionsAvg;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Fighter(String name, String normalizedName) {
        this.name = name;
        this.normalizedName = normalizedName;
    }
}
