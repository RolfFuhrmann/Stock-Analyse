import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

import { MlModelInfo } from '../models/stock.models';

/**
 * MlService
 *
 * Modell-Info des ML-Service für die Einstellungen. Der Browser spricht nur
 * mit dem agent-service-java, der die Antwort des ml-service durchreicht:
 *   GET /ml/info – Trainingsstand, Metriken, Merkmals-Wichtigkeit, Diagnose
 * Die Erklärung einzelner Modellwerte kommt dagegen direkt im Analyse-Ergebnis
 * (Feld ml_explanation) und braucht keinen eigenen Aufruf.
 */
@Injectable({ providedIn: 'root' })
export class MlService {
  private readonly http     = inject(HttpClient);
  private readonly agentUrl = 'http://localhost:8016';

  getModelInfo(): Observable<MlModelInfo> {
    return this.http.get<MlModelInfo>(`${this.agentUrl}/ml/info`);
  }
}
