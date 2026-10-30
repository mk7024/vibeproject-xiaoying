"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { ArrowLeft, ArrowUpRight, AlertCircle, CheckCircle2, ImagePlus, LoaderCircle, SlidersHorizontal, Sparkles } from "lucide-react";
import { api, ApiError, errorMessage } from "@/lib/api";
import { type Asset, type Stage, type Task, type Template } from "@/lib/types";
import { useTasks } from "@/hooks/use-tasks";
import { useSession } from "./session-provider";
import { TaskHistory } from "./task-history";
import { UploadSlot } from "./upload-slot";

export function Studio({ templateId }: { templateId: string }) {
  const [template, setTemplate] = useState<Template | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notFound, setNotFound] = useState(false);
  const [revision, setRevision] = useState(0);
  const { session, loading: sessionLoading, error: sessionError, retry } = useSession();
  useEffect(() => {
    const controller = new AbortController();
    setLoading(true); setError(null); setNotFound(false);
    api<Template>(`/templates/${encodeURIComponent(templateId)}`, { signal: controller.signal }).then((data) => { if (!controller.signal.aborted) setTemplate(data); }).catch((error) => { if (!controller.signal.aborted) { setError(errorMessage(error)); setNotFound(error instanceof ApiError && error.status === 404); } }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [templateId, revision]);
  if (loading || sessionLoading) return <main className="page-width studio-loading" aria-busy="true"><div className="skeleton" /><div className="skeleton" /></main>;
  if (error || !template) return <main className="page-width"><div className="empty-state gallery-empty" role="alert"><AlertCircle size={30} /><h1>{notFound ? "这个灵感模板不见了" : "暂时无法打开模板"}</h1><p>{error}</p><div className="button-row"><Link className="secondary-button" href="/"><ArrowLeft size={15} />返回模板</Link>{!notFound && <button className="primary-button" onClick={() => setRevision((n) => n + 1)}>重新加载</button>}</div></div></main>;
  if (!session) return <main className="page-width"><div className="empty-state gallery-empty"><AlertCircle size={30} /><h2>连接创作空间后即可开始</h2><p>{sessionError}</p><button className="secondary-button" onClick={retry}>重新连接</button></div></main>;
  return <Workspace key={`${session.id}:${template.id}`} template={template} userId={session.id} />;
}

function Workspace({ template, userId }: { template: Template; userId: string }) {
  const history = useTasks(userId);
  const [assets, setAssets] = useState<(Asset | null)[]>(Array(template.requiredImageCount).fill(null));
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [accepted, setAccepted] = useState(false);
  const [failStage, setFailStage] = useState<Stage | "">("");
  const [stepDelayMs, setStepDelayMs] = useState(2500);
  const inFlight = useRef(false);
  const controller = useRef<AbortController | null>(null);
  useEffect(() => () => controller.current?.abort(), []);
  const readyCount = assets.filter(Boolean).length;
  const ready = readyCount === template.requiredImageCount;
  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (inFlight.current || !ready) return;
    inFlight.current = true;
    setSubmitting(true); setSubmitError(null); setAccepted(false);
    const request = new AbortController(); controller.current = request;
    try {
      const task = await api<Task>("/tasks", { method: "POST", signal: request.signal, body: JSON.stringify({ templateId: template.id, assetIds: assets.map((asset) => asset!.id), simulation: { failStage: failStage || null, stepDelayMs } }) });
      if (!request.signal.aborted) { history.addTask(task); setAccepted(true); }
    } catch (error) {
      if (!request.signal.aborted) setSubmitError(`${errorMessage(error)} 若请求中途断开，请先刷新制作记录，确认是否已受理后再提交。`);
    } finally {
      inFlight.current = false;
      if (!request.signal.aborted) setSubmitting(false);
    }
  }
  return <main className="studio-page page-width">
    <div className="breadcrumb"><Link href="/"><ArrowLeft size={14} />发现模板</Link><span>/</span><span>{template.title}</span><span className="breadcrumb-end">YOUR NEXT CREATION</span></div>
    <div className="studio-layout"><section className="editor-panel"><div className="editor-title"><span className="section-eyebrow">灵感模板</span><span className="subtle-badge">{template.category}</span></div><h1>{template.title}</h1><p className="template-description">{template.description}</p><div className="template-example"><img src={template.coverUrl} className="example-blur" alt="" /><img src={template.coverUrl} className="example-image" alt={`${template.title}示例封面`} /><span className="example-caption"><Sparkles size={11} />效果示意</span></div>
      <form onSubmit={submit}><div className="upload-heading"><h2>上传素材</h2><span>{readyCount} / {template.requiredImageCount} 张</span></div><p className="upload-requirements">JPG、PNG · 每张不超过 {template.maxFileSizeBytes / 1048576} MB</p><div className={`upload-grid ${template.requiredImageCount > 1 ? "multiple" : ""}`}>{assets.map((_, index) => <UploadSlot key={index} template={template} index={index} disabled={submitting} onChange={(asset) => { setAssets((previous) => previous.map((value, i) => i === index ? asset : value)); setAccepted(false); }} />)}</div>
        <details className="simulation-controls"><summary><SlidersHorizontal size={13} />模拟调试<span>可选</span></summary><div className="simulation-fields"><label>模拟结果<select aria-label="模拟结果" value={failStage} onChange={(event) => setFailStage(event.target.value as Stage | "")} disabled={submitting}><option value="">全部成功</option><option value="CHECK">素材检查失败</option><option value="GENERATE">模拟生成失败</option><option value="FINALIZE">结果整理失败</option></select></label><label>每阶段耗时<select aria-label="每阶段耗时" value={stepDelayMs} onChange={(event) => setStepDelayMs(Number(event.target.value))} disabled={submitting}><option value={1000}>1 秒</option><option value={2500}>2.5 秒</option><option value={5000}>5 秒</option><option value={15000}>15 秒</option></select></label></div></details>
        <div className="submit-area">{submitError && <p className="field-error submit-message" role="alert"><AlertCircle size={14} />{submitError}</p>}{accepted && <p className="success-message submit-message" role="status"><CheckCircle2 size={14} />已加入制作队列，你可以继续创作。</p>}<button className="create-button" type="submit" disabled={!ready || submitting}>{submitting ? <LoaderCircle size={18} className="spin" /> : <Sparkles size={18} />}<span>{submitting ? "正在提交…" : "开始制作"}</span>{!submitting && <ArrowUpRight size={18} />}</button><p className="submit-note"><ImagePlus size={11} />模拟制作，不消耗积分</p></div>
      </form>
    </section><div className="workspace-history"><TaskHistory {...history} tasks={history.tasks.filter((task) => task.templateId === template.id)} compact /><div className="workspace-bottom"><span>每一份灵感，都会好好保存。</span><Link href="/history">查看全部作品 <ArrowUpRight size={12} /></Link></div></div></div>
  </main>;
}
