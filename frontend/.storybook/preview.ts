import type { Preview } from '@storybook/react-vite'
import '../src/styles/tokens.css'

// Renders the library against the real C-002 tokens — Storybook is the contract
// other streams read, so it must show the actual design system, not a mock of it.
const preview: Preview = {
  parameters: {
    backgrounds: {
      options: {
        app: { name: 'app', value: '#F7F8FC' },
        surface: { name: 'surface', value: '#FFFFFF' }
      }
    },
    controls: { matchers: { color: /(background|color)$/i, date: /Date$/i } },
    layout: 'centered',
  },

  initialGlobals: {
    backgrounds: {
      value: 'app'
    }
  },

  tags: ['autodocs']
}

export default preview
