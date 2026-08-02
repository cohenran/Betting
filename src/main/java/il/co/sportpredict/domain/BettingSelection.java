package il.co.sportpredict.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One line of a betting form, optionally bound to a fixture we know. */
@Entity
@Table(name = "betting_selection")
@Getter
@Setter
@NoArgsConstructor
public class BettingSelection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "round_id", nullable = false)
    private BettingRound round;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "raw_text", columnDefinition = "text")
    private String rawText;

    @Column(name = "competition_raw", length = 240)
    private String competitionRaw;

    @Column(name = "home_raw", length = 240)
    private String homeRaw;

    @Column(name = "away_raw", length = 240)
    private String awayRaw;

    private Instant kickoff;

    @Column(name = "odds_home")
    private Double oddsHome;

    @Column(name = "odds_draw")
    private Double oddsDraw;

    @Column(name = "odds_away")
    private Double oddsAway;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fixture_id")
    private Fixture fixture;

    @Column(name = "match_confidence")
    private Double matchConfidence;
}
