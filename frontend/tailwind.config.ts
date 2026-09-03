import type { Config } from 'tailwindcss'
import tailwindcssAnimate from 'tailwindcss-animate'

/**
 * Design tokens come from blueprint §12.1 and are owned by Stream C (Divyansh),
 * task C-002. They are FROZEN after Sprint 0 — other streams request a token
 * rather than adding one, or the palette drifts across four features.
 *
 * This file wires the CSS variables in src/styles/tokens.css into Tailwind.
 * Divyansh owns both; the values below are the blueprint's, not placeholders.
 */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  /*
    D-15 · the dark theme is a class on <html>, not a media query.

    'class' rather than 'media' because the choice is manual (no
    prefers-color-scheme): a media query would be a second, competing input to
    one piece of state. `app/theme/themeStore` is the only writer of the class.

    Note that almost nothing in this codebase needs a `dark:` utility. Colour
    reaches components as `var(--token)`, so redefining the tokens under `.dark`
    in styles/tokens.css re-themes the app without touching a component. This
    switch exists for the residue — the handful of places that need a different
    *value* rather than a different colour, e.g. an overlay opacity — and so
    that `dark:` works when one of them appears.
  */
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        app:        'var(--bg-app)',
        surface:    'var(--bg-surface)',
        subtle:     'var(--bg-subtle)',
        border:     'var(--border)',
        primary:    { DEFAULT: 'var(--primary)', soft: 'var(--primary-soft)' },
        content:    { DEFAULT: 'var(--text-primary)', muted: 'var(--text-secondary)' },
        success:    { DEFAULT: 'var(--success)', text: 'var(--success-text)' },
        warning:    { DEFAULT: 'var(--warning)', text: 'var(--warning-text)' },
        danger:     { DEFAULT: 'var(--danger)',  text: 'var(--danger-text)' },
        info:       { DEFAULT: 'var(--info)',    text: 'var(--info-text)' },
        // DEFAULT/soft are for icons, borders and chip backgrounds (3:1 UI threshold).
        // `text` is a darkened, AA-normal-text-safe (4.5:1) variant — use it for chip
        // and badge labels, never the DEFAULT shade, which fails at small text sizes.
        level: {
          low:      { DEFAULT: 'var(--level-low)',      soft: 'var(--level-low-soft)',      text: 'var(--level-low-text)' },
          medium:   { DEFAULT: 'var(--level-medium)',   soft: 'var(--level-medium-soft)',   text: 'var(--level-medium-text)' },
          high:     { DEFAULT: 'var(--level-high)',     soft: 'var(--level-high-soft)',     text: 'var(--level-high-text)' },
          critical: { DEFAULT: 'var(--level-critical)', soft: 'var(--level-critical-soft)', text: 'var(--level-critical-text)' },
        },
        // Colour-blind-safe series order for charts — use in index order, never by name.
        chart: {
          1: 'var(--chart-1)', 2: 'var(--chart-2)', 3: 'var(--chart-3)', 4: 'var(--chart-4)',
          5: 'var(--chart-5)', 6: 'var(--chart-6)', 7: 'var(--chart-7)', 8: 'var(--chart-8)',
        },
        ribbon: {
          done:     { DEFAULT: 'var(--ribbon-done-fg)',    bg: 'var(--ribbon-done-bg)',    text: 'var(--ribbon-done-text)' },
          current:  { DEFAULT: 'var(--ribbon-current-fg)', bg: 'var(--ribbon-current-bg)', text: 'var(--ribbon-current-text)' },
          pending:  { DEFAULT: 'var(--ribbon-pending-fg)', bg: 'var(--ribbon-pending-bg)', text: 'var(--ribbon-pending-text)' },
          reworked: { DEFAULT: 'var(--ribbon-reworked)', text: 'var(--ribbon-reworked-text)' },
          breached: { DEFAULT: 'var(--ribbon-breached)', text: 'var(--ribbon-breached-text)' },
          // C-109 — the onboarding journey ribbon's two extra states.
          waiting:  { DEFAULT: 'var(--ribbon-waiting-fg)', bg: 'var(--ribbon-waiting-bg)', text: 'var(--ribbon-waiting-text)' },
          blocked:  { DEFAULT: 'var(--ribbon-blocked-fg)', bg: 'var(--ribbon-blocked-bg)', text: 'var(--ribbon-blocked-text)' },
        },
      },
      borderRadius: { card: '12px', control: '8px', chip: '999px' },
      boxShadow: {
        rest:  '0 1px 2px rgba(16,24,40,.05)',
        modal: '0 8px 24px rgba(16,24,40,.10)',
      },
      fontFamily: { sans: ['Inter', 'Plus Jakarta Sans', 'system-ui', 'sans-serif'] },
      // Base 14/20 per blueprint §12.2 — overrides Tailwind's default 16px `text-base`.
      fontSize: {
        base: ['14px', '20px'],
        caption: ['12px', '16px'],
        h3: ['16px', { lineHeight: '24px', fontWeight: '600' }],
        h2: ['20px', { lineHeight: '28px', fontWeight: '600' }],
        h1: ['24px', { lineHeight: '32px', fontWeight: '600' }],
      },
      transitionDuration: { DEFAULT: '180ms' },
      transitionTimingFunction: { DEFAULT: 'cubic-bezier(0, 0, 0.2, 1)' },
      // C-109 — the onboarding ribbon's animated status emojis (Onboarding-Module-Plan
      // §9, OB-05). `motion-reduce:animate-none` at each call site is what actually
      // honours prefers-reduced-motion; these five are only the motion itself.
      keyframes: {
        'emo-bounce': { '0%, 100%': { transform: 'translateY(0)' }, '50%': { transform: 'translateY(-3px)' } },
        'emo-pop':    { '0%, 100%': { transform: 'scale(1) rotate(0)' }, '50%': { transform: 'scale(1.25) rotate(8deg)' } },
        'emo-shake':  { '0%, 100%': { transform: 'rotate(0)' }, '25%': { transform: 'rotate(-12deg)' }, '75%': { transform: 'rotate(12deg)' } },
        'emo-clap':   { '0%, 100%': { transform: 'scale(1)' }, '50%': { transform: 'scale(1.2)' } },
        'emo-sad':    { '0%, 100%': { transform: 'translateY(0)' }, '50%': { transform: 'translateY(2px) rotate(-6deg)' } },
      },
      animation: {
        'emo-bounce': 'emo-bounce 1.2s ease-in-out infinite',
        'emo-pop':    'emo-pop 1.4s ease-in-out infinite',
        'emo-shake':  'emo-shake 1.6s ease-in-out infinite',
        'emo-clap':   'emo-clap 1s ease-in-out infinite',
        'emo-sad':    'emo-sad 2s ease-in-out infinite',
      },
    },
  },
  plugins: [tailwindcssAnimate],
} satisfies Config
