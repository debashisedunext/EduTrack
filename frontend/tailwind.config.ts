import type { Config } from 'tailwindcss'

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
    },
  },
  plugins: [],
} satisfies Config
