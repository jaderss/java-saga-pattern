package dev.jaderss.saga.transfer.api;

import dev.jaderss.saga.transfer.app.SagaChanged;
import dev.jaderss.saga.transfer.persistence.TransferViews;
import dev.jaderss.saga.transfer.persistence.TransferViews.TransferView;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
class ProgressHub {

    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(10);

    private final Map<UUID, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final TransferViews views;

    ProgressHub(TransferViews views) {
        this.views = views;
    }

    SseEmitter subscribe(UUID id) {
        var emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        subscribers.computeIfAbsent(id, _ -> ConcurrentHashMap.newKeySet()).add(emitter);
        Runnable remove = () -> subscribers.computeIfPresent(id, (_, set) -> {
            set.remove(emitter);
            return set.isEmpty() ? null : set;
        });
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(_ -> remove.run());
        views.find(id).ifPresentOrElse(view -> send(emitter, view), emitter::complete);
        return emitter;
    }

    @Async
    @TransactionalEventListener
    void on(SagaChanged changed) {
        var emitters = subscribers.get(changed.sagaId());
        if (emitters == null) {
            return;
        }
        views.find(changed.sagaId()).ifPresent(view -> emitters.forEach(emitter -> send(emitter, view)));
    }

    private static void send(SseEmitter emitter, TransferView view) {
        try {
            emitter.send(SseEmitter.event().name("saga").data(view));
            if (view.transfer().status().terminal()) {
                emitter.complete();
            }
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e);
        }
    }
}
