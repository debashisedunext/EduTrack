import * as React from 'react'
import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ErrorBoundary } from './error-boundary'

/**
 * The app had no error boundary anywhere, so a render exception on a routed
 * page — see `avatar-stack.test.tsx`'s note on the S-20 bug that exposed it —
 * unmounted the whole React tree to a blank page the browser's Back button
 * could not recover from. This pins the two things that fix depends on:
 * the fallback renders instead of the crash propagating past `AppShell`, and
 * a route change (`resetKey`) clears it rather than leaving the fallback
 * stuck on screen after the reader has navigated away.
 */
function Bomb(): React.ReactElement {
  throw new Error('boom')
}

describe('ErrorBoundary', () => {
  it('renders a fallback instead of letting the error propagate', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    render(
      <ErrorBoundary resetKey="/tickets/CRM-26-00068">
        <Bomb />
      </ErrorBoundary>,
    )
    expect(screen.getByText('Something went wrong on this page')).toBeInTheDocument()
    spy.mockRestore()
  })

  it('renders children normally when nothing throws', () => {
    render(
      <ErrorBoundary resetKey="/tickets">
        <p>All good</p>
      </ErrorBoundary>,
    )
    expect(screen.getByText('All good')).toBeInTheDocument()
  })

  it('clears the fallback when resetKey changes — a route change is a fresh screen', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const { rerender } = render(
      <ErrorBoundary resetKey="/tickets/CRM-26-00068">
        <Bomb />
      </ErrorBoundary>,
    )
    expect(screen.getByText('Something went wrong on this page')).toBeInTheDocument()

    rerender(
      <ErrorBoundary resetKey="/tickets">
        <p>Back on the list</p>
      </ErrorBoundary>,
    )
    expect(screen.getByText('Back on the list')).toBeInTheDocument()
    spy.mockRestore()
  })

  it('reloads the page from the fallback action', async () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    const reload = vi.fn()
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { ...window.location, reload },
    })

    render(
      <ErrorBoundary resetKey="/tickets/CRM-26-00068">
        <Bomb />
      </ErrorBoundary>,
    )
    await userEvent.click(screen.getByRole('button', { name: 'Reload' }))
    expect(reload).toHaveBeenCalledOnce()
    spy.mockRestore()
  })
})
