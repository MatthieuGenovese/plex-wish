import { ChangeDetectionStrategy, Component, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { LibraryApi } from '../../core/library-api';
import { loadOn } from '../../shared/load-state';
import { Poster } from '../../shared/poster';
import { Rail } from '../../shared/rail';

export const ROLE_LABELS: Record<string, string> = { MAIN: 'Principal', SUPPORTING: 'Secondaire' };

/**
 * Distribution d'un animé, en rangée d'avatars ronds : photo (ou initiales) et nom du comédien japonais (lien vers
 * sa page), personnage joué, rôle principal signalé. Jamais d'image de personnage. Rien n'est affiché sans
 * distribution ou en cas d'erreur (la fiche reste utilisable).
 */
@Component({
  selector: 'app-cast-section',
  imports: [RouterLink, Poster, Rail],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (cast().data; as c) {
      @if (c.items.length > 0) {
        <app-rail heading="Distribution" id="cast-title">
          <ul class="rail-list cast-list">
            @for (e of c.items; track $index) {
              <li class="entry">
                @if (e.person; as p) {
                  <a class="person-chip" [routerLink]="['/personne', p.id]"
                     [attr.aria-label]="p.name + ', voix de ' + e.character.name + ' (' + roles[e.role] + ')'">
                    <app-poster variant="round" [title]="p.name" [url]="p.imageUrl" />
                    <span class="name">{{ p.name }}</span>
                    <span class="character" [attr.title]="e.character.nativeName">{{ e.character.name }}</span>
                    <span class="badge" [class.badge-success]="e.role === 'MAIN'">{{ roles[e.role] }}</span>
                  </a>
                } @else {
                  <div class="person-chip">
                    <app-poster variant="round" [title]="e.character.name" />
                    <span class="name muted">Voix non renseignée</span>
                    <span class="character" [attr.title]="e.character.nativeName">{{ e.character.name }}</span>
                    <span class="badge" [class.badge-success]="e.role === 'MAIN'">{{ roles[e.role] }}</span>
                  </div>
                }
              </li>
            }
          </ul>
        </app-rail>
        @if (c.source) {
          <p class="source">Distribution (voix japonaises) :
            @if (c.sourceUrl) { <a [href]="c.sourceUrl" target="_blank" rel="noopener noreferrer">{{ c.source }}</a> } @else { {{ c.source }} }
          </p>
        }
      }
    }
  `,
})
export class CastSection {
  private readonly api = inject(LibraryApi);
  readonly animeId = input.required<string>();
  protected readonly roles = ROLE_LABELS;
  protected readonly cast = loadOn(this.animeId, (id) => this.api.cast(id));
}
