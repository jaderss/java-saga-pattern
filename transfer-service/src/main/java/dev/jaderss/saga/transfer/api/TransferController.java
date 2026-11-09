package dev.jaderss.saga.transfer.api;

import dev.jaderss.saga.transfer.app.SagaProperties;
import dev.jaderss.saga.transfer.app.TransferSagas;
import dev.jaderss.saga.transfer.persistence.TransferViews;
import dev.jaderss.saga.transfer.persistence.TransferViews.TransferSummary;
import dev.jaderss.saga.transfer.persistence.TransferViews.TransferView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Validated
@RestController
@RequestMapping("/api")
class TransferController {

    private final TransferSagas sagas;
    private final TransferViews views;
    private final ProgressHub hub;
    private final List<Scenario> scenarios;

    TransferController(TransferSagas sagas, TransferViews views, ProgressHub hub, SagaProperties properties) {
        this.sagas = sagas;
        this.views = views;
        this.hub = hub;
        this.scenarios = Scenarios.all(properties.maxAttempts());
    }

    @GetMapping("/scenarios")
    List<Scenario> scenarios() {
        return scenarios;
    }

    @PostMapping("/transfers")
    ResponseEntity<TransferView> start(
            @RequestHeader("Idempotency-Key") @Size(min = 1, max = 100) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        var started = sagas.start(idempotencyKey, request.toOrder(UUID.randomUUID()));
        return ResponseEntity.status(started.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(view(started.id()));
    }

    @PostMapping("/transfers/{id}/approve")
    TransferView approve(@PathVariable UUID id) {
        return decide(id, true);
    }

    @PostMapping("/transfers/{id}/decline")
    TransferView decline(@PathVariable UUID id) {
        return decide(id, false);
    }

    @GetMapping("/transfers")
    List<TransferSummary> recent(@RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {
        return views.recent(limit);
    }

    @GetMapping("/transfers/{id}")
    TransferView view(@PathVariable UUID id) {
        return views.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no transfer " + id));
    }

    private TransferView decide(UUID id, boolean approved) {
        if (!sagas.decide(id, approved)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no transfer " + id);
        }
        return view(id);
    }

    @GetMapping(path = "/transfers/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream(@PathVariable UUID id) {
        view(id);
        return hub.subscribe(id);
    }
}
