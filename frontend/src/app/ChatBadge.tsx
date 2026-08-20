import * as React from 'react'
import * as PopoverPrimitive from '@radix-ui/react-popover'
import { ArrowLeft, MessageCircle, SendHorizonal } from 'lucide-react'
import { Link } from 'react-router-dom'
import { useQueryClient } from '@tanstack/react-query'
import {
  getListChatMessagesQueryKey,
  getListChatThreadsQueryKey,
  useListChatMessages,
  useListChatThreads,
  usePostChatMessage,
} from '@/api/generated/chat/chat'
import type { ChatMessage, ChatThread } from '@/api/generated/model'
import { useAuthStore } from '@/features/auth/authStore'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'

/**
 * The header's chat element — blueprint §7.2, beside the bell.
 *
 * <h2>Why this is a panel and no longer a link</h2>
 *
 * <p>Chat used to reach the user two ways and both were the wrong way round. A
 * message raised a **toast**, which is an interruption placed over whatever
 * they were doing — and a conversation produces interruptions faster than
 * anybody can absorb them, so ten messages between two other people meant ten
 * cards, and D-046's replay popped every one of them again on the next page
 * load. The badge meanwhile was a bare link, so the only way to *look* at chat
 * on purpose was to leave the screen you were on.
 *
 * <p>The two are swapped. Chat no longer toasts at all — `isChatNotification`,
 * in the notification stream — and this element is where it lands instead:
 * **nothing appears until it is opened**, and opening it shows everything.
 * Which is the deal the bell already offers, and the reason this file is
 * deliberately built to the same shape as `NotificationBell`: one popover, one
 * badge, one list.
 *
 * <h2>Two views, one popover</h2>
 *
 * <p>The thread list, and one conversation. Selecting a thread replaces the
 * list and the back arrow returns to it. The selection is **cleared when the
 * popover closes**, so opening it always lands on the list — reopening
 * straight into a conversation would be a chat box appearing unasked for,
 * which is the thing this change exists to remove.
 *
 * <h2>What this is not</h2>
 *
 * <p>Not a replacement for S-25, the full chat screen (D-065, Stream D). This
 * is the read-and-reply surface for somebody who is in the middle of something
 * else: recent messages and a one-line composer. Search, attachments, editing
 * and the §7.6 ticket cards belong on the screen, and "Open chat" goes there.
 */
export function ChatBadge() {
  const [open, setOpen] = React.useState(false)
  const [threadId, setThreadId] = React.useState<number | null>(null)

  /**
   * The thread list is fetched whether or not the panel is open, because the
   * badge is a summary of it — a count that only appeared once you had looked
   * would be no count at all. It renders nothing until then.
   */
  const threads = useListChatThreads()
  const threadList = React.useMemo<ChatThread[]>(() => threads.data?.data ?? [], [threads.data])
  const unreadCount = threadList.reduce((sum, t) => sum + (t.unreadCount ?? 0), 0)
  const selected = threadList.find((t) => t.id === threadId)

  function onOpenChange(next: boolean) {
    setOpen(next)
    if (!next) setThreadId(null)
  }

  return (
    <PopoverPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <PopoverPrimitive.Trigger asChild>
        <button
          type="button"
          aria-label={`Chat${unreadCount > 0 ? ` (${unreadCount} unread)` : ''}`}
          className="relative flex h-9 w-9 shrink-0 items-center justify-center rounded-control text-content-muted transition-colors hover:bg-subtle hover:text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
        >
          <MessageCircle className="h-4 w-4" />
          {unreadCount > 0 && (
            <span className="absolute right-1 top-1 flex h-4 min-w-4 items-center justify-center rounded-chip bg-danger px-1 text-[10px] font-semibold text-white">
              {unreadCount > 9 ? '9+' : unreadCount}
            </span>
          )}
        </button>
      </PopoverPrimitive.Trigger>
      <PopoverPrimitive.Portal>
        <PopoverPrimitive.Content
          align="end"
          sideOffset={8}
          aria-label="Chat"
          className="z-50 flex w-96 flex-col overflow-hidden rounded-control border border-border bg-surface shadow-modal"
        >
          <div className="flex items-center gap-2 border-b border-border px-3 py-2">
            {selected && (
              <button
                type="button"
                onClick={() => setThreadId(null)}
                aria-label="Back to all conversations"
                className="-ml-1 flex h-6 w-6 shrink-0 items-center justify-center rounded-control text-content-muted transition-colors hover:bg-subtle hover:text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
              >
                <ArrowLeft className="h-4 w-4" />
              </button>
            )}
            <p className="truncate text-sm font-semibold text-content">
              {selected ? threadName(selected) : 'Chat'}
            </p>
            <Button variant="ghost" size="sm" className="ml-auto shrink-0" asChild>
              <Link to="/chat" onClick={() => onOpenChange(false)}>
                Open chat
              </Link>
            </Button>
          </div>

          {selected ? (
            <Conversation thread={selected} />
          ) : (
            <ThreadList threads={threadList} isLoading={threads.isLoading} onSelect={setThreadId} />
          )}
        </PopoverPrimitive.Content>
      </PopoverPrimitive.Portal>
    </PopoverPrimitive.Root>
  )
}

// ------------------------------------------------------------------ the list

function ThreadList({
  threads,
  isLoading,
  onSelect,
}: {
  threads: ChatThread[]
  isLoading: boolean
  onSelect: (threadId: number) => void
}) {
  if (isLoading) {
    return (
      <div className="space-y-2 p-3">
        {[0, 1, 2].map((row) => (
          <Skeleton key={row} className="h-10 w-full" />
        ))}
      </div>
    )
  }

  if (threads.length === 0) {
    return <EmptyState title="No conversations" className="py-8" />
  }

  return (
    <ul className="max-h-96 overflow-y-auto">
      {threads.map((thread) => (
        <li key={thread.id}>
          <button
            type="button"
            onClick={() => thread.id != null && onSelect(thread.id)}
            className={cn(
              'flex w-full flex-col items-start gap-0.5 border-b border-border px-3 py-2.5 text-left transition-colors last:border-0 hover:bg-subtle',
              (thread.unreadCount ?? 0) > 0 && 'bg-primary-soft/40',
            )}
          >
            <span className="flex w-full items-center gap-2">
              <span className="truncate text-sm font-medium text-content">
                {threadName(thread)}
              </span>
              {(thread.unreadCount ?? 0) > 0 && (
                <span className="ml-auto flex h-4 min-w-4 shrink-0 items-center justify-center rounded-chip bg-danger px-1 text-[10px] font-semibold text-white">
                  {thread.unreadCount}
                </span>
              )}
            </span>
            <span className="text-xs text-content-muted">
              {kindLabel(thread)}
              {thread.lastMessageAt ? ` · ${shortTime(thread.lastMessageAt)}` : ''}
            </span>
          </button>
        </li>
      ))}
    </ul>
  )
}

// ---------------------------------------------------------- one conversation

function Conversation({ thread }: { thread: ChatThread }) {
  const threadId = thread.id as number
  const queryClient = useQueryClient()
  const me = useAuthStore((s) => s.user)
  const [draft, setDraft] = React.useState('')
  const bottom = React.useRef<HTMLDivElement>(null)

  /**
   * Polled rather than subscribed, for the reason `threadDestination` records
   * on the full screen (D-065): `ChatThread` carries the ticket *code*, not the
   * numeric row id its own backend publishes `/topic/ticket.{id}` under, so the
   * client cannot address two of the three kinds of room. Ten seconds, and only
   * while this panel is open on a thread — a closed panel polls nothing.
   */
  const messages = useListChatMessages(threadId, undefined, {
    query: { refetchInterval: 10_000 },
  })
  const rows = messages.data?.data ?? []

  const refresh = React.useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: getListChatMessagesQueryKey(threadId) })
    // The list behind this view carries unread counts and last-message times,
    // so posting changes that too.
    void queryClient.invalidateQueries({ queryKey: getListChatThreadsQueryKey() })
  }, [queryClient, threadId])

  const post = usePostChatMessage({ mutation: { onSuccess: refresh } })

  // Newest last, so the useful end is the bottom. Called optionally because
  // jsdom implements no `scrollIntoView` at all, and a panel that throws in
  // every test that opens a conversation is a high price for an affordance.
  React.useEffect(() => {
    bottom.current?.scrollIntoView?.({ block: 'end' })
  }, [rows.length])

  function send(event: React.FormEvent) {
    event.preventDefault()
    const body = draft.trim()
    if (!body || post.isPending) return
    // Cleared before the round trip completes: it is short, and a box that
    // stays full is one people re-send from.
    setDraft('')
    post.mutate({ threadId, data: { body } })
  }

  return (
    <>
      <div className="flex max-h-80 min-h-40 flex-col gap-2 overflow-y-auto p-3">
        {messages.isLoading && <Skeleton className="h-16 w-full" />}
        {!messages.isLoading && rows.length === 0 && (
          <EmptyState title="No messages yet" className="py-6" />
        )}
        {rows.map((message) => (
          <Message key={message.id} message={message} isMine={message.author?.id === me?.id} />
        ))}
        <div ref={bottom} />
      </div>

      <form onSubmit={send} className="flex items-center gap-2 border-t border-border p-2">
        <Input
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          placeholder="Write a message…"
          aria-label={`Message ${threadName(thread)}`}
        />
        <Button
          type="submit"
          size="sm"
          className="shrink-0"
          disabled={!draft.trim() || post.isPending}
          aria-label="Send message"
        >
          <SendHorizonal className="h-4 w-4" />
        </Button>
      </form>
      {post.isError && (
        <p role="alert" className="border-t border-border px-3 py-2 text-xs text-danger">
          That message did not send. Try again.
        </p>
      )}
    </>
  )
}

function Message({ message, isMine }: { message: ChatMessage; isMine: boolean }) {
  // A deleted message keeps its place and loses its body — the server withholds
  // it, so there is nothing to render even if this file wanted to.
  if (message.isDeleted) {
    return <p className="text-xs italic text-content-muted">Message deleted</p>
  }

  return (
    <div className={cn('flex flex-col gap-0.5', isMine && 'items-end')}>
      <p className="text-[11px] text-content-muted">
        {isMine ? 'You' : (message.author?.displayName ?? 'Someone')}
        {message.createdAt ? ` · ${shortTime(message.createdAt)}` : ''}
        {message.isEdited ? ' · edited' : ''}
      </p>
      <p
        className={cn(
          'max-w-[85%] whitespace-pre-wrap break-words rounded-control px-2.5 py-1.5 text-sm',
          isMine ? 'bg-primary text-white' : 'bg-subtle text-content',
        )}
      >
        {message.body}
      </p>
    </div>
  )
}

// ------------------------------------------------------------------- helpers

/** What to call a thread that was not given a title. */
function threadName(thread: ChatThread): string {
  if (thread.title) return thread.title
  if (thread.kind === 'DIRECT') {
    const names = (thread.participants ?? []).map((participant) => participant.displayName)
    if (names.length > 0) return names.join(', ')
  }
  return thread.ticketId ?? 'Conversation'
}

function kindLabel(thread: ChatThread): string {
  switch (thread.kind) {
    case 'TICKET':
      return 'Ticket'
    case 'PROJECT':
      return 'Project channel'
    case 'DIRECT':
      return 'Direct message'
    default:
      return 'Conversation'
  }
}

/**
 * Today gets a clock, anything older gets a date.
 *
 * Storage is UTC everywhere (CONVENTIONS.md); the browser's zone and locale are
 * applied here, in the presentation layer, and nowhere earlier.
 */
function shortTime(iso: string): string {
  const at = new Date(iso)
  if (Number.isNaN(at.getTime())) return ''
  const isToday = at.toDateString() === new Date().toDateString()
  return isToday
    ? at.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' })
    : at.toLocaleDateString(undefined, { day: 'numeric', month: 'short' })
}
