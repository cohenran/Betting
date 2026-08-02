package il.co.sportpredict.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * A stored prediction. Kept even after the event finishes so calibration
 * (log-loss / Brier) can be measured on genuinely out-of-sample forecasts.
 */
@Entity
@Table(name = "prediction")
@Getter
@Setter
@NoArgsConstructor
public class Prediction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Sport sport;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fixture_id")
    private Fixture fixture;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fight_id")
    private Fight fight;

    @Column(nullable = false, length = 64)
    private String model;

    @Column(name = "model_version", length = 40)
    private String modelVersion;

    @Column(name = "p_home")
    private Double pHome;

    @Column(name = "p_draw")
    private Double pDraw;

    @Column(name = "p_away")
    private Double pAway;

    @Column(name = "expected_home")
    private Double expectedHome;

    @Column(name = "expected_away")
    private Double expectedAway;

    @Column(name = "ou_line")
    private Double ouLine;

    @Column(name = "p_over")
    private Double pOver;

    @Column(name = "p_btts")
    private Double pBtts;

    @Column(name = "top_score", length = 16)
    private String topScore;

    private Double confidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private boolean settled = false;

    /** HOME / DRAW / AWAY once known. */
    @Column(length = 8)
    private String outcome;

    @Column(name = "log_loss")
    private Double logLoss;

    private Double brier;
}
