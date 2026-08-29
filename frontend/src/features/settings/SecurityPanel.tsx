import { Link } from 'react-router-dom';
import { Button } from '@/components/ui/button';
import { MIN_LENGTH } from '@/features/auth/passwordPolicy';
import { SettingsSection } from './SettingsTabs';
import { TwoFactorPanel } from './TwoFactorPanel';

/**
 * Everything that decides whether somebody else can be you.
 *
 * <p>The password section is a **link, not a second form**. `S-03` already
 * exists as a full page and is reached two other ways — the avatar menu, and
 * the forced redirect when `mustChangePassword` is set — and a copy embedded
 * here would be the third place the policy, the strength meter and the
 * `PASSWORD_REUSED` handling would have to stay in step. `ChangePasswordPage`
 * is deliberately routed outside the app shell so the forced variant has no
 * navigation to escape through, which is also why it is linked rather than
 * rendered inline.
 */
export function SecurityPanel() {
  return (
    <div className="flex flex-col gap-4">
      <SettingsSection
        title="Password"
        description={`At least ${MIN_LENGTH} characters, and not one of your last three — blueprint §10.3.`}
      >
        <Button asChild variant="secondary">
          <Link to="/change-password">Change password</Link>
        </Button>
      </SettingsSection>

      <TwoFactorPanel />
    </div>
  );
}
