"use client";

import { ArrowUpRight, Images, LoaderCircle } from "lucide-react";
import Link from "next/link";
import { useTasks } from "@/hooks/use-tasks";
import { useSession } from "./session-provider";
import { TaskHistory } from "./task-history";

export function HistoryPage() {
  const { session, loading, error, retry } = useSession();
  return <main className="page-width personal-history"><section className="history-intro"><div><div className="eyebrow">YOUR PERSONAL COLLECTION</div><h1>我的作品<span className="heading-period">.</span></h1><p>那些一闪而过的灵感，都在这里好好收藏。</p></div><Link className="secondary-button" href="/">开始新创作 <ArrowUpRight size={16} /></Link></section>{loading ? <div className="empty-state"><LoaderCircle className="spin" size={25} /><p>正在连接你的创作空间…</p></div> : session ? <PersonalTasks key={session.id} userId={session.id} /> : <div className="empty-state"><Images size={28} /><h2>暂时无法读取作品</h2><p>{error}</p><button className="secondary-button" onClick={retry}>重新连接</button></div>}</main>;
}

function PersonalTasks({ userId }: { userId: string }) {
  const history = useTasks(userId);
  return <TaskHistory {...history} />;
}
