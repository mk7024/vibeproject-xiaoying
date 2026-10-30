"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { ArrowUpRight, ImagePlus, Sparkles, LayoutGrid, PawPrint, Mountain, ScanFace, RefreshCw, AlertCircle } from "lucide-react";
import { api, errorMessage } from "@/lib/api";
import type { Template } from "@/lib/types";

const categoryIcons: Record<string, typeof LayoutGrid> = { "全部模板": LayoutGrid, "萌宠时刻": PawPrint, "旅行灵感": Mountain, "人像写真": ScanFace };

export function TemplateGallery() {
  const [category, setCategory] = useState("全部模板");
  const [categories, setCategories] = useState<string[]>([]);
  const [templates, setTemplates] = useState<Template[]>([]);
  const [loading, setLoading] = useState(true);
  const [switching, setSwitching] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);
  const loaded = useRef(false);
  useEffect(() => {
    const controller = new AbortController();
    if (loaded.current) setSwitching(true);
    else setLoading(true);
    setError(null);
    const query = category === "全部模板" ? "" : `?category=${encodeURIComponent(category)}`;
    Promise.all([api<Template[]>(`/templates${query}`, { signal: controller.signal }), api<string[]>("/categories", { signal: controller.signal })])
      .then(([data, groups]) => { if (!controller.signal.aborted) { setTemplates(data); setCategories(groups); loaded.current = true; } })
      .catch((error) => { if (!controller.signal.aborted) setError(errorMessage(error)); })
      .finally(() => { if (!controller.signal.aborted) { setLoading(false); setSwitching(false); } });
    return () => controller.abort();
  }, [category, revision]);

  return <main className="gallery-page page-width">
    <section className="gallery-intro"><div><div className="eyebrow"><span className="tiny-spark">✦</span> MADE OF LITTLE INSPIRATIONS</div><h1>让想象，<span>有画面。</span></h1><p>选一个喜欢的模板，把普通的一刻变成独一无二的作品。</p></div><div className="intro-art" aria-hidden="true"><div className="orbit orbit-one" /><div className="orbit orbit-two" /><Sparkles size={57} strokeWidth={1.1} /><span className="orbit-point" /></div></section>
    <div className="gallery-toolbar"><div className="category-tabs" aria-label="模板分类">{["全部模板", ...categories].map((name) => { const Icon = categoryIcons[name] || LayoutGrid; return <button key={name} className={name === category ? "selected" : ""} aria-pressed={name === category} onClick={() => setCategory(name)}><Icon size={15} />{name}</button>; })}</div><span className="template-count">{switching ? "正在切换分类…" : loading ? "正在发现灵感…" : `${templates.length} 个灵感模板`}</span></div>
    {error ? <div className="empty-state gallery-empty" role="alert"><AlertCircle size={30} /><h2>灵感暂时还没加载出来</h2><p>{error}</p><button className="secondary-button" onClick={() => setRevision((value) => value + 1)}><RefreshCw size={15} />重新加载</button></div> : loading && templates.length === 0 ? <div className="template-grid" aria-label="正在加载模板" aria-busy="true">{Array.from({ length: 6 }, (_, index) => <div key={index} className="template-skeleton skeleton" />)}</div> : templates.length === 0 ? <div className="empty-state gallery-empty"><ImagePlus size={32} /><h2>这里的灵感正在准备中</h2><p>试试切换其他分类。</p></div> : <div className="template-grid" aria-label="模板列表" aria-busy={switching}>{templates.map((template, index) => <Link className="template-card" href={`/templates/${template.id}`} key={template.id} aria-label={`使用${template.title}，需 ${template.requiredImageCount} 张图片`}>
      <img src={template.coverUrl} alt={template.title} loading={index < 6 ? "eager" : "lazy"} />
      <div className="template-shade" />
      <div className="card-top">{template.tag ? <span className={`template-tag ${template.tag === "热门" ? "tag-lime" : ""}`}>{template.tag === "热门" && <Sparkles size={11} />}{template.tag}</span> : <span />}<span className="image-count"><ImagePlus size={12} />{template.requiredImageCount} 张</span></div>
      <div className="card-bottom"><span className="card-category">{template.category}</span><div className="card-title-line"><h2>{template.title}</h2><span className="card-arrow"><ArrowUpRight size={18} /></span></div><span className="card-cta">用你的照片，重新演绎</span></div>
    </Link>)}</div>}
    <div className="gallery-bottom"><span className="small-spark" aria-hidden="true"><Sparkles size={16} /></span><p>每一份灵感，都从你的一张照片开始</p><span className="gallery-bottom-line" /></div>
    <section className="how-it-works" aria-label="如何制作"><div><span className="step-number">01</span><p><strong>发现你的灵感</strong><span>挑选喜欢的视觉模板</span></p></div><span className="step-connector" /><div><span className="step-number">02</span><p><strong>放入你的故事</strong><span>上传照片，交给后台制作</span></p></div><span className="step-connector" /><div><span className="step-number">03</span><p><strong>收藏这一刻</strong><span>随时回来，查看你的作品</span></p></div></section>
  </main>;
}
