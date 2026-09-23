import { Injectable } from '@angular/core';

/**
 * WakeLockService
 *
 * Hält den Rechner wach, solange eine Analyse läuft. Hintergrund: Geht der
 * Mac (bzw. das Notebook) in den Ruhezustand, friert Docker Desktop alle
 * Container ein und die laufende Analyse bricht ab - kein Service kann in
 * diesem Zustand weiterarbeiten.
 *
 * Technik: Screen Wake Lock API. Der Browser hält damit den Bildschirm an,
 * und der Rechner geht nicht wegen Inaktivität schlafen. Grenzen:
 *  - Der Browser gibt die Sperre automatisch frei, sobald der Tab nicht mehr
 *    sichtbar ist (anderer Tab aktiv, Fenster minimiert oder vollständig
 *    verdeckt). Wird der Tab wieder sichtbar, fordern wir sie erneut an.
 *  - Zuklappen des Deckels schickt den Rechner trotzdem schlafen.
 *  - Nur in sicheren Kontexten verfügbar (https oder http://localhost).
 * Wo die API fehlt oder abgelehnt wird (z.B. Energiesparmodus), passiert
 * nichts - die Analyse läuft dann einfach wie bisher ohne Sperre.
 */
@Injectable({ providedIn: 'root' })
export class WakeLockService {
  private sentinel: WakeLockSentinel | null = null;
  private wanted = false;
  private requesting = false;

  constructor() {
    // Beim Tab-Wechsel löst der Browser die Sperre selbst - deshalb neu anfordern
    document.addEventListener('visibilitychange', () => {
      if (this.wanted && document.visibilityState === 'visible') {
        void this.request();
      }
    });
  }

  /** Ruhezustand verhindern (z.B. beim Start einer Analyse). */
  acquire(): void {
    this.wanted = true;
    void this.request();
  }

  /** Sperre wieder freigeben (Analyse beendet, abgebrochen oder fehlgeschlagen). */
  release(): void {
    this.wanted = false;
    const sentinel = this.sentinel;
    this.sentinel = null;
    sentinel?.release().catch(() => {
      // bereits vom Browser freigegeben - nichts zu tun
    });
  }

  private async request(): Promise<void> {
    if (!('wakeLock' in navigator) || this.sentinel || this.requesting) {
      return;
    }
    this.requesting = true;
    try {
      const sentinel = await navigator.wakeLock.request('screen');
      // Die Analyse kann schon zu Ende sein, während die Anfrage lief
      if (!this.wanted) {
        await sentinel.release();
        return;
      }
      this.sentinel = sentinel;
      sentinel.addEventListener('release', () => {
        if (this.sentinel === sentinel) {
          this.sentinel = null;
        }
      });
    } catch (e) {
      console.warn('Wake Lock nicht verfügbar – Rechner kann während der Analyse einschlafen', e);
    } finally {
      this.requesting = false;
    }
  }
}
