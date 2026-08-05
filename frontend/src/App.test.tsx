import { render, screen } from '@testing-library/react'
import App from './App'

describe('App scaffold', () => {
  it('renders and lists all four streams', () => {
    render(<App />)
    expect(screen.getByText(/Scaffold is running/i)).toBeInTheDocument()
    for (const owner of ['Shivendra', 'Ayush', 'Divyansh', 'Debashis']) {
      expect(screen.getByText(owner)).toBeInTheDocument()
    }
  })
})
