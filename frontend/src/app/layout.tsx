import type { Metadata } from "next";
import { SessionProvider } from "@/components/session-provider";
import { AppShell } from "@/components/app-shell";
import "./globals.css";

export const metadata: Metadata = {
  title: { default: "Muse · 灵感制作所", template: "%s · Muse" },
  description: "选一个喜欢的模板，让想象有画面。一个可独立运行的素材创作空间。",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="zh-CN"><body><SessionProvider><AppShell>{children}</AppShell></SessionProvider></body></html>;
}
