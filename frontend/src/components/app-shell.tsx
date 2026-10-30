"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Aperture, ChevronDown, CircleUserRound, Grid2X2, Images, ArrowUpRight } from "lucide-react";
import { useState, type ReactNode } from "react";
import { useSession } from "./session-provider";
import { AccountDialog } from "./account-dialog";

export function AppShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const { session, loading, error, retry } = useSession();
  const [accountOpen, setAccountOpen] = useState(false);
  return <>
    <header className="site-header">
      <Link className="brand" href="/" aria-label="Muse 灵感制作所首页"><span className="brand-icon"><Aperture size={25} strokeWidth={2.3} /></span><span className="brand-name">muse<span className="brand-dot">.</span></span><span className="brand-caption">灵感制作所</span></Link>
      <nav className="main-nav" aria-label="主导航"><Link className={!pathname.startsWith("/history") ? "active" : ""} href="/"><Grid2X2 size={16} />发现模板</Link><Link className={pathname.startsWith("/history") ? "active" : ""} href="/history"><Images size={17} />我的作品</Link></nav>
      <div className="header-right"><span className="demo-indicator"><span />模拟创作空间</span><button className="account-button" onClick={() => setAccountOpen(true)} aria-label={session?.email ? "管理账号" : "登录或注册"}><span className="avatar"><CircleUserRound size={18} /></span><span className="account-name">{loading ? "连接中" : session?.email || session?.displayName || "未连接"}</span><ChevronDown size={13} /></button></div>
    </header>
    {error && <div className="session-alert" role="alert"><span>{error}</span><button onClick={retry}>重新连接</button></div>}
    {children}
    <footer className="site-footer"><span>一点灵感，无限可能。</span><span>MUSE STUDIO <span className="footer-dot">·</span> 2026</span><span className="footer-note">为你的每个想象，留一份作品 <ArrowUpRight size={12} /></span></footer>
    <AccountDialog open={accountOpen} onClose={() => setAccountOpen(false)} />
  </>;
}
