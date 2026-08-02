package il.co.sportpredict.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Maps a provider's own team id / spelling onto our canonical {@link Team}. */
@Entity
@Table(name = "team_alias")
@Getter
@Setter
@NoArgsConstructor
public class TeamAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(name = "external_id", length = 64)
    private String externalId;

    @Column(name = "raw_name", nullable = false, length = 240)
    private String rawName;

    @Column(name = "normalized_name", nullable = false, length = 240)
    private String normalizedName;

    public TeamAlias(Team team, String provider, String externalId, String rawName, String normalizedName) {
        this.team = team;
        this.provider = provider;
        this.externalId = externalId;
        this.rawName = rawName;
        this.normalizedName = normalizedName;
    }
}
