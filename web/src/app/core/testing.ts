// Données de test partagées par les specs (non incluses dans le build : rien ne les importe hors specs).
import { TokenResponse, User } from './api-types';

export const ADMIN: User = { id: 1, username: 'admin', email: null, role: 'ADMIN', enabled: true, createdAt: '' };
export const USER: User = { ...ADMIN, id: 2, username: 'alice', role: 'USER' };
export const tokens = (accessToken: string, user: User = ADMIN): TokenResponse => ({
  accessToken,
  tokenType: 'Bearer',
  expiresIn: 900,
  user,
});
