/**
 * Placeholder shell. Stream C (Divyansh) replaces this in C-005 with the real
 * app shell: collapsible 240px sidebar, top bar with global search, project
 * switcher, notification bell, chat badge, avatar menu, toast layer.
 */
const STREAMS = [
  { id: 'A', name: 'Platform & Security', owner: 'Shivendra', accent: 'var(--primary)' },
  { id: 'B', name: 'Masters & Clients',   owner: 'Ayush',     accent: '#06B6D4' },
  { id: 'C', name: 'Tickets & Ribbon',    owner: 'Divyansh',  accent: '#8B5CF6' },
  { id: 'D', name: 'Engines & Realtime',  owner: 'Debashis',  accent: '#14B8A6' },
]

export default function App() {
  return (
    <div className="min-h-full p-8">
      <header className="mb-8">
        <p className="text-xs font-semibold tracking-wide text-primary">EDUTRACK</p>
        <h1 className="mt-1 text-2xl font-semibold text-content">
          Scaffold is running
        </h1>
        <p className="mt-1 text-content-muted">
          Vite · React 18 · TypeScript · Tailwind · TanStack Query — design tokens loaded from
          blueprint §12.1.
        </p>
      </header>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {STREAMS.map((s) => (
          <div
            key={s.id}
            className="rounded-card border border-border bg-surface p-5 shadow-rest"
            style={{ borderTop: `3px solid ${s.accent}` }}
          >
            <p className="text-xs font-semibold" style={{ color: s.accent }}>
              STREAM {s.id}
            </p>
            <p className="mt-1 font-semibold text-content">{s.name}</p>
            <p className="mt-0.5 text-sm text-content-muted">{s.owner}</p>
          </div>
        ))}
      </div>

      <p className="mt-8 text-sm text-content-muted">
        Next: run your stream skill in Claude Code — <code>/stream-platform</code>,{' '}
        <code>/stream-masters</code>, <code>/stream-tickets</code> or{' '}
        <code>/stream-engines</code>.
      </p>
    </div>
  )
}
