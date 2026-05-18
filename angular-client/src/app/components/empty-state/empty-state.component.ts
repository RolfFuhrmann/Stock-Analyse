import { Component } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';

/**
 * EmptyStateComponent
 *
 * Wird im Hauptbereich angezeigt, solange noch keine
 * Analyseergebnisse vorliegen.
 */
@Component({
  selector: 'app-empty-state',
  standalone: true,
  imports: [MatIconModule],
  template: `
    <div class="empty-state">
      <mat-icon class="empty-icon">show_chart</mat-icon>
      <div class="empty-title">Bereit zur Analyse</div>
      <div class="empty-sub">Basiswerte eingeben, Quelle wählen und Analyse starten</div>
    </div>
  `,
  styles: [`
    .empty-state {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      height: 60vh;
      gap: 12px;
      color: #9ca3af;
    }
    .empty-icon {
      font-size: 56px;
      width: 56px;
      height: 56px;
      opacity: 0.2;
    }
    .empty-title {
      font-size: 18px;
      font-weight: 500;
      color: #6b7280;
    }
    .empty-sub {
      font-size: 13px;
    }
  `],
})
export class EmptyStateComponent {}
