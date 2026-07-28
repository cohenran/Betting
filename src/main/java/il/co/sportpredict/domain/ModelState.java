package il.co.sportpredict.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** Persisted model parameters (Dixon-Coles fit, UFC logistic weights, ...). */
@Entity
@Table(name = "model_state")
@Getter
@Setter
@NoArgsConstructor
public class ModelState {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "model_key", nullable = false, length = 80, unique = true)
    private String modelKey;

    @Column(length = 40)
    private String version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "sample_size")
    private Integer sampleSize;

    @Column(name = "trained_at", nullable = false)
    private Instant trainedAt = Instant.now();

    public ModelState(String modelKey, String payload) {
        this.modelKey = modelKey;
        this.payload = payload;
    }
}
