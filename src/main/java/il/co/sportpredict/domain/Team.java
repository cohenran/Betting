package il.co.sportpredict.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "team")
@Getter
@Setter
@NoArgsConstructor
public class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Sport sport;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "normalized_name", nullable = false, length = 200)
    private String normalizedName;

    @Column(length = 100)
    private String country;

    public Team(Sport sport, String name, String normalizedName, String country) {
        this.sport = sport;
        this.name = name;
        this.normalizedName = normalizedName;
        this.country = country;
    }
}
