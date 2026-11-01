import { describe, it, expect } from 'vitest';
import { userFromToken, authService } from '../authService';

/** The signed-in user is what Keycloak's access token says: identity's id, the realm role, the API's permissions. */
describe('authService reads the user from the Keycloak token', () => {
  const token = {
    sub: '2b0f7c3e-5a1d-4e8b-9c6f-0a1b2c3d4e5f',
    email: 'purchaser@materia.com',
    given_name: 'Youssef',
    family_name: 'El Alami',
    realm_access: { roles: ['default-roles-materia', 'offline_access', 'PURCHASER'] },
    resource_access: { 'materia-api': { roles: ['order:read', 'order:write'] }, account: { roles: ['view-profile'] } },
  };

  it('takes the id, the Materia role among the realm roles, and the API client roles as permissions', () => {
    expect(userFromToken(token)).toEqual({
      id: token.sub,
      name: 'Youssef El Alami',
      email: 'purchaser@materia.com',
      role: 'PURCHASER',
      permissions: ['order:read', 'order:write'],
    });
  });

  it('prefers the full name claim, and falls back to the e-mail', () => {
    expect(userFromToken({ ...token, name: 'Y. El Alami' })?.name).toBe('Y. El Alami');
    expect(userFromToken({ ...token, given_name: undefined, family_name: undefined })?.name).toBe('purchaser');
  });

  it('is nobody without a Materia role (a service account, another realm role only) or without a token', () => {
    expect(userFromToken({ ...token, realm_access: { roles: ['SERVICE'] } })).toBeNull();
    expect(userFromToken(undefined)).toBeNull();
  });

  it('sends a forgotten password to Keycloak, for this app', () => {
    expect(authService.forgotPasswordUrl()).toBe(
      `${window.location.origin}/auth/realms/materia/login-actions/reset-credentials?client_id=materia-frontend`
    );
  });
});
