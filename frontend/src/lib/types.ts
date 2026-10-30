export type Stage = "CHECK" | "GENERATE" | "FINALIZE";
export type TaskStatus = "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED";

export interface Session {
  id: string;
  displayName: string;
  email: string | null;
}

export interface Template {
  id: string;
  title: string;
  category: string;
  coverUrl: string;
  description: string;
  requiredImageCount: number;
  acceptedMimeTypes: string[];
  maxFileSizeBytes: number;
  tag: string | null;
}

export interface Asset {
  id: string;
  url: string;
  originalName: string;
  mimeType: string;
  sizeBytes: number;
}

export interface TaskEvent {
  stage: Stage;
  status: "RUNNING" | "SUCCEEDED" | "FAILED";
  startedAt: string;
  finishedAt: string | null;
}

export interface Task {
  id: string;
  templateId: string;
  templateTitle: string;
  templateCoverUrl: string;
  status: TaskStatus;
  currentStage: Stage | null;
  createdAt: string;
  updatedAt: string;
  resultUrl: string | null;
  failureReason: string | null;
  events: TaskEvent[];
}

export const STAGES: Stage[] = ["CHECK", "GENERATE", "FINALIZE"];
export const STAGE_LABELS: Record<Stage, string> = {
  CHECK: "素材检查",
  GENERATE: "模拟生成",
  FINALIZE: "结果整理",
};
export const STATUS_LABELS: Record<TaskStatus, string> = {
  QUEUED: "等待处理",
  RUNNING: "制作中",
  SUCCEEDED: "已完成",
  FAILED: "制作失败",
};
export const isPending = (task: Task) => task.status === "QUEUED" || task.status === "RUNNING";
