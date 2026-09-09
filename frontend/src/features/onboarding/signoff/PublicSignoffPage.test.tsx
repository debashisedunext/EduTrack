import { describe, expect, it, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import { PublicSignoffPage } from './PublicSignoffPage'

/**
 * B-115 · OB-09 against the mock server.
 *
 * Rendered bare rather than through `Routes`: the page takes nothing from the
 * router — the token comes off `window.location` and there is no navigation
 * anywhere on it, deliberately — so mounting a router here would be scaffolding
 * that proves nothing.
 *
 * Fixture note — `db.ts`'s `OB_SIGNOFFS`: row 1 is `PENDING` on Cambridge
 * Heights School's "UAT & issue closure" step (step 617, sent to Sana Qureshi)
 * with token `ob-signoff-demo-token-1`. Row 2 is already `SIGNED`, which is
 * what makes the refusal assertions below real rather than a made-up value
 * the mock could satisfy by accident.
 * The mock's OTP is always `123456` once requested.
 */
const TOKEN = 'ob-signoff-demo-token-1'

function visit(search: string) {
  window.history.replaceState(null, '', `/signoff${search}`)
  return render(<PublicSignoffPage />)
}

async function identify(user: ReturnType<typeof userEvent.setup>, otp = '123456') {
  await user.click(await screen.findByRole('button', { name: /email me a code/i }))
  const field = await screen.findByLabelText(/six-digit code/i)
  await user.type(field, otp)
  await user.click(screen.getByRole('button', { name: /continue/i }))
}

describe('OB-09 public sign-off page', () => {
  beforeEach(() => {
    window.history.replaceState(null, '', '/signoff')
  })

  it('asks for a code before it shows anything about the client', async () => {
    visit(`?token=${TOKEN}`)

    expect(await screen.findByRole('heading', { name: /confirm it is you/i })).toBeInTheDocument()
    // Nothing about the sign-off is fetched before the OTP is proved, so the
    // client's name cannot be on screen at this point however the page is built.
    expect(screen.queryByText(/cambridge/i)).not.toBeInTheDocument()
  })

  it('renders the client, the service and its checklist once the code is proved', async () => {
    const user = userEvent.setup()
    visit(`?token=${TOKEN}`)

    await identify(user)

    expect(await screen.findByLabelText(/your full name/i)).toBeInTheDocument()
    expect(screen.getByText(/cambridge/i)).toBeInTheDocument()
  })

  it('takes the token out of the address bar as soon as it is read', async () => {
    visit(`?token=${TOKEN}`)

    // The API takes the token in a POST body; the address bar is the half the
    // API cannot answer, and it is what lands in history and every Referer.
    await waitFor(() => expect(window.location.search).not.toContain('token'))
    // Still usable — the value was read into state before the URL was replaced.
    expect(await screen.findByRole('button', { name: /email me a code/i })).toBeInTheDocument()
  })

  it('records the acceptance and says so', async () => {
    const user = userEvent.setup()
    visit(`?token=${TOKEN}`)
    await identify(user)

    await user.type(await screen.findByLabelText(/your full name/i), 'Priya Raman')
    await user.click(screen.getByRole('button', { name: /^accept$/i }))

    expect(await screen.findByRole('heading', { name: /your acceptance is recorded/i }))
      .toBeInTheDocument()
  })

  it('will not submit an acceptance with no typed name', async () => {
    const user = userEvent.setup()
    visit(`?token=${TOKEN}`)
    await identify(user)

    await screen.findByLabelText(/your full name/i)
    // A name the person entered themselves is what distinguishes acceptance
    // from a click, so the button cannot be reachable without one.
    expect(screen.getByRole('button', { name: /^accept$/i })).toBeDisabled()
  })

  it('gives one message for a wrong code, saying nothing about whether the link is real', async () => {
    const user = userEvent.setup()
    visit(`?token=${TOKEN}`)

    await identify(user, '000000')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/could not continue with this link/i)
    expect(alert).not.toHaveTextContent(/expired/i)
    expect(alert).not.toHaveTextContent(/incorrect code/i)
  })

  it('gives that same message for a link that does not exist', async () => {
    const user = userEvent.setup()
    visit('?token=not-a-real-token')

    // The mock answers 202 to the request either way — deliberately
    // indistinguishable — so the difference only appears on verify, and it
    // must read identically to the wrong-code case above.
    await identify(user)

    expect(await screen.findByRole('alert'))
      .toHaveTextContent(/could not continue with this link/i)
  })

  it('gives that same message for a sign-off that has already been decided', async () => {
    const user = userEvent.setup()
    visit('?token=ob-signoff-demo-token-2')

    await identify(user)

    expect(await screen.findByRole('alert'))
      .toHaveTextContent(/could not continue with this link/i)
  })

  it('says the link is incomplete when there is no token at all', async () => {
    visit('')

    expect(await screen.findByRole('heading', { name: /not complete/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /email me a code/i })).not.toBeInTheDocument()
  })

  it('offers no navigation anywhere — the reader has no account to reach', async () => {
    const user = userEvent.setup()
    visit(`?token=${TOKEN}`)
    await identify(user)
    await screen.findByLabelText(/your full name/i)

    // "nothing that hints at the rest of the application". A link here is
    // reachable by someone we know nothing about beyond one email.
    expect(screen.queryAllByRole('link')).toHaveLength(0)
  })
})
