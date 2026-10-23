import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios';

/**
 * Dispatched on `window` when the session can no longer be renewed (Keycloak's refresh token expired or revoked).
 * AuthProvider listens for it so React state stays in sync instead of rendering a stale logged-in UI.
 */
export const SESSION_EXPIRED_EVENT = 'materia:session-expired';

/** Where requests get their bearer token: the auth service (Keycloak) registers itself here. */
type TokenProvider = () => Promise<string | null>;
let tokenProvider: TokenProvider = async () => null;

export const setTokenProvider = (provider: TokenProvider): void => {
  tokenProvider = provider;
};

const axiosClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api/v1',
  headers: {
    'Content-Type': 'application/json',
  },
});

// Every request carries a token renewed beforehand when it was about to expire
axiosClient.interceptors.request.use(
  async (config: InternalAxiosRequestConfig) => {
    const token = await tokenProvider();
    if (token && config.headers) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => Promise.reject(error)
);

// A 401 although the token was fresh: the session is over (revoked, account disabled)
axiosClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    if (error.response?.status === 401 && error.config?.headers?.Authorization) {
      window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT));
    }
    return Promise.reject(error);
  }
);

export default axiosClient;
