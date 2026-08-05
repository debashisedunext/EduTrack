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
        success:    'var(--success)',
        warning:    'var(--warning)',
        danger:     'var(--danger)',
        info:       'var(--info)',
      },
      borderRadius: { card: '12px', control: '8px', chip: '999px' },
      boxShadow: {
        rest:  '0 1px 2px rgba(16,24,40,.05)',
        modal: '0 8px 24px rgba(16,24,40,.10)',
      },
      fontFamily: { sans: ['Inter', 'Plus Jakarta Sans', 'system-ui', 'sans-serif'] },
    },
  },
  plugins: [],
} satisfies Config
