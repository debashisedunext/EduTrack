import { create } from 'zustand'

interface CommandPaletteStore {
  open: boolean
  setOpen: (open: boolean) => void
}

// Shared so both the global Ctrl+K listener and the top bar's hint button
// (TopBar.tsx) can open the same palette instance.
export const useCommandPaletteStore = create<CommandPaletteStore>((set) => ({
  open: false,
  setOpen: (open) => set({ open }),
}))
