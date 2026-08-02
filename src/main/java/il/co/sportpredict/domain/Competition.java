package il.co.sportpredict.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "competition")
@Getter
@Setter
@NoArgsConstructor
public class Competition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Sport sport;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 100)
    private String country;

    @Column(name = "external_ref", length = 64)
    private String externalRef;

    public Competition(Sport sport, String name, String country, String externalRef) {
        this.sport = sport;
        this.name = name;
        this.country = country;
        this.externalRef = externalRef;
    }
}
