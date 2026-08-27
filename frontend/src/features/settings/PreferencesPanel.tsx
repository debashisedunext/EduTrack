import type { ReactNode } from 'react';
import { Moon, Sun } from 'lucide-react';
import { PushOptIn } from '@/features/notifications/PushOptIn';
import { cn } from '@/lib/utils';
import { useThemeStore, type Theme } from '@/app/theme/themeStore';
import { SettingsSection } from './SettingsTabs';

/**
 * How the app looks, and how it reaches you.
 *
 * <p>The theme control already existed as a toggle in the avatar menu, and this
 * is not a second source of truth for it — both write through `themeStore`,
 * which is the only thing that puts the class on `<html>`. What changes is the
 * shape: a menu item can only offer *the other* theme, so it can never show
 * which one is current. Two radios can, which is what a settings screen is for.
 *
 * <p><strong>The notification preference matrix is not here, and the gap is
 * deliberate.</strong> S-26's per-event grid is served and tested
 * (`GET`/`PUT /me/notification-preferences`, D-042) and has no UI anywhere in
 * this repository — but it belongs in `features/notifications/`, which is
 * Stream D's directory under TEAM-PLAN §6. It slots in beside these two as its
 * own tab, and it is Debashis's to write or to sign off. `PushOptIn` below is
 * D's component, rendered rather than reimplemented; its own doc comment says
 * it is meant for "wherever a user manages how they are reached — S-26's
 * preference screen", and until that screen exists this is the only place it
 * has ever been mounted.
 */
export function PreferencesPanel() {
  const theme = useThemeStore((s) => s.theme);
  const setTheme = useThemeStore((s) => s.setTheme);

  return (
    <div className="flex flex-col gap-4">
      <SettingsSection
        title="Theme"
        description="Stored in this browser, so it does not follow you to another machine."
      >
        <div role="radiogroup" aria-label="Theme" className="flex flex-wrap gap-3">
          <ThemeChoice
            value="light"
            current={theme}
            onSelect={setTheme}
            icon={<Sun className="h-4 w-4" aria-hidden />}
            label="Light"
          />
          <ThemeChoice
            value="dark"
            current={theme}
            onSelect={setTheme}
            icon={<Moon className="h-4 w-4" aria-hidden />}
            label="Dark"
          />
        </div>
        {/*
          Says what it does rather than what it is: the OS setting is
          deliberately ignored (`themeStore`), and "why is it dark again this
          morning" is the bug report that decision exists to prevent.
        */}
        <p className="mt-3 text-caption text-content-muted">
          Your choice is kept until you change it here. The system setting is not followed.
        </p>
      </SettingsSection>

      <SettingsSection
        title="Alerts"
        description="Which events reach you, and on which channel, is set on a screen that does not exist yet — this is the browser-level switch only."
      >
        <PushOptIn />
      </SettingsSection>
    </div>
  );
}

function ThemeChoice({
  value,
  current,
  onSelect,
  icon,
  label,
}: {
  value: Theme;
  current: Theme;
  onSelect: (theme: Theme) => void;
  icon: ReactNode;
  label: string;
}) {
  const selected = value === current;
  return (
    <button
      type="button"
      role="radio"
      aria-checked={selected}
      onClick={() => onSelect(value)}
      className={cn(
        'flex items-center gap-2 rounded-control border px-4 py-2 text-sm font-medium transition-colors',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary focus-visible:ring-offset-2',
        selected
          ? 'border-primary bg-primary-soft text-primary'
          : 'border-border bg-surface text-content hover:bg-subtle',
      )}
    >
      {icon}
      {label}
    </button>
  );
}
