import * as React from 'react'
import * as TooltipPrimitive from '@radix-ui/react-tooltip'
import { cn } from '@/lib/utils'

/**
 * C-052 · the shared rich-hover primitive, first needed for the ribbon
 * segment's entered/exited/owner/note/effort/idle detail — `title` is the
 * browser's native fallback and cannot carry more than one line, structure,
 * or a keyboard-reachable focus state.
 *
 * `TooltipProvider` is exported rather than assumed global: each consumer
 * wraps its own trigger, so `RibbonSegment` (and Storybook, and any future
 * caller) stays self-contained instead of depending on a provider mounted
 * once in `App.tsx`. Radix nests providers without conflict.
 */
export const TooltipProvider = TooltipPrimitive.Provider
export const Tooltip = TooltipPrimitive.Root
export const TooltipTrigger = TooltipPrimitive.Trigger

export const TooltipContent = React.forwardRef<
  React.ElementRef<typeof TooltipPrimitive.Content>,
  React.ComponentPropsWithoutRef<typeof TooltipPrimitive.Content>
>(({ className, sideOffset = 6, ...props }, ref) => (
  <TooltipPrimitive.Portal>
    <TooltipPrimitive.Content
      ref={ref}
      sideOffset={sideOffset}
      className={cn(
        'z-50 max-w-xs rounded-card border border-border bg-surface px-3 py-2 text-caption text-content shadow-modal',
        className,
      )}
      {...props}
    />
  </TooltipPrimitive.Portal>
))
TooltipContent.displayName = TooltipPrimitive.Content.displayName
