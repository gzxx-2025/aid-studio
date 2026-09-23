import { request } from '@/utils/request';

export interface DiagnosticApplication {
  applicationId?: string; status: string; observedIp?: string; reason?: string;
  authorized?: boolean; canOperate?: boolean; canApply?: boolean; queryCredentialSaved?: boolean;
}
export interface DiagnosticSettings {
  enabled: boolean; keyConfigured: boolean; retentionDays: number; captureFailures: number;
  application?: DiagnosticApplication; metadataError?: string;
  metadata?: { supportEgressIps: string[]; expiresAt: string };
}
export interface DiagnosticEvent {
  eventId: string; requestId: string; requestTime: string; source: string;
  reportId?: string; deliveryStatus?: string; deliveryMessage?: string;
  handlingStatus?: 'loading' | 'pending' | 'processing' | 'done' | 'refused' | 'unable' | 'unknown' | null;
  handlingNote?: string | null; handlingUpdatedAt?: string | null;
}
export interface Verification { status: string; verificationId?: string; message?: string; errorCode?: string }

const inFlight = new Map<string, Promise<unknown>>();
function call<T>(method: 'get' | 'post' | 'put', path: string, data?: unknown, signal?: AbortSignal, timeout?: number): Promise<T> {
  const key = `${method}:${path}:${JSON.stringify(data ?? null)}`;
  const existing = inFlight.get(key);
  if (existing) return existing as Promise<T>;
  const pending = request<T>({ url: `/aid/diagnostics${path}`, method, data, signal, timeout,
    headers: { repeatSubmit: false } }).then((result) => result.data as T)
    .finally(() => { if (inFlight.get(key) === pending) inFlight.delete(key); });
  inFlight.set(key, pending);
  return pending;
}
export const diagnostics = {
  settings: () => call<DiagnosticSettings>('get', '/settings'),
  save: (enabled: boolean, aesKey?: string) => call<DiagnosticSettings>('put', '/settings', { enabled, aesKey }),
  key: () => call<{ aesKey: string }>('post', '/key'),
  events: (page: number, signal?: AbortSignal) => call<{ rows: DiagnosticEvent[]; total: number }>('get', `/events?page=${page}&size=20`, undefined, signal),
  detail: (id: string, signal?: AbortSignal) => call<Record<string, unknown>>('get', `/events/${encodeURIComponent(id)}`, undefined, signal),
  apply: (data: unknown) => call<DiagnosticApplication>('post', '/applications', data, undefined, 50000),
  application: (force = false) => call<DiagnosticApplication>('get', `/applications/status${force ? '?force=true' : ''}`, undefined, undefined, 50000),
  access: (force = false) => call<DiagnosticApplication>('get', `/access${force ? '?force=true' : ''}`, undefined, undefined, 50000),
  verify: (data: unknown) => call<Verification>('post', '/support-verifications', data),
  verification: (signal?: AbortSignal) => call<Verification>('get', '/support-verifications/status', undefined, signal),
  send: (data: unknown) => call<{ reportIds: string[] }>('post', '/reports', data),
  retry: (id: string) => call<void>('post', `/reports/${encodeURIComponent(id)}/retry`),
};
