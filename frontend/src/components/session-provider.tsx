"use client";

import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { api, errorMessage } from "@/lib/api";
import type { Session } from "@/lib/types";

interface SessionState {
  session: Session | null;
  loading: boolean;
  error: string | null;
  retry: () => void;
  newSession: () => Promise<void>;
  register: (email: string, password: string) => Promise<void>;
  login: (email: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  changePassword: (currentPassword: string, newPassword: string) => Promise<void>;
}

const SessionContext = createContext<SessionState | null>(null);

export function SessionProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const request = useRef<AbortController | null>(null);
  const sequence = useRef(0);

  const load = useCallback(async (fresh = false) => {
    const id = ++sequence.current;
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    setLoading(true);
    setError(null);
    try {
      const data = await api<Session>(`/session${fresh ? "?fresh=true" : ""}`, {
        method: "POST",
        signal: controller.signal,
      });
      if (id === sequence.current && !controller.signal.aborted) {
        setSession(data);
        setError(null);
      }
      return true;
    } catch (cause) {
      if (id === sequence.current && !controller.signal.aborted) setError(errorMessage(cause));
      return false;
    } finally {
      if (id === sequence.current && !controller.signal.aborted) setLoading(false);
    }
  }, []);

  const accountAction = useCallback(async (path: string, body?: Record<string, string>) => {
    const id = ++sequence.current;
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    try {
      const data = await api<Session>(path, {
        method: "POST",
        body: body ? JSON.stringify(body) : undefined,
        signal: controller.signal,
      });
      if (id === sequence.current && !controller.signal.aborted) {
        setSession(data);
        setError(null);
        setLoading(false);
      }
    } finally {
      if (id === sequence.current && !controller.signal.aborted) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
    return () => request.current?.abort();
  }, [load]);

  const register = useCallback((email: string, password: string) => accountAction("/account/register", { email, password }), [accountAction]);
  const login = useCallback((email: string, password: string) => accountAction("/account/login", { email, password }), [accountAction]);
  const logout = useCallback(() => accountAction("/account/logout"), [accountAction]);
  const changePassword = useCallback((currentPassword: string, newPassword: string) =>
    accountAction("/account/password", { currentPassword, newPassword }), [accountAction]);
  const newSession = useCallback(async () => {
    if (!await load(true)) throw new Error("无法创建新的匿名空间，请检查连接后重试。");
  }, [load]);

  return <SessionContext.Provider value={{
    session, loading, error,
    retry: () => void load(),
    newSession,
    register, login, logout, changePassword,
  }}><div key={session?.id || "anonymous-loading"} className="app-frame">{children}</div></SessionContext.Provider>;
}

export function useSession() {
  const value = useContext(SessionContext);
  if (!value) throw new Error("SessionProvider is required");
  return value;
}
