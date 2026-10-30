import Link from "next/link";
import { ArrowLeft, Compass } from "lucide-react";

export default function NotFound() {
  return <main className="page-width"><div className="empty-state gallery-empty"><Compass size={32} /><h1>灵感走到了一条小岔路</h1><p>这个页面不存在，回到模板库继续发现吧。</p><Link className="secondary-button" href="/"><ArrowLeft size={15} />返回模板库</Link></div></main>;
}
