"use client";

import { useEffect, useId, useRef, useState } from "react";
import { Check, ImagePlus, LoaderCircle, Replace, X, AlertCircle } from "lucide-react";
import { api, errorMessage } from "@/lib/api";
import type { Asset, Template } from "@/lib/types";

export function UploadSlot({ template, index, onChange, disabled }: { template: Template; index: number; onChange: (asset: Asset | null) => void; disabled: boolean }) {
  const inputId = useId();
  const input = useRef<HTMLInputElement>(null);
  const request = useRef<AbortController | null>(null);
  const objectUrl = useRef<string | null>(null);
  const [preview, setPreview] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [ready, setReady] = useState(false);
  const [dragging, setDragging] = useState(false);
  useEffect(() => () => { request.current?.abort(); if (objectUrl.current) URL.revokeObjectURL(objectUrl.current); }, []);

  async function upload(file: File) {
    if (disabled) return;
    if (!template.acceptedMimeTypes.includes(file.type)) { setError("请选择 JPG 或 PNG 格式的图片。"); return; }
    if (file.size === 0 || file.size > template.maxFileSizeBytes) { setError(`图片不能为空，且不能超过 ${template.maxFileSizeBytes / 1048576} MB。`); return; }
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    if (objectUrl.current) URL.revokeObjectURL(objectUrl.current);
    objectUrl.current = URL.createObjectURL(file);
    setPreview(objectUrl.current);
    setName(file.name);
    setBusy(true);
    setReady(false);
    setError(null);
    onChange(null);
    const body = new FormData();
    body.append("file", file);
    body.append("templateId", template.id);
    try {
      const asset = await api<Asset>("/assets", { method: "POST", body, signal: controller.signal });
      if (!controller.signal.aborted) { onChange(asset); setReady(true); }
    } catch (error) {
      if (!controller.signal.aborted) setError(errorMessage(error));
    } finally {
      if (!controller.signal.aborted) setBusy(false);
    }
  }

  function remove() {
    request.current?.abort();
    if (objectUrl.current) URL.revokeObjectURL(objectUrl.current);
    objectUrl.current = null;
    setPreview(null); setName(""); setError(null); setBusy(false); setReady(false);
    onChange(null);
    if (input.current) input.current.value = "";
  }

  return <div className="upload-wrapper">
    <div className={`upload-slot ${preview ? "has-preview" : ""} ${dragging ? "dragging" : ""} ${error ? "upload-error" : ""}`} onDragOver={(event) => { event.preventDefault(); if (!disabled) setDragging(true); }} onDragLeave={() => setDragging(false)} onDrop={(event) => { event.preventDefault(); setDragging(false); if (event.dataTransfer.files.length !== 1) { setError("每个上传位请选择一张图片。"); return; } void upload(event.dataTransfer.files[0]); }}>
      <input ref={input} id={inputId} type="file" className="visually-hidden" accept={template.acceptedMimeTypes.join(",")} disabled={disabled} aria-label={`上传第 ${index + 1} 张图片`} onChange={(event) => { const file = event.target.files?.[0]; if (file) void upload(file); event.target.value = ""; }} />
      <label htmlFor={inputId} className="upload-label">
        {preview ? <><img src={preview} alt={`素材 ${index + 1} 预览`} /><span className="upload-overlay">{busy ? <><LoaderCircle size={21} className="spin" /><span>正在上传…</span></> : <><Replace size={18} /><span>点击替换</span></>}</span></> : <><span className="upload-symbol"><ImagePlus size={23} strokeWidth={1.5} /></span><strong>上传{template.requiredImageCount > 1 ? `第 ${index + 1} 张` : "你的"}图片</strong><span>点击选择，或拖拽到这里</span></>}
      </label>
      {preview && <button type="button" className="remove-upload" onClick={remove} disabled={disabled} aria-label={`移除第 ${index + 1} 张图片`}><X size={14} /></button>}
      {ready && <span className="upload-check"><Check size={12} />已上传</span>}
    </div>
    {name && <p className="upload-file-name" title={name}>{name}</p>}
    {error && <p className="field-error" role="alert"><AlertCircle size={13} />{error}</p>}
  </div>;
}
