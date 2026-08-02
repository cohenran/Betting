package il.co.sportpredict.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "fight_source")
@Getter
@Setter
@NoArgsConstructor
public class FightSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "fight_id", nullable = false)
    private Fight fight;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(name = "external_id", nullable = false, length = 64)
    private String externalId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt = Instant.now();

    public FightSource(Fight fight, String provider, String externalId, String payload) {
        this.fight = fight;
        this.provider = provider;
        this.externalId = externalId;
        this.payload = payload;
    }
}
