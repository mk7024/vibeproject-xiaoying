export class ApiError extends Error {
  constructor(message: string, public status: number) {
    super(message);
    this.name = "ApiError";
  }
}

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers);
  if (init?.body && !(init.body instanceof FormData)) headers.set("Content-Type", "application/json");
  if (!["GET", "HEAD", "OPTIONS"].includes((init?.method || "GET").toUpperCase())) headers.set("X-Muse-Request", "1");
  let response: Response;
  try {
    response = await fetch(`/api${path}`, { ...init, headers, credentials: "same-origin", cache: "no-store" });
  } catch (error) {
    if ((error instanceof Error || error instanceof DOMException) && error.name === "AbortError") throw error;
    throw new ApiError("暂时无法连接服务，请检查网络后重试。", 0);
  }
  if (!response.ok) {
    const body = await response.json().catch(() => null);
    throw new ApiError(body?.message || (response.status === 401 ? "当前会话已失效，请刷新页面。" : "服务暂时不可用，请稍后重试。"), response.status);
  }
  return response.json() as Promise<T>;
}

export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : "请求未完成，请重试。";
}
