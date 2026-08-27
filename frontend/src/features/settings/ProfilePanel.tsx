import { useGetMe } from '@/api/generated/auth/auth';
import { Skeleton } from '@/components/ui/skeleton';
import { useAuthStore } from '@/features/auth/authStore';
import { SettingsSection } from './SettingsTabs';

/**
 * Who you are signed in as.
 *
 * <p><strong>Read-only, and it says so rather than showing disabled inputs.</strong>
 * There is no `PATCH /me` in the contract — a resource's name, email, role and
 * timezone are set by an Admin on S-11 (Masters → Resources), because role and
 * project membership decide row scope and a user who could edit their own role
 * would be editing their own permissions. Greyed-out fields would imply an
 * edit that is coming; a sentence naming where the change is actually made is
 * the truth and is more use.
 *
 * <p>Reads `GET /me` rather than `authStore.user`, and the two are not the same
 * thing. The store holds the identity the session was <em>issued</em> for, which
 * is right for the sidebar and the avatar — they must agree with the token the
 * requests carry. This panel is where someone comes to check what the server
 * currently believes about them, so a role changed by an Admin an hour ago
 * should show through here even though the access token still carries the old
 * claim. The store is the fallback while that request is in flight, so the
 * panel is never empty.
 */
export function ProfilePanel() {
  const sessionUser = useAuthStore((s) => s.user);
  const { data, isPending, isError } = useGetMe();
  const me = data?.data ?? sessionUser ?? undefined;

  return (
    <div className="flex flex-col gap-4">
      <SettingsSection
        title="Your details"
        description="Set by an administrator on Masters → Resources. Ask them if something here is wrong."
      >
        {isPending && !me ? (
          <div className="flex flex-col gap-3" aria-hidden>
            <Skeleton className="h-4 w-48" />
            <Skeleton className="h-4 w-64" />
            <Skeleton className="h-4 w-40" />
          </div>
        ) : (
          <dl className="grid gap-x-8 gap-y-4 sm:grid-cols-2">
            <Field label="Name" value={me?.displayName} />
            <Field label="Username" value={me?.username} />
            <Field label="Email" value={me?.email} />
            <Field label="Role" value={me?.role} />
            {/*
              Storage is UTC everywhere and the timezone is applied at
              presentation (CLAUDE.md · Conventions), so this is the one field
              here that changes what the user sees on every other screen —
              worth showing even though they cannot change it themselves.
            */}
            <Field label="Timezone" value={me?.timezone} />
            <Field
              label="Projects"
              value={me?.projectIds?.length ? String(me.projectIds.length) : 'None'}
            />
          </dl>
        )}

        {isError && !me ? (
          <p role="alert" className="mt-4 text-sm text-danger">
            Could not load your details. They may be out of date.
          </p>
        ) : null}
      </SettingsSection>
    </div>
  );
}

function Field({ label, value }: { label: string; value?: string | null }) {
  return (
    <div>
      <dt className="text-xs font-medium uppercase tracking-wide text-content-muted">{label}</dt>
      {/* An em dash rather than an empty cell: a blank looks like a failed render. */}
      <dd className="mt-1 text-sm text-content">{value || '—'}</dd>
    </div>
  );
}
