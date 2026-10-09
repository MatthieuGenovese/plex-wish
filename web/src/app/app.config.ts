import { ApplicationConfig, inject, provideAppInitializer, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { TitleStrategy, provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { routes } from './app.routes';
import { authInterceptor } from './core/auth.interceptor';
import { AuthService } from './core/auth.service';
import { SetupService } from './core/setup.service';
import { AppTitleStrategy } from './core/title-strategy';
import { ThemeService } from './core/theme.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideHttpClient(withFetch(), withInterceptors([authInterceptor])),
    provideRouter(routes, withComponentInputBinding(), withInMemoryScrolling({ scrollPositionRestoration: 'enabled' })),
    // Installation d'abord (D1.3) : tant qu'elle n'est pas terminée, ou par l'adresse locale du NAS, seule la page
    // /installation s'affiche. Sinon, F5 ne déconnecte pas : la session est rétablie (cookie de refresh) avant la
    // première navigation.
    provideAppInitializer(async () => {
      const setup = inject(SetupService);
      const auth = inject(AuthService);
      await setup.load();
      if (!setup.takesOver()) {
        await auth.restoreSession();
      }
    }),
    // Thème (préférence de l'appareil) : appliqué dès le démarrage, suit le système si « Système ».
    provideAppInitializer(() => void inject(ThemeService)),
    { provide: TitleStrategy, useClass: AppTitleStrategy },
  ],
};
