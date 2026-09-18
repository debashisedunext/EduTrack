import { create } from 'zustand'

interface SidebarStore {
  collapsed: boolean
  toggle: () => void
}

// Collapsible 240px sidebar — blueprint §7.2. Persisted across navigation
// (not across reloads; that's a "remember my preference" nice-to-have, not
// part of C-005's scope).
//
// Collapsed by default: the rail opens as icons and the page name shows on
// hover, so the screen gets the width. The toggle above the menu expands it
// for anybody who prefers the labels, and the choice holds until the next
// reload.
export const useSidebarStore = create<SidebarStore>((set) => ({
  collapsed: true,
  toggle: () => set((s) => ({ collapsed: !s.collapsed })),
}))
