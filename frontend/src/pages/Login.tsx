import { useState } from "react";
import { Link } from "react-router-dom";
import { useAuth } from "../auth";
import { Button, Card } from "../ui";

export default function Login() {
  const { login, signup } = useAuth();
  const [mode, setMode] = useState<"login" | "signup">("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(""); setBusy(true);
    try {
      if (mode === "login") await login(email, password);
      else await signup(email, password, displayName);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Something went wrong");
    } finally { setBusy(false); }
  }

  const input = "w-full rounded-lg border border-white/10 bg-white/5 px-3 py-2 text-sm text-slate-50 outline-none placeholder:text-slate-500 focus:border-indigo-400";
  return (
    <div className="grid min-h-screen place-items-center bg-[#14122a] px-4">
      <Card className="w-full max-w-sm">
        <Link to="/" className="mb-1 block text-2xl font-bold text-indigo-300">Squarely</Link>
        <p className="mb-5 text-sm text-slate-400">Split expenses without the screenshots.</p>
        <form onSubmit={submit} className="space-y-3">
          {mode === "signup" && (
            <input className={input} placeholder="Display name" value={displayName}
              onChange={(e) => setDisplayName(e.target.value)} required />
          )}
          <input className={input} type="email" placeholder="Email" value={email}
            onChange={(e) => setEmail(e.target.value)} required />
          <input className={input} type="password" placeholder="Password (min 8 chars)" value={password}
            onChange={(e) => setPassword(e.target.value)} required minLength={8} />
          {error && <p className="text-sm text-rose-400">{error}</p>}
          <Button type="submit" disabled={busy}>{busy ? "…" : mode === "login" ? "Log in" : "Sign up"}</Button>
        </form>
        <button className="mt-4 text-sm text-slate-400 hover:text-indigo-300"
          onClick={() => { setMode(mode === "login" ? "signup" : "login"); setError(""); }}>
          {mode === "login" ? "Need an account? Sign up" : "Have an account? Log in"}
        </button>
      </Card>
    </div>
  );
}
