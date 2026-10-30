import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError } from "./api";

afterEach(() => vi.unstubAllGlobals());

describe("API request boundaries", () => {
  it("sends JSON and same-origin cookies without caching", async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(JSON.stringify({ id: "one" })));
    vi.stubGlobal("fetch", fetcher);
    await expect(api("/tasks", { method: "POST", body: JSON.stringify({ templateId: "pet" }) })).resolves.toEqual({ id: "one" });
    const [url, options] = fetcher.mock.calls[0];
    expect(url).toBe("/api/tasks");
    expect(options.credentials).toBe("same-origin");
    expect(options.cache).toBe("no-store");
    expect(options.headers.get("Content-Type")).toBe("application/json");
    expect(options.headers.get("X-Muse-Request")).toBe("1");
  });

  it("lets the browser supply multipart boundaries for uploads", async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response("{}"));
    vi.stubGlobal("fetch", fetcher);
    await api("/assets", { method: "POST", body: new FormData() });
    expect(fetcher.mock.calls[0][1].headers.has("Content-Type")).toBe(false);
  });

  it("turns unavailable servers and non-JSON gateway responses into query errors", async () => {
    const fetcher = vi.fn().mockRejectedValueOnce(new TypeError("offline")).mockResolvedValueOnce(new Response("Bad Gateway", { status: 502 }));
    vi.stubGlobal("fetch", fetcher);
    await expect(api("/tasks/one")).rejects.toMatchObject({ status: 0 });
    await expect(api("/tasks/one")).rejects.toBeInstanceOf(ApiError);
  });

  it("preserves abort cancellation and the backend's understandable error", async () => {
    const aborted = new DOMException("Cancelled", "AbortError");
    vi.stubGlobal("fetch", vi.fn().mockRejectedValueOnce(aborted).mockResolvedValueOnce(new Response(JSON.stringify({ message: "图片格式不符合要求" }), { status: 400 })));
    await expect(api("/tasks")).rejects.toBe(aborted);
    await expect(api("/assets")).rejects.toMatchObject({ status: 400, message: "图片格式不符合要求" });
  });
});
