package rf.stock.agent.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import rf.stock.agent.service.MlClient;

/**
 * ML-Endpunkte für den Angular-Client (21.09.). Der Browser spricht nur mit dem
 * Agent - der reicht die Modell-Info des ml-service durch.
 */
@RestController
@RequestMapping("/ml")
public class MlController {

    private final MlClient mlClient;

    public MlController(MlClient mlClient) {
        this.mlClient = mlClient;
    }

    /** Trainingsstand, Metriken, Merkmals-Wichtigkeit und Diagnose des ML-Modells. */
    @GetMapping("/info")
    public Mono<Map<String, Object>> info() {
        return mlClient.fetchModelInfo();
    }
}
