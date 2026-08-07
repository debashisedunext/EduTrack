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
