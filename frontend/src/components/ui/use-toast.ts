import * as React from 'react'
import { create } from 'zustand'

export interface ToastOptions {
  title?: React.ReactNode
  description?: React.ReactNode
  variant?: 'default' | 'success' | 'danger'
  action?: React.ReactNode
  /** ms before auto-dismiss; Radix default is 5000. */
  duration?: number
}

export interface ToastItem extends ToastOptions {
  id: string
}

interface ToastStore {
  toasts: ToastItem[]
  add: (options: ToastOptions) => string
  dismiss: (id: string) => void
}

const useToastStore = create<ToastStore>((set) => ({
  toasts: [],
  add: (options) => {
    const id = crypto.randomUUID()
    set((state) => ({ toasts: [...state.toasts, { id, ...options }] }))
    return id
  },
  dismiss: (id) => set((state) => ({ toasts: state.toasts.filter((t) => t.id !== id) })),
}))

export function toast(options: ToastOptions) {
  return useToastStore.getState().add(options)
}

export function useToast() {
  const toasts = useToastStore((s) => s.toasts)
  const dismiss = useToastStore((s) => s.dismiss)
  return { toasts, dismiss }
}

/**
 * Test-only. The store is module-level so a toast fired in one test survives
 * into the next `render()` in the same file — `resetDb`'s own reason,
 * `src/test/setup.ts` calls this beside it in the global `afterEach` for the
 * same one. Without it, a later test querying a button whose accessible name
 * happens to collide with a still-mounted toast's dismiss button (both named
 * "Close" is the case that found this) gets an ambiguous-match failure that
 * has nothing to do with what it is testing.
 */
export function resetToasts() {
  useToastStore.setState({ toasts: [] })
}
