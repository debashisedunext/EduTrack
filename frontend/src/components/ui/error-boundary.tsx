import * as React from 'react'

import { Button } from './button'
import { EmptyState } from './empty-state'

interface ErrorBoundaryProps {
  /** Remounts the boundary's children when this changes — a route change is a
   * fresh screen, so a crash on the ticket detail page must not still be
   * showing when the reader navigates to My Tasks instead. */
  resetKey?: unknown
  children: React.ReactNode
}

interface ErrorBoundaryState {
  error: Error | null
}

/**
 * The app had no error boundary anywhere, so one uncaught render exception —
 * a malformed API field, a null a component didn't guard — unmounted the
 * whole React tree: a blank white page that the browser's Back button could
 * not recover from, because there was nothing left mounted to navigate.
 *
 * Scoped to `AppShell`'s `<Outlet />` rather than the app root: the sidebar,
 * top bar and command palette survive a crash in the routed page, so a
 * reader can still see where they are and navigate elsewhere.
 */
export class ErrorBoundary extends React.Component<ErrorBoundaryProps, ErrorBoundaryState> {
  state: ErrorBoundaryState = { error: null }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error }
  }

  componentDidCatch(error: Error, info: React.ErrorInfo) {
    console.error('Unhandled error in routed page', error, info.componentStack)
  }

  componentDidUpdate(prevProps: ErrorBoundaryProps) {
    if (this.state.error && prevProps.resetKey !== this.props.resetKey) {
      this.setState({ error: null })
    }
  }

  render() {
    if (this.state.error) {
      return (
        <div className="p-8">
          <EmptyState
            title="Something went wrong on this page"
            description="Try reloading. If this keeps happening, tell your administrator what you were doing."
            action={
              <Button size="sm" onClick={() => window.location.reload()}>
                Reload
              </Button>
            }
          />
        </div>
      )
    }
    return this.props.children
  }
}
