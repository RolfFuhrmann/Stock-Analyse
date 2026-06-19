import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { DataSource, Interval, StockResult } from '../models/stock.models';

/**
 * AnalysisService
 *
 * Öffnet eine SSE-Verbindung zum Agent-Backend und emittiert
 * StockResult-Objekte sobald jeder Ticker analysiert wurde.
 *
 * Stop-Mechanismus:
 *   Jede Analyse erhält eine eindeutige session_id (UUID).
 *   stopStream() sendet POST /analyze/stop an den Agent,
 *   der daraufhin auch den laufenden Data-Service-Abruf abbricht.
 *   Zusätzlich wird der lokale Reader abgebrochen (AbortController).
 */
@Injectable({ providedIn: 'root' })
export class AnalysisService {
  private readonly agentUrl = 'http://localhost:8010';

  /** Aktiver AbortController – ermöglicht sofortigen lokalen Abbruch */
  private _abortController: AbortController | null = null;

  /** Session-ID der laufenden Analyse – für den Stop-Request ans Backend */
  private _sessionId: string | null = null;

  streamAnalysis(
    tickers: string[],
    source: DataSource,
    interval: Interval = '1d',
    lookbackDays = 90
  ): Observable<StockResult> {
    return new Observable<StockResult>((observer) => {
      // Neue Session für diese Analyse
      const sessionId = crypto.randomUUID();
      this._sessionId = sessionId;
      this._abortController = new AbortController();
      const { signal } = this._abortController;

      fetch(`${this.agentUrl}/analyze/stream`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          tickers,
          source,
          interval,
          lookback_days: lookbackDays,
          session_id: sessionId,
        }),
        signal,
      })
        .then((response) => {
          const reader  = response.body!.getReader();
          const decoder = new TextDecoder();
          let buffer    = '';
          let eventType = '';

          const processChunk = ({
            done,
            value,
          }: ReadableStreamReadResult<Uint8Array>): Promise<void> => {
            if (done) {
              observer.complete();
              return Promise.resolve();
            }

            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop() ?? '';

            for (const line of lines) {
              const trimmed = line.trim();
              if (trimmed.startsWith('event:')) {
                eventType = trimmed.slice(6).trim();
              } else if (trimmed.startsWith('data:')) {
                const data = trimmed.slice(5).trim();
                if (eventType === 'result') {
                  try {
                    observer.next(JSON.parse(data));
                  } catch {
                    // Ungültiges JSON ignorieren
                  }
                } else if (eventType === 'done') {
                  observer.complete();
                  return Promise.resolve();
                }
              }
            }
            return reader.read().then(processChunk);
          };

          reader.read().then(processChunk).catch((e) => {
            // AbortError ist kein echter Fehler – der Nutzer hat gestoppt
            if (e?.name !== 'AbortError') {
              observer.error(e);
            } else {
              observer.complete();
            }
          });
        })
        .catch((e) => {
          if (e?.name !== 'AbortError') {
            observer.error(e);
          } else {
            observer.complete();
          }
        });
    });
  }

  /**
   * Stoppt den laufenden Stream auf zwei Ebenen:
   * 1. Backend: POST /analyze/stop → Agent bricht Data-Service-Abruf ab
   * 2. Lokal:   AbortController → Fetch-Verbindung sofort trennen
   */
  stopStream(): void {
    // Zuerst Backend informieren (fire-and-forget)
    if (this._sessionId) {
      fetch(`${this.agentUrl}/analyze/stop`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ session_id: this._sessionId }),
      }).catch(() => {
        // Fehler beim Stop ignorieren – lokaler Abbruch reicht
      });
      this._sessionId = null;
    }

    // Dann lokale Verbindung abbrechen
    this._abortController?.abort();
    this._abortController = null;
  }
}
