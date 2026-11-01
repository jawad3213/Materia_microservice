import Keycloak, { type KeycloakTokenParsed } from 'keycloak-js';
import { setTokenProvider, SESSION_EXPIRED_EVENT } from '../../../shared/api/axiosClient';
import type { User, UserRole } from '../types/auth.types';

/**
 * Sign-in is Keycloak's (docs/architecture/keycloak-migration.md): its pages ask for the password, reset it and make
 * a new account choose one; this app only holds the tokens keycloak-js obtains (authorization code with PKCE) and
 * renews. Keycloak is served under /auth next to the app unless VITE_KEYCLOAK_URL says otherwise (local development:
 * http://localhost:8180/auth).
 */
export const keycloakConfig = {
  url: import.meta.env.VITE_KEYCLOAK_URL || `${window.location.origin}/auth`,
  realm: import.meta.env.VITE_KEYCLOAK_REALM || 'materia',
  clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID || 'materia-frontend',
};

const ROLES: UserRole[] = ['ADMIN', 'PURCHASER', 'RECEIVER'];
/** The API client, whose roles in the token are the user's permissions (order:read, ...). */
const API_CLIENT = 'materia-api';
/** Renew the access token when it has less than this left (seconds). */
const MIN_VALIDITY = 30;

interface MateriaToken extends KeycloakTokenParsed {
  email?: string;
  name?: string;
  given_name?: string;
  family_name?: string;
}

/** The signed-in user as the access token describes them: the same id as in identity, its role and permissions. */
export function userFromToken(token: MateriaToken | undefined): User | null {
  if (!token?.sub) return null;
  const realmRoles = token.realm_access?.roles ?? [];
  const role = ROLES.find((r) => realmRoles.includes(r));
  if (!role) return null;
  const email = token.email ?? '';
  const name =
    token.name?.trim() ||
    [token.given_name, token.family_name].filter(Boolean).join(' ').trim() ||
    (email ? email.split('@')[0] : 'User');
  return {
    id: token.sub,
    name,
    email,
    role,
    permissions: token.resource_access?.[API_CLIENT]?.roles ?? [],
  };
}

class AuthService {
  private readonly keycloak = new Keycloak(keycloakConfig);
  private initialized: Promise<User | null> | null = null;
  /** What the user changed in the app since the token was issued (their name, on the profile page). */
  private changes: Partial<User> = {};

  /**
   * Restores the Keycloak session, if any, without showing a page (check-sso); resolves to the signed-in user, or
   * null. Called once; later calls share the first.
   */
  init(): Promise<User | null> {
    if (!this.initialized) {
      this.keycloak.onTokenExpired = () => {
        this.keycloak.updateToken(MIN_VALIDITY).catch(() => this.expire());
      };
      this.initialized = this.keycloak
        .init({
          onLoad: 'check-sso',
          pkceMethod: 'S256',
          checkLoginIframe: false,
          silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`,
        })
        .then((authenticated) => (authenticated ? this.getStoredUser() : null))
        .catch(() => null);
    }
    return this.initialized;
  }

  /** The signed-in user, from the current token; null before init or when signed out. */
  getStoredUser(): User | null {
    const user = userFromToken(this.keycloak.tokenParsed as MateriaToken | undefined);
    return user ? { ...user, ...this.changes } : null;
  }

  /** Keeps the user's in-app changes (their name) until the next sign-in brings them in the token. */
  storeUser(user: User): void {
    this.changes = { name: user.name, avatar: user.avatar };
  }

  /** A token valid for at least MIN_VALIDITY seconds, renewed if needed; null when signed out or expired. */
  async getToken(): Promise<string | null> {
    if (!this.keycloak.authenticated) return null;
    try {
      await this.keycloak.updateToken(MIN_VALIDITY);
    } catch {
      this.expire();
      return null;
    }
    return this.keycloak.token ?? null;
  }

  /** Keycloak's sign-in page, then back to this path. */
  login(redirectPath = '/dashboard'): Promise<void> {
    return this.keycloak.login({ redirectUri: new URL(redirectPath, window.location.origin).href });
  }

  /** Ends the Keycloak session too, then back to the home page. */
  logout(): Promise<void> {
    this.changes = {};
    return this.keycloak.logout({ redirectUri: `${window.location.origin}/` });
  }

  /** Keycloak's own page to choose a new password, then back here. */
  changePassword(): Promise<void> {
    return this.keycloak.login({ action: 'UPDATE_PASSWORD', redirectUri: window.location.href });
  }

  /** Keycloak's "forgot password" page, which e-mails a reset link. */
  forgotPasswordUrl(): string {
    const { url, realm, clientId } = keycloakConfig;
    return `${url}/realms/${realm}/login-actions/reset-credentials?client_id=${encodeURIComponent(clientId)}`;
  }

  private expire(): void {
    this.keycloak.clearToken();
    window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT));
  }
}

export const authService = new AuthService();
setTokenProvider(() => authService.getToken());

export default authService;
