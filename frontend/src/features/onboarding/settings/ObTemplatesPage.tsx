import * as React from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import {
  getObNotificationTemplateVocabulary,
  listObNotificationTemplates,
} from '@/api/generated/onboarding-masters/onboarding-masters';
import type { ObNotificationTemplate } from '@/api/generated/model/obNotificationTemplate';
import http, { ApiError } from '@/api/http';
import { Button } from '@/components/ui/button';
import { Chip } from '@/components/ui/chip';
import { Input } from '@/components/ui/input';

/**
 * OB-12 — notification templates. B-113.
 *
 * Layout follows the prototype's `vMsgTpl()` in `docs/prototype/onboarding.html`:
 * one card per template — name, a channel chip, an approval chip, and the body
 * in a monospace block on the subtle background.
 *
 * ## Three things the screen has to say that the row does not
 *
 * **Mandatory.** `ESCALATION` and `SIGNOFF` mail cannot be switched off. The
 * contract asks for the toggle to be "a locked statement rather than a control
 * whose only outcome is a refusal" — so a mandatory template renders the words
 * "Always sent" and no switch at all. A disabled checkbox still invites a click.
 *
 * **Not deliverable.** Phase 2 sends email only; a `WHATSAPP` template can be
 * authored and stored and nothing will dispatch it. The prototype renders that
 * as the amber "Pending approval" chip — WhatsApp business templates wait on
 * provider approval, and until an adapter exists nothing sends them — where a
 * deliverable channel wears the green "Approved" chip. Saying so on the row is
 * the whole reason `isDeliverable` exists; otherwise the admin configures
 * something that queues forever looking correct.
 *
 * **The merge tags.** Served by the vocabulary rather than held here, because a
 * client with its own copy is a second copy of the server's list and a new event
 * would silently fail to appear. The palette is rendered beside the editor so
 * the tag is copied rather than typed, which is the cheapest way to avoid the
 * 400 the server would otherwise have to give.
 *
 * ## `If-Match` is hand-set
 *
 * orval omits header parameters, the same gap `obSettingsQueries.ts` and
 * `clientQueries.ts` document. It matters more here than almost anywhere: two
 * admins rewording one body is plausible, and the losing edit vanishes silently
 * while what reaches a client is wording nobody chose.
 */
export function ObTemplatesPage() {
  const queryClient = useQueryClient();

  const templates = useQuery({
    queryKey: ['obNotificationTemplates'],
    queryFn: () => listObNotificationTemplates(),
  });
  const vocabulary = useQuery({
    queryKey: ['obNotificationVocabulary'],
    queryFn: () => getObNotificationTemplateVocabulary(),
  });

  const [editing, setEditing] = React.useState<number | null>(null);

  if (templates.isPending) {
    return <p className="p-6 text-sm text-content-muted">Loading…</p>;
  }
  if (templates.isError) {
    return (
      <p
        role="alert"
        className="m-6 rounded-card bg-level-critical-soft p-3 text-sm text-danger-text"
      >
        {templates.error instanceof ApiError && templates.error.status === 403
          ? 'Notification templates are OB Admin only.'
          : 'These templates could not be loaded.'}
      </p>
    );
  }

  const rows = templates.data?.data ?? [];

  return (
    <div className="mx-auto w-full max-w-4xl p-6">
      <header>
        <h1 className="text-h1 text-content">Notification templates</h1>
        <p className="mt-0.5 text-sm text-content-muted">
          WhatsApp business templates need provider approval — submit them early;
          approval takes days to weeks. Escalation and sign-off mail cannot be switched
          off.
        </p>
      </header>

      <ul className="mt-6 flex flex-col gap-3">
        {rows.map((template) => (
          <li
            key={template.id}
            className="rounded-card border border-border bg-surface p-4 shadow-rest sm:px-5"
          >
            <TemplateRow
              template={template}
              mergeTags={vocabulary.data?.data.mergeTags ?? []}
              isEditing={editing === template.id}
              onEdit={() => setEditing(template.id)}
              onDone={() => {
                setEditing(null);
                void queryClient.invalidateQueries({ queryKey: ['obNotificationTemplates'] });
              }}
            />
          </li>
        ))}
      </ul>
    </div>
  );
}

/** The prototype's channel chips — WhatsApp green, Email blue, in-app neutral. */
function ChannelChip({ channel }: { channel: ObNotificationTemplate['channel'] }) {
  if (channel === 'WHATSAPP') {
    return (
      <Chip variant="success">
        <span aria-hidden="true">💬</span> WhatsApp
      </Chip>
    );
  }
  if (channel === 'EMAIL') {
    return (
      <Chip variant="info">
        <span aria-hidden="true">✉</span> Email
      </Chip>
    );
  }
  return <Chip variant="neutral">In-app</Chip>;
}

function TemplateRow({
  template,
  mergeTags,
  isEditing,
  onEdit,
  onDone,
}: {
  template: ObNotificationTemplate;
  mergeTags: string[];
  isEditing: boolean;
  onEdit: () => void;
  onDone: () => void;
}) {
  const [subject, setSubject] = React.useState(template.subjectTemplate ?? '');
  const [body, setBody] = React.useState(template.bodyTemplate);
  const [error, setError] = React.useState<string | null>(null);

  const save = useMutation({
    mutationFn: async () => {
      const response = await http<{ data: ObNotificationTemplate }>({
        url: `/onboarding/notification-templates/${template.id}`,
        method: 'PATCH',
        // `*` only because this list read carries no per-row ETag of its own.
        // Stated rather than hidden: the guard is weaker here than on OB-11,
        // and closing it properly means the list serving a tag per row.
        headers: { 'If-Match': '*' },
        data: { subjectTemplate: subject || null, bodyTemplate: body },
      });
      return response.data;
    },
    onSuccess: onDone,
    onError: (caught: unknown) => setError(messageFor(caught)),
  });

  return (
    <>
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="font-mono text-sm font-semibold text-content">
          {template.eventCode}
        </h2>
        <ChannelChip channel={template.channel} />
        {template.isDeliverable ? (
          <Chip variant="success">
            <span aria-hidden="true">✓</span> Approved
          </Chip>
        ) : (
          <Chip
            variant="warning"
            title="Authored, not yet sending — no adapter dispatches this channel in this deployment"
          >
            <span aria-hidden="true">⏳</span> Pending approval
          </Chip>
        )}
        <span className="flex-1" />
        <span className="flex items-center gap-3 text-xs">
          {template.isMandatory ? (
            // A locked statement, not a disabled control — a greyed switch
            // still invites a click, and its only outcome would be a 409.
            <Chip variant="neutral">Always sent</Chip>
          ) : (
            <span className="text-content-muted">{template.isActive ? 'On' : 'Off'}</span>
          )}
          {!isEditing ? (
            <Button variant="secondary" size="sm" onClick={onEdit}>Edit wording</Button>
          ) : null}
        </span>
      </div>
      <p className="mt-1 text-caption text-content-muted">
        {template.category} · to {template.recipients?.join(', ')}
      </p>

      {!isEditing ? (
        <p className="mt-2.5 whitespace-pre-wrap rounded-control bg-subtle px-3 py-2.5 font-mono text-caption text-content">
          {template.bodyTemplate}
        </p>
      ) : null}

      {isEditing ? (
        <form
          className="mt-4"
          onSubmit={(event) => {
            event.preventDefault();
            setError(null);
            save.mutate();
          }}
        >
          {error ? (
            <p
              role="alert"
              className="mb-3 rounded-card bg-level-critical-soft p-3 text-sm text-danger-text"
            >
              {error}
            </p>
          ) : null}

          {template.channel === 'EMAIL' ? (
            <>
              <label
                htmlFor={`subject-${template.id}`}
                className="block text-sm font-medium text-content"
              >
                Subject
              </label>
              <Input
                id={`subject-${template.id}`}
                className="mt-1"
                value={subject}
                onChange={(e) => setSubject(e.target.value)}
                maxLength={255}
              />
            </>
          ) : null}

          <label
            htmlFor={`body-${template.id}`}
            className="mt-3 block text-sm font-medium text-content"
          >
            Body
          </label>
          <textarea
            id={`body-${template.id}`}
            className="mt-1 w-full rounded-control border border-border bg-surface p-3 font-mono text-sm text-content focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-1"
            rows={6}
            value={body}
            onChange={(e) => setBody(e.target.value)}
            maxLength={20000}
            required
          />

          <p className="mt-2 text-caption text-content-muted">
            Merge tags — click to insert. A tag that is not on this list is refused when
            you save, because it would reach the client as literal braces.
          </p>
          <div className="mt-1 flex flex-wrap gap-1">
            {mergeTags.map((tag) => (
              <button
                key={tag}
                type="button"
                className="rounded-control bg-subtle px-2 py-1 font-mono text-xs text-content-muted transition-colors hover:bg-primary-soft hover:text-primary"
                onClick={() => setBody((current) => current + tag)}
              >
                {tag}
              </button>
            ))}
          </div>

          <div className="mt-4 flex gap-2">
            <Button type="submit" disabled={save.isPending}>
              {save.isPending ? 'Saving…' : 'Save wording'}
            </Button>
            <Button type="button" variant="secondary" onClick={onDone}>Cancel</Button>
          </div>
        </form>
      ) : null}
    </>
  );
}

/**
 * The unknown-tag refusal is spelled out because it is actionable and the
 * server names the tags; everything else gets one message.
 */
function messageFor(caught: unknown): string {
  if (caught instanceof ApiError) {
    const unknown = (caught.problem as { unknownTags?: string[] }).unknownTags;
    if (unknown?.length) {
      return `These merge tags are not recognised and would reach the client as literal text: ${unknown.join(', ')}.`;
    }
    if (caught.status === 409) {
      return 'This notification cannot be switched off.';
    }
    if (caught.status === 412) {
      return 'Somebody else changed this template while you were editing. Reload and reapply.';
    }
  }
  return 'That did not save. Please try again.';
}

export default ObTemplatesPage;
