import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

import { VpnInfo, VpnRotateResult } from '../models/stock.models';

/**
 * VpnService
 *
 * Yahoo-Abrufe laufen über einen VPN-Container. Der Browser spricht nicht
 * direkt mit ihm, sondern über den agent-service-java (CORS, Auth):
 *   GET  /vpn/info    – Tunnel-Status, aktuelle IP, Standort
 *   POST /vpn/rotate  – VPN neu starten, um eine andere IP zu bekommen
 *                       (dauert bis zu einigen Minuten)
 */
@Injectable({ providedIn: 'root' })
export class VpnService {
  private readonly http     = inject(HttpClient);
  private readonly agentUrl = 'http://localhost:8016';

  getInfo(): Observable<VpnInfo> {
    return this.http.get<VpnInfo>(`${this.agentUrl}/vpn/info`);
  }

  rotateIp(): Observable<VpnRotateResult> {
    return this.http.post<VpnRotateResult>(`${this.agentUrl}/vpn/rotate`, {});
  }
}
