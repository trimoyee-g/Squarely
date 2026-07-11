// Thin fetch wrapper: attaches the access token, and on a 401 transparently tries
// the refresh token once before giving up. Tokens live in localStorage.
const ACCESS = "sq_access";
const REFRESH = "sq_refresh";

export const tokens = {
  get access() { return localStorage.getItem(ACCESS); },
  get refresh() { return localStorage.getItem(REFRESH); },
  set({ accessToken, refreshToken }: { accessToken: string; refreshToken: string }) {
    localStorage.setItem(ACCESS, accessToken);
    localStorage.setItem(REFRESH, refreshToken);
  },
  clear() { localStorage.removeItem(ACCESS); localStorage.removeItem(REFRESH); },
};

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

function send(method: string, path: string, body?: unknown, extra?: Record<string, string>) {
  const headers: Record<string, string> = { "Content-Type": "application/json", ...(extra || {}) };
  if (tokens.access) headers.Authorization = `Bearer ${tokens.access}`;
  return fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
}

async function tryRefresh(): Promise<boolean> {
  if (!tokens.refresh) return false;
  const res = await fetch("/auth/refresh", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken: tokens.refresh }),
  });
  if (!res.ok) return false;
  tokens.set(await res.json());
  return true;
}

export async function api<T = unknown>(
  method: string, path: string, body?: unknown, extraHeaders?: Record<string, string>,
): Promise<T> {
  let res = await send(method, path, body, extraHeaders);
  if (res.status === 401 && tokens.access && (await tryRefresh())) {
    res = await send(method, path, body, extraHeaders);
  }
  if (!res.ok) {
    let detail = res.statusText;
    try { const j = await res.json(); detail = j.detail || j.message || j.error || detail; } catch { /* ignore */ }
    if (res.status === 401) tokens.clear();
    throw new ApiError(res.status, detail);
  }
  if (res.status === 204) return undefined as T;
  return res.json();
}
