import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { api, tokens } from "./api";
import type { User } from "./types";

interface AuthCtx {
  user: User | null;
  loading: boolean;
  login: (email: string, password: string) => Promise<void>;
  signup: (email: string, password: string, displayName: string) => Promise<void>;
  logout: () => Promise<void>;
}

const Ctx = createContext<AuthCtx>(null!);
export const useAuth = () => useContext(Ctx);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  // On boot, if we have a token, resolve the current user.
  useEffect(() => {
    if (!tokens.access) { setLoading(false); return; }
    api<User>("GET", "/auth/me")
      .then(setUser)
      .catch(() => tokens.clear())
      .finally(() => setLoading(false));
  }, []);

  async function authenticate(path: string, body: unknown) {
    tokens.set(await api("POST", path, body));
    setUser(await api<User>("GET", "/auth/me"));
  }

  const value: AuthCtx = {
    user,
    loading,
    login: (email, password) => authenticate("/auth/login", { email, password }),
    signup: (email, password, displayName) => authenticate("/auth/signup", { email, password, displayName }),
    logout: async () => {
      try { await api("POST", "/auth/logout"); } catch { /* ignore */ }
      tokens.clear();
      setUser(null);
    },
  };
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
