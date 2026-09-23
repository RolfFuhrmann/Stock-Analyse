package rf.stock.agent.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import rf.stock.agent.model.VpnInfo;
import rf.stock.agent.model.VpnRotateResult;
import rf.stock.agent.service.VpnService;

/**
 * VPN-Endpunkte für die Einstellungen im Angular-Client (21.09.).
 * Der Browser darf nicht direkt an Gluetun (CORS, Auth) - der Agent vermittelt.
 */
@RestController
@RequestMapping("/vpn")
public class VpnController {

    private final VpnService vpnService;

    public VpnController(VpnService vpnService) {
        this.vpnService = vpnService;
    }

    /** Tunnel-Status, Ausgangs-IP und Standort. */
    @GetMapping("/info")
    public Mono<VpnInfo> info() {
        return vpnService.info();
    }

    /** Wechselt die VPN-IP. Dauert bis zu einigen Minuten - der Client wartet auf die Antwort. */
    @PostMapping("/rotate")
    public Mono<VpnRotateResult> rotate() {
        return vpnService.rotate();
    }
}
