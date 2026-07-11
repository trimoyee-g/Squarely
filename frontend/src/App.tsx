import { lazy, Suspense } from "react";
import { Link, Navigate, Route, Routes, useLocation } from "react-router-dom";
import type { ReactNode } from "react";
import { useAuth } from "./auth";
import { Avatar } from "./ui";
import Login from "./pages/Login";
import Dashboard from "./pages/Dashboard";
import Groups from "./pages/Groups";
import GroupDetail from "./pages/GroupDetail";
import Settlements from "./pages/Settlements";
import Recurring from "./pages/Recurring";

const Landing = lazy(() => import("./pages/Landing"));

function Loading() {
  return <div className="grid h-screen place-items-center bg-[#14122a] text-slate-400">Loading…</div>;
}

function RequireAuth({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth();
  if (loading) return <Loading />;
  if (!user) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

function RedirectIfAuthed({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth();
  if (loading) return <Loading />;
  if (user) return <Navigate to="/dashboard" replace />;
  return <>{children}</>;
}

function Nav() {
  const { user, logout } = useAuth();
  const { pathname } = useLocation();
  const link = (to: string, label: string) => (
    <Link to={to} className={`rounded-lg px-3 py-1.5 text-sm font-medium ${
      pathname === to ? "bg-indigo-400/20 text-indigo-300" : "text-slate-300 hover:bg-white/10"}`}>{label}</Link>
  );
  return (
    <header className="border-b border-white/10 bg-[#14122a]">
      <div className="mx-auto flex max-w-5xl items-center justify-between px-4 py-3">
        <div className="flex items-center gap-2">
          <Link to="/" className="text-lg font-bold text-indigo-300">Squarely</Link>
          <nav className="ml-4 flex gap-1">
            {link("/dashboard", "Dashboard")}
            {link("/groups", "Groups")}
            {link("/settlements", "Settlements")}
            {link("/recurring", "Recurring")}
          </nav>
        </div>
        <div className="flex items-center gap-3 text-sm">
          {user && <Avatar id={user.id} name={user.displayName} size="sm" />}
          <span className="text-slate-400">{user?.displayName}</span>
          <button onClick={logout} className="text-slate-500 hover:text-slate-200">Sign out</button>
        </div>
      </div>
    </header>
  );
}

function AuthedShell() {
  return (
    <RequireAuth>
      <div className="min-h-screen bg-[#14122a] text-slate-50">
        <Nav />
        <main className="mx-auto max-w-5xl px-4 py-6">
          <Routes>
            <Route path="dashboard" element={<Dashboard />} />
            <Route path="groups" element={<Groups />} />
            <Route path="groups/:id" element={<GroupDetail />} />
            <Route path="settlements" element={<Settlements />} />
            <Route path="recurring" element={<Recurring />} />
            <Route path="*" element={<Navigate to="/dashboard" replace />} />
          </Routes>
        </main>
      </div>
    </RequireAuth>
  );
}

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Suspense fallback={<Loading />}><Landing /></Suspense>} />
      <Route path="/login" element={<RedirectIfAuthed><Login /></RedirectIfAuthed>} />
      <Route path="/*" element={<AuthedShell />} />
    </Routes>
  );
}
