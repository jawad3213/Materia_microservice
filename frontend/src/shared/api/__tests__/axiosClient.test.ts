import { describe, it, expect, beforeEach, vi } from 'vitest';
import type { InternalAxiosRequestConfig, AxiosError } from 'axios';
import axiosClient, { setTokenProvider, SESSION_EXPIRED_EVENT } from '../axiosClient';

type Handler<T> = { fulfilled: (value: T) => T | Promise<T>; rejected: (error: unknown) => unknown };

const requestHandler = () =>
  (axiosClient.interceptors.request as unknown as { handlers: Handler<InternalAxiosRequestConfig>[] }).handlers[0];
const responseHandler = () =>
  (axiosClient.interceptors.response as unknown as { handlers: Handler<unknown>[] }).handlers[0];

const config = (url: string) => ({ url, headers: {} } as InternalAxiosRequestConfig);

describe('axiosClient and the Keycloak token', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    setTokenProvider(async () => null);
  });

  it('attaches the token the auth service provides, renewed beforehand', async () => {
    const provider = vi.fn().mockResolvedValue('fresh-token');
    setTokenProvider(provider);

    const sent = await requestHandler().fulfilled(config('/materials'));

    expect(provider).toHaveBeenCalledOnce();
    expect(sent.headers.Authorization).toBe('Bearer fresh-token');
  });

  it('sends no Authorization header when signed out', async () => {
    const sent = await requestHandler().fulfilled(config('/materials'));

    expect(sent.headers.Authorization).toBeUndefined();
  });

  it('dispatches SESSION_EXPIRED_EVENT when the API refuses a token (revoked session)', async () => {
    const listener = vi.fn();
    window.addEventListener(SESSION_EXPIRED_EVENT, listener);
    const error = {
      response: { status: 401 },
      config: { headers: { Authorization: 'Bearer revoked' } },
    } as unknown as AxiosError;

    await expect(responseHandler().rejected(error)).rejects.toBe(error);

    expect(listener).toHaveBeenCalledOnce();
    window.removeEventListener(SESSION_EXPIRED_EVENT, listener);
  });

  it('does not end the session for a 401 on a request sent without a token', async () => {
    const listener = vi.fn();
    window.addEventListener(SESSION_EXPIRED_EVENT, listener);
    const error = { response: { status: 401 }, config: { headers: {} } } as unknown as AxiosError;

    await expect(responseHandler().rejected(error)).rejects.toBe(error);

    expect(listener).not.toHaveBeenCalled();
    window.removeEventListener(SESSION_EXPIRED_EVENT, listener);
  });
});
