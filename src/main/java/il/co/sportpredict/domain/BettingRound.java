package il.co.sportpredict.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One scraped betting form (e.g. a Winner 16 / Winner World round). */
@Entity
@Table(name = "betting_round")
@Getter
@Setter
@NoArgsConstructor
public class BettingRound {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32)
    private String provider = "winner";

    @Column(name = "round_code", length = 80)
    private String roundCode;

    @Column(name = "form_name", length = 200)
    private String formName;

    @Column(name = "source_url", nullable = false, columnDefinition = "text")
    private String sourceUrl;

    /** JSON_API, HTML, or PLAYWRIGHT - useful when a parse looks wrong. */
    @Column(name = "fetch_method", length = 32)
    private String fetchMethod;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt = Instant.now();
}
