"use client";

import { AlertCircle } from "lucide-react";

export default function ErrorPage({ reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return <main className="page-width"><div className="empty-state gallery-empty" role="alert"><AlertCircle size={30} /><h1>页面暂时出了点问题</h1><p>后台已经受理的制作任务不受影响。请重新加载后查看记录。</p><button className="secondary-button" onClick={reset}>重新加载页面</button></div></main>;
}
