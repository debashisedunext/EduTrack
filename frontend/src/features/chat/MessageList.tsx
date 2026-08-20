import { type ChatMessage, ChatMessageKind } from '@/api/generated/model'
import { Skeleton } from '@/components/ui/skeleton'
import { EmptyState } from '@/components/ui/empty-state'

/**
 * D-065 · one thread's messages, oldest at the top.
 *
 * <p>Presentational on purpose: it takes messages and renders them, so the
 * behaviours worth testing — the tombstone, the edit marker, a system message
 * reading differently from a person's — are testable without a query client or
 * a socket.
 */
export function MessageList({
  messages,
  isLoading,
  loadError,
}: {
  messages: ChatMessage[]
  isLoading: boolean
  loadError: boolean
}) {
  if (isLoading) {
    return (
      <div className="space-y-3 p-4" aria-busy="true" aria-label="Loading messages">
        <Skeleton className="h-12 w-2/3" />
        <Skeleton className="h-12 w-1/2" />
        <Skeleton className="h-12 w-3/5" />
      </div>
    )
  }

  if (loadError) {
    return (
      <EmptyState
        title="This conversation could not be loaded"
        description="The messages are on the server; this is a problem reaching them. Try again in a moment."
      />
    )
  }

  if (messages.length === 0) {
    return <EmptyState title="No messages yet" description="Say something to start this conversation." />
  }

  return (
    <ol className="flex flex-col gap-3 p-4" aria-label="Messages">
      {messages.map((message) => (
        <MessageRow key={message.id} message={message} />
      ))}
    </ol>
  )
}

function MessageRow({ message }: { message: ChatMessage }) {
  const isSystem = message.kind === ChatMessageKind.SYSTEM

  return (
    <li className="flex flex-col gap-1">
      <div className="flex items-baseline gap-2">
        <span className="text-sm font-semibold text-ink">
          {isSystem ? 'EduTrack' : (message.author?.displayName ?? 'Unknown')}
        </span>
        {message.isEdited && !message.isDeleted && (
          // Shown rather than silently rewriting: §7.6 keeps chat admissible as
          // project evidence, and evidence that changed without saying so is
          // worth less than evidence that says when it changed.
          <span className="text-xs text-ink-3">edited</span>
        )}
      </div>

      {message.isDeleted ? (
        // The tombstone, not the body. The server withholds a deleted body on
        // read — this is the same rule rendered, so a client that somehow
        // received one still does not display it.
        <p className="text-sm italic text-ink-3">This message was deleted.</p>
      ) : (
        <p className="whitespace-pre-wrap text-sm text-ink">{message.body}</p>
      )}

    </li>
  )
}
