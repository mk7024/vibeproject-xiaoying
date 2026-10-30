"use client";

import { useEffect, useState } from "react";
import { AlertCircle, CheckCircle2, CircleUserRound, LoaderCircle, ShieldCheck } from "lucide-react";
import { errorMessage } from "@/lib/api";
import { Dialog } from "./dialog";
import { useSession } from "./session-provider";

type AuthMode = "login" | "register";

export function AccountDialog({ open, onClose }: { open: boolean; onClose: () => void }) {
  const { session, loading, error, retry, register, login, logout, changePassword, newSession } = useSession();
  const [mode, setMode] = useState<AuthMode>("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [formError, setFormError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmNewPassword, setConfirmNewPassword] = useState("");
  const [passwordError, setPasswordError] = useState<string | null>(null);
  const [passwordSuccess, setPasswordSuccess] = useState<string | null>(null);
  const [passwordPending, setPasswordPending] = useState(false);
  const [logoutError, setLogoutError] = useState<string | null>(null);
  const [logoutPending, setLogoutPending] = useState(false);
  const [switchConfirm, setSwitchConfirm] = useState(false);
  const [switchError, setSwitchError] = useState<string | null>(null);
  const [switchPending, setSwitchPending] = useState(false);

  useEffect(() => {
    if (open) return;
    setMode("login");
    setEmail("");
    setPassword("");
    setConfirmPassword("");
    setFormError(null);
    setPending(false);
    setPasswordOpen(false);
    setCurrentPassword("");
    setNewPassword("");
    setConfirmNewPassword("");
    setPasswordError(null);
    setPasswordSuccess(null);
    setPasswordPending(false);
    setLogoutError(null);
    setLogoutPending(false);
    setSwitchConfirm(false);
    setSwitchError(null);
    setSwitchPending(false);
  }, [open]);

  function switchMode(next: AuthMode) {
    setMode(next);
    setPassword("");
    setConfirmPassword("");
    setFormError(null);
  }

  async function submitLogin(event: React.FormEvent) {
    event.preventDefault();
    if (pending) return;
    const value = email.trim();
    if (!value) {
      setFormError("请输入邮箱。");
      return;
    }
    setPending(true);
    setFormError(null);
    try {
      await login(value, password);
      onClose();
    } catch (cause) {
      setFormError(errorMessage(cause));
    } finally {
      setPending(false);
    }
  }

  async function submitRegister(event: React.FormEvent) {
    event.preventDefault();
    if (pending) return;
    const value = email.trim();
    if (!value) {
      setFormError("请输入邮箱。");
      return;
    }
    if (password.length < 8) {
      setFormError("密码至少需要 8 位。");
      return;
    }
    if (password !== confirmPassword) {
      setFormError("两次输入的密码不一致。");
      return;
    }
    setPending(true);
    setFormError(null);
    try {
      await register(value, password);
      onClose();
    } catch (cause) {
      setFormError(errorMessage(cause));
    } finally {
      setPending(false);
    }
  }

  async function submitPassword(event: React.FormEvent) {
    event.preventDefault();
    if (passwordPending) return;
    if (newPassword.length < 8) {
      setPasswordError("新密码至少需要 8 位。");
      return;
    }
    if (newPassword !== confirmNewPassword) {
      setPasswordError("两次输入的新密码不一致。");
      return;
    }
    setPasswordPending(true);
    setPasswordError(null);
    setPasswordSuccess(null);
    try {
      await changePassword(currentPassword, newPassword);
      setCurrentPassword("");
      setNewPassword("");
      setConfirmNewPassword("");
      setPasswordOpen(false);
      setPasswordSuccess("密码已更新，当前会话已刷新。");
    } catch (cause) {
      setPasswordError(errorMessage(cause));
    } finally {
      setPasswordPending(false);
    }
  }

  async function submitLogout() {
    if (logoutPending) return;
    setLogoutPending(true);
    setLogoutError(null);
    try {
      await logout();
      onClose();
    } catch (cause) {
      setLogoutError(errorMessage(cause));
    } finally {
      setLogoutPending(false);
    }
  }

  async function submitNewSpace() {
    if (switchPending) return;
    setSwitchPending(true);
    setSwitchError(null);
    try {
      await newSession();
      onClose();
    } catch (cause) {
      setSwitchError(errorMessage(cause));
    } finally {
      setSwitchPending(false);
    }
  }

  return <Dialog open={open} onClose={onClose} title="账号设置">
    {!session ? <div className="account-loading" aria-busy={loading}>
      {loading ? <><LoaderCircle size={22} className="spin" /><p>正在连接你的创作空间…</p></> : <>
        <AlertCircle size={24} />
        <p>{error || "暂时无法连接创作空间。"}</p>
        <button className="secondary-button" onClick={retry}>重新连接</button>
      </>}
    </div> : <>
      <div className="account-card">
        <span className="large-avatar"><CircleUserRound size={30} /></span>
        <div>
          <h3>{session.email || session.displayName || "匿名访客"}</h3>
          <p>{session.email ? `${session.displayName} · 已登录` : "匿名创作空间"}</p>
        </div>
      </div>
      <div className="identity-note">
        <ShieldCheck size={20} />
        <p>{session.email
          ? "作品保存在这个账号中，换设备登录后也能查看自己的制作记录。"
          : "无需注册即可制作；注册后当前匿名作品会归入账号，也可以在其他设备登录查看。"}</p>
      </div>

      {session.email ? <>
        <div className="account-divider" />
        <div className="account-section-heading">
          <h3 className="small-heading">账号安全</h3>
          <button className="secondary-button" type="button" onClick={() => {
            setPasswordOpen((value) => !value);
            setPasswordError(null);
            setPasswordSuccess(null);
          }}>{passwordOpen ? "取消" : "修改密码"}</button>
        </div>
        {passwordSuccess && <p className="account-success" role="status"><CheckCircle2 size={13} />{passwordSuccess}</p>}
        {passwordOpen && <form className="account-form" onSubmit={submitPassword}>
          <label className="account-field"><span>当前密码</span><input type="password" autoComplete="current-password" maxLength={72} required value={currentPassword} disabled={passwordPending} onChange={(event) => setCurrentPassword(event.target.value)} /></label>
          <label className="account-field"><span>新密码</span><input type="password" autoComplete="new-password" minLength={8} maxLength={72} required value={newPassword} disabled={passwordPending} onChange={(event) => setNewPassword(event.target.value)} /></label>
          <label className="account-field"><span>确认新密码</span><input type="password" autoComplete="new-password" minLength={8} maxLength={72} required value={confirmNewPassword} disabled={passwordPending} onChange={(event) => setConfirmNewPassword(event.target.value)} /></label>
          {passwordError && <p className="account-error" role="alert"><AlertCircle size={13} />{passwordError}</p>}
          <button className="primary-button full-width" type="submit" disabled={passwordPending}>{passwordPending && <LoaderCircle size={15} className="spin" />}{passwordPending ? "正在更新…" : "保存新密码"}</button>
        </form>}
        <div className="account-divider" />
        <button className="secondary-button full-width" type="button" disabled={logoutPending} onClick={submitLogout}>{logoutPending && <LoaderCircle size={15} className="spin" />}{logoutPending ? "正在退出…" : "退出登录"}</button>
        {logoutError && <p className="account-error" role="alert"><AlertCircle size={13} />{logoutError}</p>}
        <p className="account-note">退出后会回到一个新的匿名空间，账号作品不会被删除。</p>
      </> : <>
        <div className="account-divider" />
        <div className="account-tabs" role="tablist" aria-label="账号方式">
          <button type="button" role="tab" aria-selected={mode === "login"} className={mode === "login" ? "selected" : ""} onClick={() => switchMode("login")}>登录</button>
          <button type="button" role="tab" aria-selected={mode === "register"} className={mode === "register" ? "selected" : ""} onClick={() => switchMode("register")}>注册</button>
        </div>
        {mode === "login" ? <form className="account-form" onSubmit={submitLogin}>
          <label className="account-field"><span>邮箱</span><input type="email" autoComplete="email" inputMode="email" autoCapitalize="none" spellCheck={false} maxLength={254} required value={email} disabled={pending} onChange={(event) => setEmail(event.target.value)} /></label>
          <label className="account-field"><span>密码</span><input type="password" autoComplete="current-password" maxLength={72} required value={password} disabled={pending} onChange={(event) => setPassword(event.target.value)} /></label>
          {formError && <p className="account-error" role="alert"><AlertCircle size={13} />{formError}</p>}
          <button className="primary-button full-width" type="submit" disabled={pending}>{pending && <LoaderCircle size={15} className="spin" />}{pending ? "正在登录…" : "登录"}</button>
          <p className="account-note">登录后，当前匿名空间里的作品会自动并入这个账号。</p>
        </form> : <form className="account-form" onSubmit={submitRegister}>
          <label className="account-field"><span>邮箱</span><input type="email" autoComplete="email" inputMode="email" autoCapitalize="none" spellCheck={false} maxLength={254} required value={email} disabled={pending} onChange={(event) => setEmail(event.target.value)} /></label>
          <label className="account-field"><span>密码</span><input type="password" autoComplete="new-password" minLength={8} maxLength={72} required value={password} disabled={pending} onChange={(event) => setPassword(event.target.value)} /></label>
          <label className="account-field"><span>确认密码</span><input type="password" autoComplete="new-password" minLength={8} maxLength={72} required value={confirmPassword} disabled={pending} onChange={(event) => setConfirmPassword(event.target.value)} /></label>
          {formError && <p className="account-error" role="alert"><AlertCircle size={13} />{formError}</p>}
          <button className="primary-button full-width" type="submit" disabled={pending}>{pending && <LoaderCircle size={15} className="spin" />}{pending ? "正在创建…" : "注册并保存当前作品"}</button>
          <p className="account-note">密码至少 8 位。当前演示不发送验证邮件；注册会把当前匿名空间的作品带入账号。</p>
        </form>}
        <div className="account-divider" />
        <h3 className="small-heading">匿名空间</h3>
        <p className="account-explanation">创建一个新的匿名空间会离开当前匿名作品，旧空间无法从本浏览器返回。</p>
        {switchConfirm ? <div className="confirm-box">
          <p>确定离开当前匿名空间？这不会删除账号或旧空间的数据，但本浏览器将无法恢复旧匿名空间。</p>
          <div className="button-row">
            <button className="secondary-button" type="button" onClick={() => setSwitchConfirm(false)}>取消</button>
            <button className="primary-button" type="button" disabled={switchPending} onClick={submitNewSpace}>{switchPending && <LoaderCircle size={15} className="spin" />}{switchPending ? "正在创建…" : "确认创建"}</button>
          </div>
        </div> : <button className="secondary-button full-width" type="button" disabled={switchPending} onClick={() => setSwitchConfirm(true)}>创建新的匿名空间</button>}
        {switchError && <p className="account-error" role="alert"><AlertCircle size={13} />{switchError}</p>}
      </>}
    </>}
  </Dialog>;
}
