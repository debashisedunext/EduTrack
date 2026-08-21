import * as React from 'react'
import { Paperclip } from 'lucide-react'
import { useQueryClient } from '@tanstack/react-query'

import {
  useListChatThreads,
  useListChatMessages,
  usePostChatMessage,
  useUploadChatAttachment,
  getListChatMessagesQueryKey,
  getListChatThreadsQueryKey,
} from '@/api/generated/chat/chat'
import type { ChatThread } from '@/api/generated/model'
import { useRealtime } from '@/realtime/useRealtime'
import { ownQueue } from '@/realtime/destinations'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'

import { MessageList } from './MessageList'
import {
  groupThreads,
  kindLabel,
  threadDestination,
  threadNeedsPolling,
  belongsToThread,
} from './threadDestination'
import { parseChatMessageEvent } from './chatEvents'
import { AttachmentChip } from './ChatAttachments'
import { EmojiPicker } from './EmojiPicker'
import { insertEmoji } from './emoji'

/**
 * D-065 · S-25, the chat screen.
 *
 * <h2>One screen, three surfaces</h2>
 *
 * <p>Ticket threads, direct messages and project channels are one engine on the
 * server (D-050) and one list here. They differ in exactly one place — the
 * realtime destination their messages arrive on — and {@link threadDestination}
 * is that place, for the same reason `destinationsFor` is on the backend.
 *
 * <h2>Two subscriptions, not one per thread</h2>
 *
 * <p>The user's own queue is subscribed for as long as the screen is open,
 * because a direct message arrives there whether or not its thread is selected
 * — that is what makes the unread count move while you are reading something
 * else. The selected thread's own topic is subscribed in addition, and only
 * when it is a ticket thread.
 */
export function ChatPage() {
  const [selectedId, setSelectedId] = React.useState<number | null>(null)
  const queryClient = useQueryClient()

  const threads = useListChatThreads()
  const threadList: ChatThread[] = React.useMemo(() => threads.data?.data ?? [], [threads.data])

  // The first thread is selected once, when the list first arrives — not on
  // every render, or a refetch would drag the reader back to the top of the
  // list mid-conversation.
  React.useEffect(() => {
    if (selectedId === null && threadList.length > 0 && typeof threadList[0].id === 'number') {
      setSelectedId(threadList[0].id)
    }
  }, [selectedId, threadList])

  const selected = threadList.find((t) => t.id === selectedId)
  // Ticket threads and project channels cannot be subscribed — the contract
  // carries no numeric anchor to build their topic from (see
  // `threadDestination`). They are polled instead, so the screen is live for
  // every surface rather than only for direct messages. Ten seconds is a
  // deliberate compromise: fast enough that a conversation does not feel
  // frozen, slow enough that an idle tab is not a load generator. When the
  // contract grows a numeric anchor this becomes `undefined` for every thread.
  const pollInterval = threadNeedsPolling(selected) ? 10_000 : undefined

  const messages = useListChatMessages(selectedId ?? 0, undefined, {
    query: { enabled: selectedId !== null, refetchInterval: pollInterval },
  })

  const refreshThread = React.useCallback(
    (threadId: number) => {
      void queryClient.invalidateQueries({ queryKey: getListChatMessagesQueryKey(threadId) })
      // The sidebar carries unread counts and last-message times, so a message
      // landing in *any* thread changes it — including the one nobody is
      // looking at, which is the case that matters for a badge.
      void queryClient.invalidateQueries({ queryKey: getListChatThreadsQueryKey() })
    },
    [queryClient],
  )

  // Every direct message addressed to this user, plus D-041's notifications,
  // which `parseChatMessageEvent` drops.
  useRealtime(ownQueue(), (payload) => {
    const event = parseChatMessageEvent(payload)
    if (!event) return
    refreshThread(event.threadId)
  })

  // The selected thread's own topic, when it has one this client can name.
  // Today that is direct messages only, and the subscription above already
  // covers those — this stays because it is the seam the contract fix opens,
  // and because a second handler on one queue is harmless where a missing one
  // would be a silent regression.
  useRealtime(threadDestination(selected), (payload) => {
    const event = parseChatMessageEvent(payload)
    if (!event || !belongsToThread(event, selected)) return
    refreshThread(event.threadId)
  })

  return (
    <div className="flex h-[calc(100vh-8rem)] gap-4 p-6">
      <ThreadSidebar
        threads={threadList}
        isLoading={threads.isLoading}
        selectedId={selectedId}
        onSelect={setSelectedId}
      />

      <section className="flex min-w-0 flex-1 flex-col rounded-lg border border-line bg-bg">
        {selected ? (
          <>
            <header className="border-b border-line px-4 py-3">
              <h2 className="text-sm font-semibold text-ink">{selected.title ?? 'Conversation'}</h2>
              <p className="text-xs text-ink-3">
                {selected.participants?.length ?? 0} participant
                {(selected.participants?.length ?? 0) === 1 ? '' : 's'}
              </p>
            </header>

            <div className="min-h-0 flex-1 overflow-y-auto">
              <MessageList
                messages={messages.data?.data ?? []}
                isLoading={messages.isLoading}
                loadError={messages.isError}
              />
            </div>

            <Composer threadId={selected.id} onSent={refreshThread} />
          </>
        ) : (
          <EmptyState
            title="No conversation selected"
            description="Pick a thread on the left, or open one from a ticket."
          />
        )}
      </section>
    </div>
  )
}

function ThreadSidebar({
  threads,
  isLoading,
  selectedId,
  onSelect,
}: {
  threads: ChatThread[]
  isLoading: boolean
  selectedId: number | null
  onSelect: (id: number) => void
}) {
  if (isLoading) {
    return (
      <nav className="w-72 shrink-0 space-y-2 rounded-lg border border-line p-3" aria-busy="true">
        <Skeleton className="h-8 w-full" />
        <Skeleton className="h-8 w-full" />
        <Skeleton className="h-8 w-full" />
      </nav>
    )
  }

  if (threads.length === 0) {
    return (
      <nav className="w-72 shrink-0 rounded-lg border border-line p-3">
        <EmptyState title="No conversations" description="Threads appear here once you are in one." />
      </nav>
    )
  }

  return (
    <nav className="w-72 shrink-0 overflow-y-auto rounded-lg border border-line p-2" aria-label="Conversations">
      {groupThreads(threads).map((group) => (
        <section key={group.kind}>
          <h3 className="px-2 py-1 text-xs font-semibold uppercase tracking-wide text-ink-3">
            {kindLabel(group.kind)}
          </h3>
          <ul>
            {group.threads.map((thread) => (
              <li key={thread.id}>
                <button
                  type="button"
                  onClick={() => typeof thread.id === 'number' && onSelect(thread.id)}
                  aria-current={thread.id === selectedId ? 'true' : undefined}
                  className={`flex w-full items-center justify-between gap-2 rounded-md px-2 py-1.5 text-left text-sm ${
                    thread.id === selectedId ? 'bg-bg-3 text-ink' : 'text-ink-2 hover:bg-bg-2'
                  }`}
                >
                  <span className="min-w-0 flex-1 truncate">{thread.title ?? 'Conversation'}</span>
                  {(thread.unreadCount ?? 0) > 0 && (
                    <span
                      className="shrink-0 rounded-full bg-accent px-1.5 py-0.5 text-xs font-semibold text-white"
                      aria-label={`${thread.unreadCount} unread`}
                    >
                      {thread.unreadCount}
                    </span>
                  )}
                </button>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </nav>
  )
}

function Composer({ threadId, onSent }: { threadId: number | undefined; onSent: (threadId: number) => void }) {
  const [body, setBody] = React.useState('')
  const post = usePostChatMessage()
  const textareaRef = React.useRef<HTMLTextAreaElement>(null)
  const fileInputRef = React.useRef<HTMLInputElement>(null)

  /*
   * D-053 · §7.6's file and image share.
   *
   * **Upload and send are two requests, and the upload happens first.** The
   * file is sniffed, EXIF-stripped, stored and queued for the AV scan while
   * the author is still typing, so the send is instant and a file that will be
   * refused is refused before they have written anything — rather than after,
   * with their message lost.
   *
   * The ids are held here until the send. An id that never reaches a message
   * is a file nobody sent; the server carries an index for sweeping those, and
   * this side does not pretend to manage it.
   */
  const [pending, setPending] = React.useState<{ id: number; name: string }[]>([])
  const upload = useUploadChatAttachment()
  const [uploadError, setUploadError] = React.useState<string | null>(null)

  /*
   * D-053 · emoji, §7.6.
   *
   * Nothing about this touches the server. `chat_messages.body` is `utf8mb4`
   * — checked against the live column, not only the DDL — so an emoji is
   * already storable and searchable, and the mention parser reads `@handle`
   * rather than scanning code points. What was missing was only a way to
   * reach one without leaving the keyboard.
   *
   * Inserted at the caret, and the caret is put back after it, so picking an
   * emoji mid-sentence does not send the user to the end of their own
   * message. `setSelectionRange` has to run after React has re-rendered with
   * the new value, hence the effect below rather than a call here.
   */
  const pendingCaret = React.useRef<number | null>(null)
  React.useEffect(() => {
    const caret = pendingCaret.current
    if (caret == null) return
    pendingCaret.current = null
    const textarea = textareaRef.current
    if (!textarea) return
    textarea.focus()
    textarea.setSelectionRange(caret, caret)
  }, [body])

  const pickEmoji = (emoji: string) => {
    const textarea = textareaRef.current
    const start = textarea?.selectionStart ?? body.length
    const end = textarea?.selectionEnd ?? start
    const next = insertEmoji(body, start, end, emoji)
    pendingCaret.current = next.caret
    setBody(next.body)
  }

  const attach = (file: File) => {
    if (typeof threadId !== 'number') return
    setUploadError(null)
    upload.mutate(
      { threadId, data: { file } },
      {
        onSuccess: (response) => {
          const stored = response.data
          if (stored?.id != null) {
            // The **browser's** name on the pending chip, not the server's
            // echo. It is the name the user picked out of their own file
            // picker, and it is the only one that has meaning before the
            // message exists — the server's name is what the sent message
            // renders, and the two can legitimately differ once sniffing has
            // had its say about the extension.
            setPending((current) => [...current, { id: stored.id as number, name: file.name }])
          }
        },
        // Named rather than swallowed: the server refuses a type that is not
        // on the allow-list and a file over the size ceiling, and both are
        // things the sender can act on. A silent failure has them pressing
        // the button again.
        onError: () => setUploadError(`${file.name} could not be attached.`),
      },
    )
  }

  const trimmed = body.trim()
  // Disabled rather than posting and letting the server reject: `body` is
  // `@minLength 1` on the contract, so an empty send is a round trip whose only
  // possible outcome is an error message the user could have been spared.
  // D-053 · a message carrying only a file is a message. `body` is
  // `@minLength 1` on the contract, so the send supplies a single space rather
  // than widening the contract for a case the server has no other reason to
  // allow — and rather than inventing a filename caption, which would then be
  // searchable text nobody wrote.
  const canSend =
    (trimmed.length > 0 || pending.length > 0) && typeof threadId === 'number' && !post.isPending

  const send = () => {
    if (!canSend || typeof threadId !== 'number') return
    post.mutate(
      {
        threadId,
        data: {
          body: trimmed.length > 0 ? trimmed : ' ',
          attachmentIds: pending.map((file) => file.id),
        },
      },
      {
        onSuccess: () => {
          setBody('')
          setPending([])
          setUploadError(null)
          onSent(threadId)
        },
      },
    )
  }

  return (
    <form
      className="flex flex-col gap-2 border-t border-line p-3"
      onSubmit={(event) => {
        event.preventDefault()
        send()
      }}
    >
      {(pending.length > 0 || uploadError) && (
        <div className="flex flex-wrap items-center gap-2">
          {pending.map((file) => (
            <AttachmentChip
              key={file.id}
              name={file.name}
              onRemove={() => setPending((current) => current.filter((f) => f.id !== file.id))}
            />
          ))}
          {uploadError && (
            // role="alert" rather than a chip: an upload that failed is the
            // one thing here a keyboard user has no other way to learn about,
            // since the file simply never appears.
            <span role="alert" className="text-xs text-danger">
              {uploadError}
            </span>
          )}
        </div>
      )}

      <div className="flex items-end gap-2">
      <label className="sr-only" htmlFor="chat-composer">
        Message
      </label>
      <textarea
        id="chat-composer"
        ref={textareaRef}
        value={body}
        onChange={(event) => setBody(event.target.value)}
        onKeyDown={(event) => {
          // Enter sends, Shift+Enter breaks the line — the convention every
          // chat client has trained people into. Without the modifier check a
          // multi-line message is impossible to type.
          if (event.key === 'Enter' && !event.shiftKey) {
            event.preventDefault()
            send()
          }
        }}
        rows={2}
        placeholder="Write a message…"
        className="min-h-0 flex-1 resize-none rounded-md border border-line bg-bg px-3 py-2 text-sm text-ink"
      />
      {/*
        The input is hidden and driven by a real button, rather than styled
        directly: a bare <input type="file"> cannot be given a label a screen
        reader announces usefully, and its native text is unstyleable and
        differs per browser. The button carries the accessible name.
      */}
      <input
        ref={fileInputRef}
        type="file"
        className="hidden"
        onChange={(event) => {
          const file = event.target.files?.[0]
          if (file) attach(file)
          // Cleared so picking the *same* file twice fires change both times —
          // the one bug every file input has, and it reads as "the button
          // stopped working".
          event.target.value = ''
        }}
      />
      <Button
        type="button"
        variant="ghost"
        size="sm"
        aria-label="Attach a file"
        disabled={typeof threadId !== 'number' || upload.isPending}
        onClick={() => fileInputRef.current?.click()}
      >
        <Paperclip aria-hidden className="size-4" />
      </Button>
      <EmojiPicker onPick={pickEmoji} disabled={typeof threadId !== 'number'} />
      <Button type="submit" disabled={!canSend}>
        Send
      </Button>
      </div>
    </form>
  )
}
