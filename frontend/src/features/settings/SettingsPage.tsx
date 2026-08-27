import { useSearchParams } from 'react-router-dom';
import { ProfilePanel } from './ProfilePanel';
import { SecurityPanel } from './SecurityPanel';
import { PreferencesPanel } from './PreferencesPanel';
import { SettingsTabs, type SettingsTab } from './SettingsTabs';

/**
 * `/settings` — the sidebar entry that has led to an empty state since it was
 * written.
 *
 * ## What this is not
 *
 * **It is not the org settings screen B-068 declined**, and that decision still
 * stands as written. Ayush looked at building a page around
 * `PUT /attachments/limits` on 22 Aug and concluded the documented API was
 * enough for one three-field org-wide setting — "revisit if a second org-wide
 * setting appears; one setting doesn't justify a surface" (DEPENDENCIES.md row
 * 24). Attachment limits are still not here, so that row is untouched and
 * nothing about it needs reopening.
 *
 * This is the *personal* half, which B-068 was not asked about. The trigger it
 * named has partly fired anyway: `POST /me/2fa/setup|confirm|disable` and
 * `GET`/`PUT /me/notification-preferences` are all implemented, tested and
 * reachable by nothing in this application. A sidebar entry leading to "there
 * is no settings screen" while three built endpoints have no UI is the wrong
 * end of that trade.
 *
 * ## The tabs, and the two that are missing
 *
 * Profile · Security · Preferences. `?tab=` carries the choice, so a tab is a
 * link somebody can paste into chat — the same reason `TicketDetailTabs` does
 * it, and the reason the ids below are stable words rather than indices.
 *
 * Two more belong here and are not this stream's to write:
 *
 * - **Notifications** — S-26's per-event matrix, `features/notifications/`,
 *   Stream D. See the note in `PreferencesPanel`.
 * - **Organisation** — attachment limits and whatever second org-wide setting
 *   eventually reopens B-068. Admin-only, and B's call to make.
 *
 * Adding either is one entry in the array below.
 */
const TAB_IDS = ['profile', 'security', 'preferences'] as const;
const DEFAULT_TAB = TAB_IDS[0];

export function SettingsPage() {
  const [params, setParams] = useSearchParams();
  const requested = params.get('tab');
  /*
    An unknown `?tab=` falls back to Profile rather than rendering nothing. A
    pasted link is the most likely source of one, and a blank page is a worse
    answer to a stale URL than the first tab is.
  */
  const activeId = TAB_IDS.find((id) => id === requested) ?? DEFAULT_TAB;

  const tabs: SettingsTab[] = [
    { id: 'profile', label: 'Profile', content: <ProfilePanel /> },
    { id: 'security', label: 'Security', content: <SecurityPanel /> },
    { id: 'preferences', label: 'Preferences', content: <PreferencesPanel /> },
  ];

  return (
    <div className="mx-auto flex w-full max-w-3xl flex-col gap-6 p-6">
      <header>
        <h1 className="text-lg font-semibold text-content">Settings</h1>
        <p className="mt-1 text-sm text-content-muted">
          Your account and how this browser behaves. Organisation-wide settings are configured by an
          administrator.
        </p>
      </header>

      <SettingsTabs
        tabs={tabs}
        activeId={activeId}
        onSelect={(id) =>
          /*
            `replace` so a run along the tab strip does not fill the back stack
            with tabs — Back should leave Settings, which is where the user came
            from, not walk them backwards through panels they glanced at.
          */
          setParams(id === DEFAULT_TAB ? {} : { tab: id }, { replace: true })
        }
      />
    </div>
  );
}
