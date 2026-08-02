package il.co.sportpredict.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Which provider record a fixture came from. Lets both providers feed the same row. */
@Entity
@Table(name = "fixture_source")
@Getter
@Setter
@NoArgsConstructor
public class FixtureSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fixture_id", nullable = false)
    private Fixture fixture;

    @Column(nullable = false, length = 32)
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Sport sport;

    @Column(name = "external_id", nullable = false, length = 64)
    private String externalId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt = Instant.now();

    public FixtureSource(Fixture fixture, String provider, Sport sport, String externalId, String payload) {
        this.fixture = fixture;
        this.provider = provider;
        this.sport = sport;
        this.externalId = externalId;
        this.payload = payload;
    }
}
