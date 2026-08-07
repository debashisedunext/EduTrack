import { create } from 'zustand'
import type { Project } from '@/api/generated/model/project'

interface CurrentProjectStore {
  project: Project | null
  setProject: (project: Project) => void
}

// Project switcher selection — read by future screens (ticket list, create
// form, etc.) to scope what they show. Only the switcher itself is C-005's
// job; consuming it is each of those screens' own future task.
export const useCurrentProjectStore = create<CurrentProjectStore>((set) => ({
  project: null,
  setProject: (project) => set({ project }),
}))
