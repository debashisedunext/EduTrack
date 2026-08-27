import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, expect, it, vi } from 'vitest';

import type { Me } from '@/api/generated/model/me';
import { initialAuthState, useAuthStore } from '@/features/auth/authStore';
import { SettingsPage } from './SettingsPage';

/**
 * `/settings` used to be a sidebar entry leading to an empty state. These cases
 * pin the three things that made it worth replacing:
 *
 * - the panels behind the tabs actually render;
 * - the tab is in the URL, so it is a link a colleague can paste;
 * - the profile is honest about being read-only, rather than showing fields
 *   that look editable and are not (there is no `PATCH /me`).
 */

const useGetMe = vi.fn();
vi.mock('@/api/generated/auth/auth', () => ({
  useGetMe: (...args: unknown[]) => useGetMe(...args),
  // The security tab mounts `TwoFactorPanel`, which imports these three. They
  // are never pressed here — `TwoFactorPanel.test.tsx` owns that behaviour.
  beginTwoFactorEnrolment: vi.fn(),
  confirmTwoFactorEnrolment: vi.fn(),
  disableTwoFactor: vi.fn(),
}));

const me: Me = {
  id: 7,
  displayName: 'Ravi Kumar',
  role: 'DEVELOPER',
  username: 'ravi.kumar',
  email: 'ravi@edunext.test',
  timezone: 'Asia/Kolkata',
  projectIds: [1, 2],
};

function renderAt(path = '/settings') {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[path]}>
        <SettingsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.resetAllMocks();
  useAuthStore.setState({ ...initialAuthState, status: 'authenticated', user: me });
  useGetMe.mockReturnValue({ data: { data: me }, isPending: false, isError: false });
});

it('opens on Profile and shows what the server says about you', () => {
  renderAt();

  expect(screen.getByRole('tab', { name: 'Profile', selected: true })).toBeInTheDocument();
  expect(screen.getByText('ravi.kumar')).toBeInTheDocument();
  expect(screen.getByText('Asia/Kolkata')).toBeInTheDocument();
  // Read-only, and it says where the change is actually made rather than
  // rendering disabled inputs that imply an edit is coming.
  expect(screen.getByText(/Masters → Resources/)).toBeInTheDocument();
  expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
});

it('falls back to the session user while the request is in flight', () => {
  // `authStore.user` is the identity the session was issued for. Showing it
  // beats an empty card, and it is what every other screen is already using.
  useGetMe.mockReturnValue({ data: undefined, isPending: true, isError: false });
  renderAt();

  expect(screen.getByText('Ravi Kumar')).toBeInTheDocument();
});

it('honours ?tab= so a tab is a link somebody can paste', () => {
  renderAt('/settings?tab=security');

  expect(screen.getByRole('tab', { name: 'Security', selected: true })).toBeInTheDocument();
  expect(screen.getByRole('heading', { name: 'Two-factor authentication' })).toBeInTheDocument();
});

it('falls back to Profile on a stale or invented tab rather than rendering nothing', () => {
  renderAt('/settings?tab=organisation');

  expect(screen.getByRole('tab', { name: 'Profile', selected: true })).toBeInTheDocument();
});

it('moves between tabs with the arrow keys, one tab stop for the strip', async () => {
  renderAt();

  await userEvent.tab();
  expect(screen.getByRole('tab', { name: 'Profile' })).toHaveFocus();

  await userEvent.keyboard('{ArrowRight}');
  expect(screen.getByRole('tab', { name: 'Security', selected: true })).toHaveFocus();

  await userEvent.keyboard('{End}');
  expect(screen.getByRole('tab', { name: 'Preferences', selected: true })).toHaveFocus();
  expect(screen.getByRole('heading', { name: 'Theme' })).toBeInTheDocument();
});
