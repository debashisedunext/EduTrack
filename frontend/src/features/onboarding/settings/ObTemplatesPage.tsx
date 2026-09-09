import * as React from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import {
  getObNotificationTemplateVocabulary,
  listObNotificationTemplates,
} from '@/api/generated/onboarding-masters/onboarding-masters';
import type { ObNotificationTemplate } from '@/api/generated/model/obNotificationTemplate';
import http, { ApiError } from '@/api/http';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

/**
 * OB-12 — email templates. B-113.
 *
 * ## Three things the screen has to say that the row does not
 *
 * **Mandatory.** `ESCALATION` and `SIGNOFF` mail cannot be switched off. The
 * contract asks for the toggle to be "a locked statement rather than a control
 * whose only outcome is a refusal" — so a mandatory template renders the words
 * "Always sent" and no switch at all. A disabled checkbox still invites a click.
 *
 * **Not deliverable.** Phase 2 sends email only; a `WHATSAPP` template can be
 * authored and stored and nothing will dispatch it. Saying so on the row is the
 * whole reason `isDeliverable` exists — otherwise the admin configures something
 * that queues forever looking correct.
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
    return <p className="p-6 text-sm text-slate-500">Loading…</p>;
  }
  if (templates.isError) {
    return (
      <p role="alert" className="m-6 rounded-md bg-red-50 p-3 text-sm text-red-800">
        {templates.error instanceof ApiError && templates.error.status === 403
          ? 'Notification templates are OB Admin only.'
          : 'These templates could not be loaded.'}
      </p>
    );
  }

  const rows = templates.data?.data ?? [];

  return (
    <div className="p-6">
      <h1 className="text-xl font-semibold text-slate-900">Email templates</h1>
      <p className="mt-1 text-sm text-slate-600">
        The wording of every notification this module sends. Escalation and sign-off mail
        cannot be switched off.
      </p>

      <ul className="mt-6 space-y-3">
        {rows.map((template) => (
          <li key={template.id} className="rounded-lg border border-slate-200 p-4">
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
      <div className="flex items-baseline justify-between gap-4">
        <div>
          <p className="font-mono text-sm text-slate-900">{template.eventCode}</p>
          <p className="text-xs text-slate-500">
            {template.category} · {template.channel} · to {template.recipients?.join(', ')}
          </p>
        </div>
        <div className="flex items-center gap-3 text-xs">
          {template.isMandatory ? (
            // A locked statement, not a disabled control — a greyed switch
            // still invites a click, and its only outcome would be a 409.
            <span className="rounded bg-slate-100 px-2 py-1 text-slate-700">Always sent</span>
          ) : (
            <span className="text-slate-500">{template.isActive ? 'On' : 'Off'}</span>
          )}
          {!template.isDeliverable ? (
            <span className="rounded bg-amber-50 px-2 py-1 text-amber-900">
              Authored, not yet sending
            </span>
          ) : null}
          {!isEditing ? (
            <Button variant="secondary" onClick={onEdit}>Edit wording</Button>
          ) : null}
        </div>
      </div>

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
            <p role="alert" className="mb-3 rounded-md bg-red-50 p-3 text-sm text-red-800">
              {error}
            </p>
          ) : null}

          {template.channel === 'EMAIL' ? (
            <>
              <label htmlFor={`subject-${template.id}`} className="block text-sm font-medium text-slate-700">
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

          <label htmlFor={`body-${template.id}`} className="mt-3 block text-sm font-medium text-slate-700">
            Body
          </label>
          <textarea
            id={`body-${template.id}`}
            className="mt-1 w-full rounded-md border border-slate-300 p-2 font-mono text-sm"
            rows={6}
            value={body}
            onChange={(e) => setBody(e.target.value)}
            maxLength={20000}
            required
          />

          <p className="mt-2 text-xs text-slate-500">
            Merge tags — click to insert. A tag that is not on this list is refused when you
            save, because it would reach the client as literal braces.
          </p>
          <div className="mt-1 flex flex-wrap gap-1">
            {mergeTags.map((tag) => (
              <button
                key={tag}
                type="button"
                className="rounded bg-slate-100 px-2 py-1 font-mono text-xs text-slate-700"
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
