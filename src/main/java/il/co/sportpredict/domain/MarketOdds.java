package il.co.sportpredict.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Median and best market prices for one fixture, kept so the walk-forward backtest can
 * compare the model against the bookmakers without re-fetching history every run.
 *
 * <p>{@code medianDraw} is null for two-way markets (basketball).
 */
@Entity
@Table(name = "market_odds")
@Getter
@Setter
@NoArgsConstructor
public class MarketOdds {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fixture_id", nullable = false)
    private Fixture fixture;

    @Column(nullable = false, length = 32)
    private String provider = "allsports";

    @Column(name = "median_home", nullable = false)
    private double medianHome;

    @Column(name = "median_draw")
    private Double medianDraw;

    @Column(name = "median_away", nullable = false)
    private double medianAway;

    @Column(name = "best_home")
    private Double bestHome;

    @Column(name = "best_draw")
    private Double bestDraw;

    @Column(name = "best_away")
    private Double bestAway;

    @Column(nullable = false)
    private int bookmakers;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt = Instant.now();

    public boolean twoWay() {
        return medianDraw == null;
    }

    /** Overround removed - the book's actual opinion rather than its quoted prices. */
    public double[] impliedProbabilities() {
        double[] raw = twoWay()
                ? new double[]{1 / medianHome, 1 / medianAway}
                : new double[]{1 / medianHome, 1 / medianDraw, 1 / medianAway};
        double total = 0;
        for (double v : raw) {
            total += v;
        }
        double[] out = new double[raw.length];
        for (int i = 0; i < raw.length; i++) {
            out[i] = raw[i] / total;
        }
        return out;
    }
}
