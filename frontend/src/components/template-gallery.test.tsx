import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";
import { api } from "../lib/api";
import type { Template } from "../lib/types";
import { TemplateGallery } from "./template-gallery";

vi.mock("../lib/api", async (importOriginal) => ({
  ...await importOriginal<typeof import("../lib/api")>(),
  api: vi.fn(),
}));

vi.mock("next/link", () => ({
  default: ({ href, children, className, "aria-label": ariaLabel }: {
    href: string;
    children?: ReactNode;
    className?: string;
    "aria-label"?: string;
  }) => <a href={href} className={className} aria-label={ariaLabel}>{children}</a>,
}));

const mockApi = vi.mocked(api);

const pet: Template = {
  id: "cloud-pet",
  title: "云朵小狗",
  category: "萌宠时刻",
  coverUrl: "/demo/pet.jpg",
  description: "宠物模板",
  requiredImageCount: 1,
  acceptedMimeTypes: ["image/jpeg", "image/png"],
  maxFileSizeBytes: 5242880,
  tag: null,
};

const postcard: Template = {
  id: "seaside-postcard",
  title: "寄自海岸的明信片",
  category: "旅行灵感",
  coverUrl: "/demo/coast.jpg",
  description: "旅行模板",
  requiredImageCount: 2,
  acceptedMimeTypes: ["image/jpeg", "image/png"],
  maxFileSizeBytes: 5242880,
  tag: null,
};

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((resolvePromise) => {
    resolve = resolvePromise;
  });
  return { promise, resolve };
}

beforeEach(() => {
  mockApi.mockReset();
});

afterEach(cleanup);

describe("TemplateGallery category switching", () => {
  it("keeps the previous grid visible while loading another category", async () => {
    const categories = ["萌宠时刻", "旅行灵感"];
    const nextTemplates = deferred<Template[]>();
    mockApi
      .mockResolvedValueOnce([pet])
      .mockResolvedValueOnce(categories)
      .mockReturnValueOnce(nextTemplates.promise)
      .mockResolvedValueOnce(categories);

    render(<TemplateGallery />);
    await waitFor(() => expect(screen.getByText("云朵小狗")).toBeTruthy());

    fireEvent.click(screen.getByRole("button", { name: "旅行灵感" }));
    await waitFor(() => expect(screen.getByText("正在切换分类…")).toBeTruthy());
    expect(screen.queryByLabelText("正在加载模板")).toBeNull();
    expect(screen.getByText("云朵小狗")).toBeTruthy();

    await act(async () => nextTemplates.resolve([postcard]));
    await waitFor(() => expect(screen.getByText("寄自海岸的明信片")).toBeTruthy());
    expect(screen.queryByText("云朵小狗")).toBeNull();
  });
});
