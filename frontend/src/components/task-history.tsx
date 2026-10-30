"use client";

import { useState } from "react";
import Link from "next/link";
import { AlertCircle, ArrowUpRight, Check, Circle, Clock3, ImageIcon, LoaderCircle, Maximize2, RefreshCw, Sparkles, WifiOff, X } from "lucide-react";
import { type Task, STAGES, STAGE_LABELS, STATUS_LABELS, isPending } from "@/lib/types";
import { Dialog } from "./dialog";

type HistoryProps = { tasks: Task[]; loading: boolean; error: string | null; queryErrors: Record<string, string>; refresh: () => void; compact?: boolean; };

function formatTime(value: string) {
  return new Intl.DateTimeFormat("zh-CN", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false }).format(new Date(value));
}

export function TaskHistory({ tasks, loading, error, queryErrors, refresh, compact = false }: HistoryProps) {
  const [preview, setPreview] = useState<Task | null>(null);
  const pendingCount = tasks.filter(isPending).length;
  return <section className={`history-section ${compact ? "history-compact" : ""}`} aria-label="制作记录">
    <div className="history-heading"><div><h2>{compact ? "制作记录" : "所有作品"}<span className="number-badge">{tasks.length}</span></h2>{pendingCount > 0 && <p className="processing-note"><span className="live-dot" />{pendingCount} 个任务正在后台制作，离开页面也不会中断</p>}</div><button className="icon-button refresh-button" aria-label="刷新制作记录" disabled={loading} onClick={refresh}><RefreshCw size={17} className={loading ? "spin" : ""} /></button></div>
    {error && <div className="query-alert" role="alert"><WifiOff size={16} /><div><strong>暂时无法更新记录</strong><p>{error} 已有任务信息仍然保留。</p></div><button onClick={refresh}>重试</button></div>}
    {loading && tasks.length === 0 ? <div className="history-grid" aria-busy="true" aria-label="正在读取制作记录">{[0, 1].map((index) => <div className="task-skeleton skeleton" key={index} />)}</div> : tasks.length === 0 && !error ? <div className="empty-state history-empty"><div className="empty-art"><ImageIcon size={28} strokeWidth={1} /><span><Sparkles size={13} /></span></div><h3>第一份作品，从这里开始</h3><p>{compact ? "上传喜欢的照片，开启一次新的创作。" : "去发现一个喜欢的模板，留下你的第一份灵感。"}</p>{!compact && <Link className="secondary-button" href="/">发现模板 <ArrowUpRight size={15} /></Link>}<div className="empty-decoration" aria-hidden="true" /></div> : <div className="history-grid">{tasks.map((task) => <article className={`task-card task-${task.status.toLowerCase()}`} key={task.id} data-task-id={task.id}>
      <div className="task-visual">
        {task.status === "SUCCEEDED" && task.resultUrl ? <button className="result-button" onClick={() => setPreview(task)} aria-label={`预览${task.templateTitle}的制作结果`}><img src={task.resultUrl} alt={`${task.templateTitle}的制作结果`} /><span className="preview-hint"><Maximize2 size={16} />预览作品</span></button> : <><img className="task-backdrop" src={task.templateCoverUrl} alt="" /><div className="task-visual-shade" /><div className="task-stage-display">{task.status === "FAILED" ? <span className="failure-symbol"><AlertCircle size={24} strokeWidth={1.5} /></span> : task.status === "QUEUED" ? <span className="pending-symbol"><Clock3 size={25} strokeWidth={1.5} /></span> : <span className="processing-orbit"><Sparkles size={26} strokeWidth={1.4} /></span>}<strong>{task.status === "FAILED" ? "这次创作遇到了一点问题" : task.status === "QUEUED" ? "灵感已就位" : "灵感正在成形"}</strong><span>{task.status === "FAILED" ? (task.currentStage ? `${STAGE_LABELS[task.currentStage]}阶段失败` : "任务未能开始") : task.currentStage ? STAGE_LABELS[task.currentStage] : "等待后台开始处理"}</span></div></>}
        <span className={`status-pill status-${task.status.toLowerCase()}`}>{task.status === "SUCCEEDED" ? <Check size={11} /> : task.status === "RUNNING" ? <LoaderCircle size={11} className="spin" /> : task.status === "FAILED" ? <X size={11} /> : <Clock3 size={11} />}{STATUS_LABELS[task.status]}</span>
      </div>
      <div className="task-info"><h3>{task.templateTitle}</h3><time dateTime={task.createdAt}>{formatTime(task.createdAt)}</time>
        <ol className="stage-track" aria-label="任务处理阶段">{STAGES.map((stage) => { const event = task.events.find((event) => event.stage === stage); const state = event?.status || "WAITING"; return <li key={stage} className={`stage-${state.toLowerCase()}`} title={`${STAGE_LABELS[stage]}：${state === "SUCCEEDED" ? "已完成" : state === "FAILED" ? "失败" : state === "RUNNING" ? "处理中" : "等待"}`}><span>{state === "SUCCEEDED" ? <Check size={9} /> : state === "FAILED" ? <X size={9} /> : state === "RUNNING" ? <LoaderCircle size={9} className="spin" /> : <Circle size={5} />}</span>{STAGE_LABELS[stage]}</li>; })}</ol>
        {task.failureReason && <p className="task-failure">{task.failureReason}</p>}
        {queryErrors[task.id] && <div className="task-query-error" role="alert"><WifiOff size={13} /><span>状态更新中断，任务仍以后台为准。</span><button onClick={refresh} aria-label={`恢复查询${task.templateTitle}`}>恢复查询</button></div>}
      </div>
    </article>)}</div>}
    <Dialog open={preview !== null} onClose={() => setPreview(null)} title={preview?.templateTitle || "作品预览"} wide>{preview?.resultUrl && <><img className="result-preview-image" src={preview.resultUrl} alt={`${preview.templateTitle}的完整制作结果`} /><p className="preview-description">模拟制作结果 · {formatTime(preview.createdAt)}</p></>}</Dialog>
  </section>;
}
