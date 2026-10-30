"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { api, errorMessage } from "../lib/api";
import { isPending, type Task } from "../lib/types";

interface TasksState {
  tasks: Task[];
  loading: boolean;
  error: string | null;
  queryErrors: Record<string, string>;
}

interface UseTasksResult extends TasksState {
  refresh: () => void;
  addTask: (task: Task) => void;
}

interface TaskSession {
  refresh: () => void;
  addTask: (task: Task) => void;
  dispose: () => void;
}

const INITIAL_STATE: TasksState = {
  tasks: [],
  loading: true,
  error: null,
  queryErrors: {},
};
const POLL_INTERVAL = 1500;

function mergeTask(current: Task | undefined, incoming: Task): Task {
  if (!current) return incoming;
  if (!isPending(current) && current.status !== incoming.status) return current;
  if (current.status === "RUNNING" && incoming.status === "QUEUED") return current;
  if (Date.parse(incoming.updatedAt) < Date.parse(current.updatedAt)) return current;
  return incoming;
}

function createTaskSession(publish: (state: TasksState) => void): TaskSession {
  const entries = new Map<string, { task: Task; revision: number }>();
  const timers = new Map<string, ReturnType<typeof setTimeout>>();
  const queries = new Map<string, AbortController>();
  let active = true;
  let revision = 0;
  let loading = true;
  let error: string | null = null;
  let queryErrors: Record<string, string> = {};
  let historyController: AbortController | undefined;

  function emit() {
    if (!active) return;
    publish({
      tasks: [...entries.values()]
        .map((entry) => entry.task)
        .sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
      loading,
      error,
      queryErrors,
    });
  }

  function putTask(incoming: Task) {
    const current = entries.get(incoming.id)?.task;
    const task = mergeTask(current, incoming);
    if (task !== current) entries.set(task.id, { task, revision: ++revision });
  }

  function clearQueryError(taskId: string) {
    if (!Object.hasOwn(queryErrors, taskId)) return;
    queryErrors = { ...queryErrors };
    delete queryErrors[taskId];
  }

  function cancelPoll(taskId: string) {
    const timer = timers.get(taskId);
    if (timer !== undefined) clearTimeout(timer);
    timers.delete(taskId);
    const controller = queries.get(taskId);
    queries.delete(taskId);
    controller?.abort();
  }

  function schedulePoll(taskId: string) {
    const task = entries.get(taskId)?.task;
    if (
      !active ||
      !task ||
      !isPending(task) ||
      Object.hasOwn(queryErrors, taskId) ||
      timers.has(taskId) ||
      queries.has(taskId)
    ) return;

    timers.set(taskId, setTimeout(() => {
      timers.delete(taskId);
      void poll(taskId);
    }, POLL_INTERVAL));
  }

  function syncPolling() {
    for (const { task } of entries.values()) {
      if (isPending(task)) {
        schedulePoll(task.id);
      } else {
        cancelPoll(task.id);
        clearQueryError(task.id);
      }
    }
  }

  async function poll(taskId: string) {
    const controller = new AbortController();
    queries.set(taskId, controller);
    try {
      const task = await api<Task>(`/tasks/${encodeURIComponent(taskId)}`, {
        signal: controller.signal,
      });
      if (!active || queries.get(taskId) !== controller) return;
      putTask(task);
      clearQueryError(taskId);
      emit();
    } catch (cause) {
      if (!active || queries.get(taskId) !== controller) return;
      // Query failures pause only this task until refresh, without changing its status.
      queryErrors = { ...queryErrors, [taskId]: errorMessage(cause) };
      emit();
    } finally {
      if (queries.get(taskId) === controller) {
        queries.delete(taskId);
        schedulePoll(taskId);
      }
    }
  }

  async function loadHistory(controller: AbortController, startedRevision: number) {
    try {
      const history = await api<Task[]>("/tasks", { signal: controller.signal });
      if (!active || historyController !== controller) return;
      for (const task of history) {
        const existing = entries.get(task.id);
        // A history snapshot cannot overwrite submissions or polls accepted after it began.
        if (existing && existing.revision > startedRevision) continue;
        putTask(task);
      }
    } catch (cause) {
      if (!active || historyController !== controller) return;
      error = errorMessage(cause);
    } finally {
      if (active && historyController === controller) {
        historyController = undefined;
        loading = false;
        syncPolling();
        emit();
      }
    }
  }

  function refresh() {
    if (!active) return;
    historyController?.abort();
    const controller = new AbortController();
    historyController = controller;
    loading = true;
    error = null;
    queryErrors = {};
    syncPolling();
    emit();
    void loadHistory(controller, revision);
  }

  return {
    refresh,
    addTask(task) {
      if (!active) return;
      putTask(task);
      clearQueryError(task.id);
      syncPolling();
      emit();
    },
    dispose() {
      active = false;
      historyController?.abort();
      historyController = undefined;
      for (const taskId of entries.keys()) cancelPoll(taskId);
    },
  };
}

export function useTasks(userId: string): UseTasksResult {
  const [view, setView] = useState({ userId, state: INITIAL_STATE });
  const sessionRef = useRef<(TaskSession & { userId: string }) | null>(null);

  useEffect(() => {
    const session = {
      userId,
      ...createTaskSession((state) => setView({ userId, state })),
    };
    sessionRef.current = session;
    session.refresh();
    return () => {
      session.dispose();
      if (sessionRef.current === session) sessionRef.current = null;
    };
  }, [userId]);

  const refresh = useCallback(() => {
    const session = sessionRef.current;
    if (session?.userId === userId) session.refresh();
  }, [userId]);

  const addTask = useCallback((task: Task) => {
    const session = sessionRef.current;
    if (session?.userId === userId) session.addTask(task);
  }, [userId]);

  return {
    ...(view.userId === userId ? view.state : INITIAL_STATE),
    refresh,
    addTask,
  };
}
