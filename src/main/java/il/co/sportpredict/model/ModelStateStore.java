package il.co.sportpredict.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import il.co.sportpredict.domain.ModelState;
import il.co.sportpredict.repo.ModelStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/** Loads/saves model parameters as JSON in the model_state table. */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModelStateStore {

    private final ModelStateRepository repo;
    private final ObjectMapper mapper;

    public <T> Optional<T> load(String key, Class<T> type) {
        return repo.findByModelKey(key).flatMap(state -> {
            try {
                return Optional.of(mapper.readValue(state.getPayload(), type));
            } catch (Exception e) {
                log.warn("model_state '{}' is unreadable, ignoring: {}", key, e.getMessage());
                return Optional.empty();
            }
        });
    }

    @Transactional
    public void save(String key, Object payload, int sampleSize, String version) {
        try {
            String json = mapper.writeValueAsString(payload);
            ModelState state = repo.findByModelKey(key).orElseGet(() -> new ModelState(key, json));
            state.setPayload(json);
            state.setSampleSize(sampleSize);
            state.setVersion(version);
            state.setTrainedAt(Instant.now());
            repo.save(state);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize model state " + key, e);
        }
    }

    public Optional<Instant> trainedAt(String key) {
        return repo.findByModelKey(key).map(ModelState::getTrainedAt);
    }
}
