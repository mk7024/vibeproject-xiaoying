import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, api } from "../lib/api";
import type { Session } from "../lib/types";
import { SessionProvider, useSession } from "./session-provider";

vi.mock("../lib/api", async (importOriginal) => ({
  ...await importOriginal<typeof import("../lib/api")>(),
  api: vi.fn(),
}));

const mockApi = vi.mocked(api);

const anonymous: Session = { id: "anon-1", displayName: "灵感访客 ANON1", email: null };
const registered: Session = { id: "anon-1", displayName: "灵感访客 ANON1", email: "user@example.com" };
const account: Session = { id: "account-1", displayName: "灵感访客 ACCOUN", email: "user@example.com" };
const nextAnonymous: Session = { id: "anon-2", displayName: "灵感访客 ANON2", email: null };

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise;
  });
  return { promise, resolve };
}

function Harness() {
  const { session, loading, error, register, login, logout, changePassword, newSession } = useSession();
  return <div>
    <span data-testid="session">{session ? `${session.id}:${session.email || "anonymous"}` : "none"}</span>
    <span data-testid="loading">{loading ? "loading" : "idle"}</span>
    <span data-testid="error">{error || "none"}</span>
    <button onClick={() => { void register("user@example.com", "password123").catch(() => undefined); }}>register</button>
    <button onClick={() => { void login("user@example.com", "password123").catch(() => undefined); }}>login</button>
    <button onClick={() => { void logout().catch(() => undefined); }}>logout</button>
    <button onClick={() => { void changePassword("old-password", "new-password").catch(() => undefined); }}>change</button>
    <button onClick={() => { void newSession().catch(() => undefined); }}>new-space</button>
  </div>;
}

function renderProvider() {
  return render(<SessionProvider><Harness /></SessionProvider>);
}

function text(testId: string) {
  return screen.getByTestId(testId).textContent;
}

function paths() {
  return mockApi.mock.calls.map(([path]) => path);
}

beforeEach(() => {
  mockApi.mockReset();
  mockApi.mockRejectedValue(new Error("Unexpected API request"));
});

afterEach(cleanup);

describe("SessionProvider account lifecycle", () => {
  it("loads the anonymous session once", async () => {
    mockApi.mockResolvedValueOnce(anonymous);
    renderProvider();
    await waitFor(() => expect(text("session")).toBe("anon-1:anonymous"));
    expect(text("loading")).toBe("idle");
    expect(text("error")).toBe("none");
    expect(paths()).toEqual(["/session"]);
  });

  it("registers the current anonymous identity without changing its data owner", async () => {
    mockApi.mockResolvedValueOnce(anonymous).mockResolvedValueOnce(registered);
    renderProvider();
    await waitFor(() => expect(text("session")).toBe("anon-1:anonymous"));
    fireEvent.click(screen.getByText("register"));
    await waitFor(() => expect(text("session")).toBe("anon-1:user@example.com"));
    expect(paths()).toEqual(["/session", "/account/register"]);
    expect(mockApi.mock.calls[1][1]?.body).toBe(JSON.stringify({ email: "user@example.com", password: "password123" }));
  });

  it("switches to the account identity on login and to a new anonymous space on logout", async () => {
    mockApi.mockResolvedValueOnce(anonymous).mockResolvedValueOnce(account).mockResolvedValueOnce(nextAnonymous);
    renderProvider();
    await waitFor(() => expect(text("session")).toBe("anon-1:anonymous"));
    fireEvent.click(screen.getByText("login"));
    await waitFor(() => expect(text("session")).toBe("account-1:user@example.com"));
    fireEvent.click(screen.getByText("logout"));
    await waitFor(() => expect(text("session")).toBe("anon-2:anonymous"));
    expect(paths()).toEqual(["/session", "/account/login", "/account/logout"]);
  });

  it("updates the session after changing a password", async () => {
    mockApi.mockResolvedValueOnce(account).mockResolvedValueOnce(account);
    renderProvider();
    await waitFor(() => expect(text("session")).toBe("account-1:user@example.com"));
    fireEvent.click(screen.getByText("change"));
    await waitFor(() => expect(paths()).toContain("/account/password"));
    expect(text("session")).toBe("account-1:user@example.com");
    expect(mockApi.mock.calls[1][1]?.body).toBe(JSON.stringify({
      currentPassword: "old-password",
      newPassword: "new-password",
    }));
  });

  it("keeps the existing session and does not turn a failed login into a session error", async () => {
    mockApi.mockResolvedValueOnce(anonymous).mockRejectedValueOnce(new ApiError("邮箱或密码不正确", 401));
    renderProvider();
    await waitFor(() => expect(text("session")).toBe("anon-1:anonymous"));
    fireEvent.click(screen.getByText("login"));
    await waitFor(() => expect(paths()).toEqual(["/session", "/account/login"]));
    await waitFor(() => expect(text("loading")).toBe("idle"));
    expect(text("session")).toBe("anon-1:anonymous");
    expect(text("error")).toBe("none");
  });

  it("keeps the current session when creating a new anonymous space fails", async () => {
    mockApi.mockResolvedValueOnce(anonymous).mockRejectedValueOnce(new ApiError("暂时无法连接服务", 0));
    renderProvider();
    await waitFor(() => expect(text("session")).toBe("anon-1:anonymous"));
    fireEvent.click(screen.getByText("new-space"));
    await waitFor(() => expect(text("error")).toBe("暂时无法连接服务"));
    expect(text("session")).toBe("anon-1:anonymous");
    expect(text("loading")).toBe("idle");
    expect(paths()).toEqual(["/session", "/session?fresh=true"]);
  });

  it("ignores a stale initial session response after logging in", async () => {
    const initial = deferred<Session>();
    mockApi.mockReturnValueOnce(initial.promise).mockResolvedValueOnce(account);
    renderProvider();
    fireEvent.click(screen.getByText("login"));
    await waitFor(() => expect(text("session")).toBe("account-1:user@example.com"));
    await act(async () => initial.resolve(anonymous));
    expect(text("session")).toBe("account-1:user@example.com");
    expect(text("error")).toBe("none");
  });

  it("aborts the active session request on unmount", async () => {
    const initial = deferred<Session>();
    mockApi.mockReturnValueOnce(initial.promise);
    const view = renderProvider();
    const signal = mockApi.mock.calls[0][1]?.signal;
    expect(signal?.aborted).toBe(false);
    view.unmount();
    expect(signal?.aborted).toBe(true);
  });
});
