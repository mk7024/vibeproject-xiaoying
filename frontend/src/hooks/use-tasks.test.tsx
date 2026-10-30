import { act, cleanup, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, api } from "../lib/api";
import type { Task } from "../lib/types";
import { useTasks } from "./use-tasks";

vi.mock("../lib/api", async (importOriginal) => ({
  ...await importOriginal<typeof import("../lib/api")>(),
  api: vi.fn(),
}));

const mockApi = vi.mocked(api);

function makeTask(id: string, status: Task["status"] = "QUEUED", overrides: Partial<Task> = {}): Task {
  return {
    id,
    templateId: "template-1",
    templateTitle: "Test template",
    templateCoverUrl: "/cover.jpg",
    status,
    currentStage: status === "QUEUED" ? null : "GENERATE",
    createdAt: "2026-01-01T00:00:00.000Z",
    updatedAt: "2026-01-01T00:00:00.000Z",
    resultUrl: status === "SUCCEEDED" ? `/results/${id}.jpg` : null,
    failureReason: status === "FAILED" ? "Generation failed" : null,
    events: [],
    ...overrides,
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

async function flush() {
  await act(async () => {
    await Promise.resolve();
  });
}

async function advance(milliseconds = 1500) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(milliseconds);
  });
}

function paths() {
  return mockApi.mock.calls.map(([path]) => path);
}

function signalAt(index: number): AbortSignal {
  const signal = mockApi.mock.calls[index]?.[1]?.signal;
  if (!signal) throw new Error(`Missing AbortSignal for request ${index}`);
  return signal;
}

beforeEach(() => {
  vi.useFakeTimers();
  mockApi.mockReset();
  mockApi.mockRejectedValue(new Error("Unexpected API request"));
});

afterEach(() => {
  cleanup();
  vi.clearAllTimers();
  vi.useRealTimers();
});

describe("useTasks", () => {
  it("polls two tasks independently through success, continued progress, and failure", async () => {
    const first = makeTask("first");
    const second = makeTask("second");
    const succeeded = makeTask("first", "SUCCEEDED");
    const running = makeTask("second", "RUNNING");
    const failed = makeTask("second", "FAILED");
    mockApi
      .mockResolvedValueOnce([first, second])
      .mockResolvedValueOnce(succeeded)
      .mockResolvedValueOnce(running)
      .mockResolvedValueOnce(failed);

    const { result } = renderHook(() => useTasks("user-1"));
    expect(result.current.loading).toBe(true);
    await flush();
    expect(result.current.tasks).toEqual([first, second]);
    expect(result.current.loading).toBe(false);

    await advance(1499);
    expect(paths()).toEqual(["/tasks"]);
    await advance(1);
    expect(paths()).toEqual(["/tasks", "/tasks/first", "/tasks/second"]);
    expect(result.current.tasks).toEqual([succeeded, running]);
    expect(vi.getTimerCount()).toBe(1);

    await advance();
    expect(result.current.tasks).toEqual([succeeded, failed]);
    expect(result.current.error).toBeNull();
    expect(result.current.queryErrors).toEqual({});
    expect(vi.getTimerCount()).toBe(0);
    await advance(15000);
    expect(paths()).toEqual(["/tasks", "/tasks/first", "/tasks/second", "/tasks/second"]);
  });

  it("never overlaps a task's requests while other tasks continue polling", async () => {
    const slowQuery = deferred<Task>();
    mockApi
      .mockResolvedValueOnce([makeTask("slow"), makeTask("fast")])
      .mockReturnValueOnce(slowQuery.promise)
      .mockResolvedValueOnce(makeTask("fast", "RUNNING"))
      .mockResolvedValueOnce(makeTask("fast", "SUCCEEDED"))
      .mockResolvedValueOnce(makeTask("slow", "SUCCEEDED"));

    const { result } = renderHook(() => useTasks("user-1"));
    await flush();
    await advance();
    await advance(4500);
    expect(paths()).toEqual(["/tasks", "/tasks/slow", "/tasks/fast", "/tasks/fast"]);
    expect(result.current.tasks.find((task) => task.id === "fast")?.status).toBe("SUCCEEDED");

    await act(async () => slowQuery.resolve(makeTask("slow", "RUNNING")));
    await advance(1499);
    expect(mockApi).toHaveBeenCalledTimes(4);
    await advance(1);
    expect(paths()).toEqual(["/tasks", "/tasks/slow", "/tasks/fast", "/tasks/fast", "/tasks/slow"]);
    expect(result.current.tasks.every((task) => task.status === "SUCCEEDED")).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
  });

  it("retains a task on query failure, pauses its requests, and recovers on refresh", async () => {
    const known = makeTask("first", "RUNNING", {
      events: [{ stage: "CHECK", status: "SUCCEEDED", startedAt: "2026-01-01T00:00:00Z", finishedAt: "2026-01-01T00:00:01Z" }],
    });
    const completed = makeTask("first", "SUCCEEDED");
    mockApi
      .mockResolvedValueOnce([known])
      .mockRejectedValueOnce(new ApiError("Connection interrupted", 0))
      .mockResolvedValueOnce([makeTask("first")])
      .mockResolvedValueOnce(completed);

    const { result } = renderHook(() => useTasks("user-1"));
    await flush();
    await advance();
    expect(result.current.tasks).toEqual([known]);
    expect(result.current.error).toBeNull();
    expect(result.current.queryErrors).toEqual({ first: "Connection interrupted" });
    expect(vi.getTimerCount()).toBe(0);
    await advance(15000);
    expect(mockApi).toHaveBeenCalledTimes(2);

    act(() => result.current.refresh());
    expect(result.current.loading).toBe(true);
    expect(result.current.queryErrors).toEqual({});
    expect(paths()).toEqual(["/tasks", "/tasks/first", "/tasks"]);
    await flush();
    expect(result.current.tasks).toEqual([known]);
    await advance();
    expect(result.current.tasks).toEqual([completed]);
    expect(result.current.queryErrors).toEqual({});
    expect(result.current.loading).toBe(false);
    expect(vi.getTimerCount()).toBe(0);
  });

  it("isolates query errors to the failed request while other tasks finish", async () => {
    const first = makeTask("first", "RUNNING");
    mockApi
      .mockResolvedValueOnce([first, makeTask("second")])
      .mockRejectedValueOnce(new Error("Temporary outage"))
      .mockResolvedValueOnce(makeTask("second", "RUNNING"))
      .mockResolvedValueOnce(makeTask("second", "SUCCEEDED"));

    const { result } = renderHook(() => useTasks("user-1"));
    await flush();
    await advance(6000);
    expect(result.current.tasks[0]).toEqual(first);
    expect(result.current.tasks[1].status).toBe("SUCCEEDED");
    expect(result.current.error).toBeNull();
    expect(result.current.queryErrors).toEqual({ first: "Temporary outage" });
    expect(paths()).toEqual(["/tasks", "/tasks/first", "/tasks/second", "/tasks/second"]);
    expect(vi.getTimerCount()).toBe(0);
  });

  it("exposes an initial history error and clears it when history is retried", async () => {
    const completed = makeTask("completed", "SUCCEEDED");
    mockApi
      .mockRejectedValueOnce(new ApiError("History unavailable", 503))
      .mockResolvedValueOnce([completed]);

    const { result } = renderHook(() => useTasks("user-1"));
    await flush();
    expect(result.current.tasks).toEqual([]);
    expect(result.current.error).toBe("History unavailable");
    expect(result.current.queryErrors).toEqual({});
    expect(result.current.loading).toBe(false);
    expect(vi.getTimerCount()).toBe(0);

    act(() => result.current.refresh());
    expect(result.current.error).toBeNull();
    expect(result.current.loading).toBe(true);
    await flush();
    expect(result.current.tasks).toEqual([completed]);
    expect(result.current.loading).toBe(false);
    expect(paths()).toEqual(["/tasks", "/tasks"]);
  });

  it("retries a paused task even when the refresh history request fails", async () => {
    const known = makeTask("first", "RUNNING");
    mockApi
      .mockResolvedValueOnce([known])
      .mockRejectedValueOnce(new Error("Query unavailable"))
      .mockRejectedValueOnce(new Error("History unavailable"))
      .mockResolvedValueOnce(makeTask("first", "SUCCEEDED"));

    const { result } = renderHook(() => useTasks("user-1"));
    await flush();
    await advance();
    act(() => result.current.refresh());
    await flush();
    expect(result.current.tasks).toEqual([known]);
    expect(result.current.queryErrors).toEqual({});
    expect(result.current.error).toBe("History unavailable");
    await advance();
    expect(result.current.tasks[0].status).toBe("SUCCEEDED");
    expect(vi.getTimerCount()).toBe(0);
  });

  it("does not schedule queries when history contains only terminal tasks", async () => {
    mockApi.mockResolvedValueOnce([makeTask("done", "SUCCEEDED"), makeTask("failed", "FAILED")]);
    renderHook(() => useTasks("user-1"));
    await flush();
    expect(vi.getTimerCount()).toBe(0);
    await advance(30000);
    expect(paths()).toEqual(["/tasks"]);
  });

  it("shows accepted tasks immediately and restarts polling after every idle period", async () => {
    mockApi
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce(makeTask("first", "SUCCEEDED"))
      .mockResolvedValueOnce(makeTask("second", "FAILED"));
    const { result } = renderHook(() => useTasks("user-1"));
    await flush();
    expect(vi.getTimerCount()).toBe(0);

    const first = makeTask("first");
    act(() => result.current.addTask(first));
    expect(result.current.tasks).toEqual([first]);
    expect(result.current.loading).toBe(false);
    expect(vi.getTimerCount()).toBe(1);
    await advance();
    expect(result.current.tasks[0].status).toBe("SUCCEEDED");
    expect(vi.getTimerCount()).toBe(0);
    await advance(6000);
    expect(mockApi).toHaveBeenCalledTimes(2);

    const second = makeTask("second", "QUEUED", { createdAt: "2026-01-02T00:00:00.000Z" });
    act(() => result.current.addTask(second));
    expect(result.current.tasks[0]).toEqual(second);
    expect(vi.getTimerCount()).toBe(1);
    await advance();
    expect(result.current.tasks.find((task) => task.id === "second")?.status).toBe("FAILED");
    expect(paths()).toEqual(["/tasks", "/tasks/first", "/tasks/second"]);
    expect(vi.getTimerCount()).toBe(0);
  });

  it("merges a stale history snapshot without dropping submissions or regressing completed tasks", async () => {
    const history = deferred<Task[]>();
    const first = makeTask("first");
    const completed = makeTask("first", "SUCCEEDED");
    const second = makeTask("second", "QUEUED", { createdAt: "2026-01-02T00:00:00.000Z" });
    const archived = makeTask("archived", "SUCCEEDED", { createdAt: "2025-12-01T00:00:00.000Z" });
    mockApi
      .mockReturnValueOnce(history.promise)
      .mockResolvedValueOnce(completed)
      .mockResolvedValueOnce(makeTask("second", "SUCCEEDED"));

    const { result } = renderHook(() => useTasks("user-1"));
    act(() => result.current.addTask(first));
    expect(result.current.tasks).toEqual([first]);
    await advance();
    expect(result.current.tasks).toEqual([completed]);
    act(() => result.current.addTask(second));
    expect(result.current.tasks).toEqual([second, completed]);

    await act(async () => history.resolve([first, archived]));
    expect(result.current.tasks).toEqual([second, completed, archived]);
    expect(result.current.loading).toBe(false);
    await advance();
    expect(paths()).toEqual(["/tasks", "/tasks/first", "/tasks/second"]);
    expect(result.current.tasks.every((task) => task.status === "SUCCEEDED")).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
  });

  it("keeps terminal state when a later refresh returns older pending data", async () => {
    const completed = makeTask("first", "SUCCEEDED");
    mockApi
      .mockResolvedValueOnce([completed])
      .mockResolvedValueOnce([makeTask("first", "RUNNING")]);
    const { result } = renderHook(() => useTasks("user-1"));
    await flush();
    act(() => result.current.refresh());
    await flush();
    expect(result.current.tasks).toEqual([completed]);
    expect(vi.getTimerCount()).toBe(0);
    await advance(15000);
    expect(paths()).toEqual(["/tasks", "/tasks"]);
  });

  it("aborts superseded history requests and ignores their late failures", async () => {
    const oldHistory = deferred<Task[]>();
    const newHistory = deferred<Task[]>();
    mockApi.mockReturnValueOnce(oldHistory.promise).mockReturnValueOnce(newHistory.promise);
    const { result } = renderHook(() => useTasks("user-1"));
    const oldSignal = signalAt(0);

    act(() => result.current.refresh());
    expect(oldSignal.aborted).toBe(true);
    await act(async () => oldHistory.reject(new Error("Old request failed")));
    expect(result.current.loading).toBe(true);
    expect(result.current.error).toBeNull();
    const completed = makeTask("new", "SUCCEEDED");
    await act(async () => newHistory.resolve([completed]));
    expect(result.current.tasks).toEqual([completed]);
    expect(result.current.loading).toBe(false);
  });

  it("clears the previous identity and ignores its aborted initial history response", async () => {
    const oldHistory = deferred<Task[]>();
    const newHistory = deferred<Task[]>();
    mockApi.mockReturnValueOnce(oldHistory.promise).mockReturnValueOnce(newHistory.promise);
    const { result, rerender } = renderHook(({ userId }) => useTasks(userId), {
      initialProps: { userId: "old-user" },
    });
    const oldSignal = signalAt(0);
    const oldAddTask = result.current.addTask;
    const oldRefresh = result.current.refresh;
    act(() => result.current.addTask(makeTask("old-task")));
    expect(vi.getTimerCount()).toBe(1);

    rerender({ userId: "new-user" });
    expect(oldSignal.aborted).toBe(true);
    expect(result.current.tasks).toEqual([]);
    expect(result.current.loading).toBe(true);
    expect(result.current.error).toBeNull();
    expect(result.current.queryErrors).toEqual({});
    expect(vi.getTimerCount()).toBe(0);
    act(() => {
      oldAddTask(makeTask("late-submission"));
      oldRefresh();
    });
    expect(mockApi).toHaveBeenCalledTimes(2);

    const newTask = makeTask("new-task", "SUCCEEDED");
    await act(async () => newHistory.resolve([newTask]));
    await act(async () => oldHistory.resolve([makeTask("old-history-task")]));
    expect(result.current.tasks).toEqual([newTask]);
    expect(result.current.loading).toBe(false);
    await advance(15000);
    expect(paths()).toEqual(["/tasks", "/tasks"]);
  });

  it("aborts in-flight polls and refreshes on identity changes and ignores late results", async () => {
    const oldQuery = deferred<Task>();
    const oldHistory = deferred<Task[]>();
    const newTask = makeTask("new-task");
    mockApi
      .mockResolvedValueOnce([makeTask("old-task")])
      .mockReturnValueOnce(oldQuery.promise)
      .mockReturnValueOnce(oldHistory.promise)
      .mockResolvedValueOnce([newTask])
      .mockResolvedValueOnce(makeTask("new-task", "SUCCEEDED"));
    const { result, rerender } = renderHook(({ userId }) => useTasks(userId), {
      initialProps: { userId: "old-user" },
    });
    await flush();
    await advance();
    const querySignal = signalAt(1);
    act(() => result.current.refresh());
    const historySignal = signalAt(2);

    rerender({ userId: "new-user" });
    expect(querySignal.aborted).toBe(true);
    expect(historySignal.aborted).toBe(true);
    await flush();
    await act(async () => {
      oldQuery.resolve(makeTask("old-task", "SUCCEEDED"));
      oldHistory.resolve([makeTask("old-task", "FAILED")]);
    });
    expect(result.current.tasks).toEqual([newTask]);
    expect(result.current.error).toBeNull();
    expect(result.current.queryErrors).toEqual({});
    await advance();
    expect(paths()).toEqual(["/tasks", "/tasks/old-task", "/tasks", "/tasks", "/tasks/new-task"]);
    expect(result.current.tasks[0].status).toBe("SUCCEEDED");
  });

  it("clears timers and aborts history and queries on unmount, ignoring late completions", async () => {
    const query = deferred<Task>();
    const history = deferred<Task[]>();
    mockApi
      .mockResolvedValueOnce([makeTask("slow"), makeTask("scheduled")])
      .mockReturnValueOnce(query.promise)
      .mockResolvedValueOnce(makeTask("scheduled", "RUNNING"))
      .mockReturnValueOnce(history.promise);
    const { result, unmount } = renderHook(() => useTasks("user-1"));
    await flush();
    await advance();
    const querySignal = signalAt(1);
    act(() => result.current.refresh());
    const historySignal = signalAt(3);
    const lastView = result.current;
    expect(vi.getTimerCount()).toBe(1);

    unmount();
    expect(querySignal.aborted).toBe(true);
    expect(historySignal.aborted).toBe(true);
    expect(vi.getTimerCount()).toBe(0);
    await act(async () => {
      query.reject(new Error("Late query failure"));
      history.resolve([makeTask("late-history-task")]);
      lastView.addTask(makeTask("late-submission"));
      lastView.refresh();
    });
    await advance(15000);
    expect(result.current).toBe(lastView);
    expect(mockApi).toHaveBeenCalledTimes(4);
    expect(vi.getTimerCount()).toBe(0);
  });

  it("isolates sessions across StrictMode effect cleanup and restart", async () => {
    const discardedHistory = deferred<Task[]>();
    const currentHistory = deferred<Task[]>();
    mockApi
      .mockReturnValueOnce(discardedHistory.promise)
      .mockReturnValueOnce(currentHistory.promise)
      .mockResolvedValueOnce(makeTask("current-task", "SUCCEEDED"));
    const { result } = renderHook(() => useTasks("user-1"), { reactStrictMode: true });
    expect(signalAt(0).aborted).toBe(true);
    expect(signalAt(1).aborted).toBe(false);
    await act(async () => currentHistory.resolve([makeTask("current-task")]));
    await act(async () => discardedHistory.resolve([makeTask("discarded-task")]));
    expect(result.current.tasks.map((task) => task.id)).toEqual(["current-task"]);
    await advance();
    expect(paths()).toEqual(["/tasks", "/tasks", "/tasks/current-task"]);
    expect(result.current.tasks[0].status).toBe("SUCCEEDED");
    expect(vi.getTimerCount()).toBe(0);
  });
});
