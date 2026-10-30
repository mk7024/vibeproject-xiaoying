import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { setTimeout as delay } from "node:timers/promises";

const base = process.env.SMOKE_BASE_URL || "http://127.0.0.1:3000";
const photo = await readFile(new URL("../public/demo/pet.jpg", import.meta.url));
let cookie = "";

async function request(path, options = {}, expected = 200, identity = cookie) {
  const headers = new Headers(options.headers);
  if (identity) headers.set("Cookie", identity);
  if (options.body && !(options.body instanceof FormData)) headers.set("Content-Type", "application/json");
  if (!["GET", "HEAD", "OPTIONS"].includes(options.method || "GET")) headers.set("X-Muse-Request", "1");
  const response = await fetch(`${base}/api${path}`, { ...options, headers });
  assert.equal(response.status, expected, `${path}: ${response.status} ${expected !== response.status ? await response.text() : ""}`);
  return response;
}

function remember(response) {
  const issued = response.headers.getSetCookie()[0];
  assert.ok(issued, "Expected a new session cookie");
  cookie = issued.split(";")[0];
  return cookie;
}

async function session(fresh = false) {
  const response = await request(`/session${fresh ? "?fresh=true" : ""}`, { method: "POST" });
  const issued = response.headers.getSetCookie()[0];
  if (issued) cookie = issued.split(";")[0];
  assert.ok(cookie.startsWith("muse_session="));
  return response.json();
}

async function waitForTask(id, deadline = Date.now() + 60000) {
  while (true) {
    const task = await (await request(`/tasks/${id}`)).json();
    if (["SUCCEEDED", "FAILED"].includes(task.status)) return task;
    assert.ok(Date.now() < deadline, `Task ${id} did not finish within 60 seconds`);
    await delay(125);
  }
}

async function newIdentity() {
  const saved = cookie;
  cookie = "";
  try {
    const response = await request("/session", { method: "POST" });
    const identity = await response.json();
    const issued = response.headers.getSetCookie()[0];
    assert.ok(issued, "Expected a new session cookie");
    return { identity, cookie: issued.split(";")[0] };
  } finally {
    cookie = saved;
  }
}

async function upload(templateId, data = photo, filename = "photo.jpg", mime = "image/jpeg", expected = 200) {
  const body = new FormData();
  body.set("file", new Blob([data], { type: mime }), filename);
  body.set("templateId", templateId);
  const response = await request("/assets", { method: "POST", body }, expected);
  return response.json();
}

await request("/health");
await request("/tasks", {}, 401, "");
const owner = await session();
assert.equal((await session()).id, owner.id);
const templates = await (await request("/templates")).json();
assert.ok(templates.length >= 6);
assert.ok(templates.some((template) => template.requiredImageCount === 2));
const categories = await (await request("/categories")).json();
assert.ok(categories.length >= 3);
const filtered = await (await request(`/templates?category=${encodeURIComponent(categories[0])}`)).json();
assert.ok(filtered.every((template) => template.category === categories[0]));
await request("/templates/missing-template", {}, 404);
const asset = await upload("cloud-pet");
await upload("cloud-pet", new TextEncoder().encode("not an image"), "fake.jpg", "image/jpeg", 400);
await upload("cloud-pet", photo, "photo.gif", "image/gif", 400);
await request("/tasks", { method: "POST", body: JSON.stringify({ templateId: "seaside-postcard", assetIds: [asset.id] }) }, 400);
await request("/tasks", { method: "POST", body: JSON.stringify({ templateId: "seaside-postcard", assetIds: [asset.id, asset.id] }) }, 400);
await request("/tasks", { method: "POST", body: JSON.stringify({ templateId: "cloud-pet", assetIds: [asset.id], simulation: { stepDelayMs: -1 } }) }, 400);

const tasks = [];
for (const failStage of [null, "CHECK", "GENERATE", "FINALIZE"]) {
  const response = await request("/tasks", { method: "POST", body: JSON.stringify({ templateId: "cloud-pet", assetIds: [asset.id], simulation: { failStage, stepDelayMs: 150 } }) }, 202);
  tasks.push({ task: await response.json(), failStage });
}
const second = await newIdentity();
assert.notEqual(second.identity.id, owner.id);
assert.deepEqual(await (await request("/tasks", {}, 200, second.cookie)).json(), []);
await request(`/tasks/${tasks[0].task.id}`, {}, 404, second.cookie);
await request(`/assets/${asset.id}/content`, {}, 404, second.cookie);
await request("/tasks", { method: "POST", body: JSON.stringify({ templateId: "cloud-pet", assetIds: [asset.id] }) }, 404, second.cookie);

const deadline = Date.now() + 60000;
for (const item of tasks) {
  let task;
  do {
    task = await (await request(`/tasks/${item.task.id}`)).json();
    if (["SUCCEEDED", "FAILED"].includes(task.status)) break;
    assert.ok(Date.now() < deadline, "Tasks did not finish within 60 seconds");
    await delay(125);
  } while (true);
  const stages = ["CHECK", "GENERATE", "FINALIZE"];
  if (item.failStage) {
    assert.equal(task.status, "FAILED");
    assert.equal(task.currentStage, item.failStage);
    assert.ok(task.failureReason);
    assert.equal(task.resultUrl, null);
    assert.deepEqual(task.events.map((event) => event.stage), stages.slice(0, stages.indexOf(item.failStage) + 1));
    assert.equal(task.events.at(-1).status, "FAILED");
  } else {
    assert.equal(task.status, "SUCCEEDED");
    assert.deepEqual(task.events.map((event) => event.stage), stages);
    assert.ok(task.events.every((event) => event.status === "SUCCEEDED" && event.finishedAt));
    const result = await request(task.resultUrl.replace("/api", ""));
    assert.equal(result.headers.get("content-type"), "image/jpeg");
    assert.deepEqual(Buffer.from(await result.arrayBuffer()), photo);
    await request(task.resultUrl.replace("/api", ""), {}, 401, "");
  }
  const again = await (await request(`/tasks/${task.id}`)).json();
  assert.equal(again.status, task.status);
  assert.equal(again.updatedAt, task.updatedAt);
}
const history = await (await request("/tasks")).json();
assert.ok(tasks.every(({ task }) => history.some((record) => record.id === task.id)));

const accountEmail = `smoke-${Date.now()}@example.com`;
const accountPassword = "smoke-password-123";
const oldOwnerCookie = cookie;
const registered = await request("/account/register", {
  method: "POST",
  body: JSON.stringify({ email: accountEmail, password: accountPassword }),
}, 200);
const registeredCookie = remember(registered);
const registeredSession = await (await request("/session", { method: "POST" })).json();
assert.equal(registeredSession.email, accountEmail.toLowerCase());
await request("/tasks", {}, 401, oldOwnerCookie);
const registeredHistory = await (await request("/tasks")).json();
assert.ok(tasks.every(({ task }) => registeredHistory.some((record) => record.id === task.id)));

const loggedOut = await request("/account/logout", { method: "POST" }, 200);
remember(loggedOut);
const anonymousSession = await (await request("/session", { method: "POST" })).json();
assert.equal(anonymousSession.email, null);
assert.deepEqual(await (await request("/tasks")).json(), []);
await request("/tasks", {}, 401, registeredCookie);

const loggedIn = await request("/account/login", {
  method: "POST",
  body: JSON.stringify({ email: accountEmail.toUpperCase(), password: accountPassword }),
}, 200);
remember(loggedIn);
const accountSession = await (await request("/session", { method: "POST" })).json();
assert.equal(accountSession.email, accountEmail.toLowerCase());
const loggedInHistory = await (await request("/tasks")).json();
assert.ok(tasks.every(({ task }) => loggedInHistory.some((record) => record.id === task.id)));

const mergeIdentity = await session(true);
assert.notEqual(mergeIdentity.id, registeredSession.id);
const mergeAsset = await upload("cloud-pet");
const mergeResponse = await request("/tasks", {
  method: "POST",
  body: JSON.stringify({ templateId: "cloud-pet", assetIds: [mergeAsset.id], simulation: { stepDelayMs: 150 } }),
}, 202);
const mergeTask = await mergeResponse.json();
const mergeCookie = cookie;
assert.equal((await waitForTask(mergeTask.id)).status, "SUCCEEDED");
const mergedLogin = await request("/account/login", {
  method: "POST",
  body: JSON.stringify({ email: accountEmail, password: accountPassword }),
}, 200);
remember(mergedLogin);
const mergedHistory = await (await request("/tasks")).json();
assert.ok(mergedHistory.some((record) => record.id === mergeTask.id));
assert.ok(tasks.every(({ task }) => mergedHistory.some((record) => record.id === task.id)));
await request("/tasks", {}, 401, mergeCookie);

console.log("PASS: template filtering, upload validation, acceptance, three stage boundaries, deterministic failures, terminal reads, private results, identity isolation, account registration, logout/login, anonymous task merge, session rotation, and history persistence.");
