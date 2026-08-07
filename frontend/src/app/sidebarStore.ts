import { create } from 'zustand'

interface SidebarStore {
  collapsed: boolean
  toggle: () => void
}

// Collapsible 240px sidebar — blueprint §7.2. Persisted across navigation
// (not across reloads; that's a "remember my preference" nice-to-have, not
// part of C-005's scope).
export const useSidebarStore = create<SidebarStore>((set) => ({
  collapsed: false,
  toggle: () => set((s) => ({ collapsed: !s.collapsed })),
}))
